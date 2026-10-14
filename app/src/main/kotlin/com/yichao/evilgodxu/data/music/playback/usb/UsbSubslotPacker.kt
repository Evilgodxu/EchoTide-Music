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
 * 音量按 15 位定点增益施加：raw 路径绕过了混音器，`AudioManager` 的音量与音效都不再作用于这条流，
 * 系统音量滑杆若想要有用就只能自己做。起止增益相同且为满幅时整段缩放不参与，写出仍是逐位原样——独占
 * 直出的价值正在于此，不该因为一个恒为满幅的乘数而被牺牲掉。小于 1 时按线性幅度缩放（与媒体3 的音量
 * 口径一致），并带半步进位以免每次向下取整都引入同样的直流偏置。
 *
 * 起止增益不同即在**本块缓冲内**逐样本斜到目标值：音量键按下即生效，而一次改动不会在缓冲边界上留一个
 * 台阶。台阶本身未必听成咔哒，但每块缓冲都来一次就是持续的背景噪声。
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
    gainFromQ15: Int = UNITY_GAIN_Q15,
    gainToQ15: Int = gainFromQ15,
): Int {
    require(subslotBytes in 1..4) { "子槽宽度只能是 1..4 字节，收到 $subslotBytes" }
    val bytesPerSourceSample = Util.getPcmFrameSize(pcmEncoding, 1)
    if (gainFromQ15 == gainToQ15) {
        val applyGain = gainFromQ15 != UNITY_GAIN_Q15
        while (source.remaining() >= bytesPerSourceSample) {
            val value = PcmAudioUtil.readAs32BitIntPcm(source, pcmEncoding)
            writeSubslot(output, if (applyGain) scaleByGain(value, gainFromQ15) else value, subslotBytes)
        }
    } else {
        // 斜坡按本块样本数均分：末样本正好落在目标增益上，下一块即从目标值接着走
        val samples = source.remaining() / bytesPerSourceSample
        val span = (samples - 1).coerceAtLeast(1)
        var index = 0
        while (source.remaining() >= bytesPerSourceSample) {
            val value = PcmAudioUtil.readAs32BitIntPcm(source, pcmEncoding)
            val gain = gainFromQ15 + ((gainToQ15 - gainFromQ15).toLong() * index / span).toInt()
            writeSubslot(output, scaleByGain(value, gain), subslotBytes)
            index++
        }
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

/** 15 位定点的满幅增益：等于不缩放 */
internal const val UNITY_GAIN_Q15 = 32768

/**
 * 音量换算成 15 位定点增益。
 *
 * 满幅及以上一律取满幅：独占链路上放大没有意义（源已按满幅送出），而超出满幅的乘数会把样本推过
 * 整型边界、听到的是削顶失真。
 */
internal fun volumeToGainQ15(volume: Float): Int {
    if (volume >= 1f) return UNITY_GAIN_Q15
    return (volume.coerceAtLeast(0f) * UNITY_GAIN_Q15).toInt()
}

/** 按定点增益缩放：带半步进位，长期平均才不偏向负值一侧 */
private fun scaleByGain(value: Int, gainQ15: Int): Int =
    ((value.toLong() * gainQ15 + UNITY_GAIN_Q15 / 2) shr 15).toInt()
