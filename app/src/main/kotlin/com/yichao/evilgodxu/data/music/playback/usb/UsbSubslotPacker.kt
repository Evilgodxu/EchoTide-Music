package com.yichao.evilgodxu.data.music.playback.usb

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.PcmAudioUtil
import java.nio.ByteBuffer

/**
 * 把解码输出打包成设备子槽的字节排布。
 *
 * 规则是**保留最高位字节**：样本统一按左对齐 32 位看待（满幅落在最高位），设备的子槽取它的高
 * [subslotBytes] 个字节——子槽 3 丢掉最低字节，子槽 2 再丢一个。只要源位深不超过 `subslotBytes × 8`
 * 就逐位无损（如 16 位源放进 24 位槽：左移补零，一个比特都没动）。
 *
 * 因此调用方必须先确认子槽容得下源位深再打包：[planUsbOutput] 已经把这一步挡在选择之前，本函数不做
 * 静默降级——源位深更深时低位会被直接丢弃，而"少了几位"这种事在听感上未必立刻可辨，比直接失败更难查。
 *
 * 打包留在 Kotlin 而非原生层，是因为它是可判定的纯变换：能在 JVM 上逐字节核对，就不必把正确性押在
 * 真机试听上。
 *
 * @return 末尾凑不满一个样本的字节数，正常为 0
 */
@OptIn(UnstableApi::class)
internal fun packToSubslot(
    source: ByteBuffer,
    output: ByteBuffer,
    pcmEncoding: Int,
    subslotBytes: Int,
): Int {
    require(subslotBytes in 1..4) { "子槽宽度只能是 1..4 字节，收到 $subslotBytes" }
    val bytesPerSourceSample = Util.getPcmFrameSize(pcmEncoding, 1)
    while (source.remaining() >= bytesPerSourceSample) {
        writeSubslot(output, PcmAudioUtil.readAs32BitIntPcm(source, pcmEncoding), subslotBytes)
    }
    val leftover = source.remaining()
    if (leftover > 0) {
        // 余数消费掉，否则同一缓冲会反复读出同样的余数
        source.position(source.limit())
    }
    return leftover
}

/**
 * 写出一个样本的高 [subslotBytes] 个字节，小端。
 *
 * 左对齐 32 位下，源的有效位落在最高字节一侧；跳过最低的 `4 - subslotBytes` 个字节即得到目标排布。
 * 子槽 4 时跳过 0 字节，即整字照搬。
 */
internal fun writeSubslot(output: ByteBuffer, value: Int, subslotBytes: Int) {
    val skip = 4 - subslotBytes
    for (index in 0 until subslotBytes) {
        output.put((value shr (8 * (skip + index))).toByte())
    }
}
