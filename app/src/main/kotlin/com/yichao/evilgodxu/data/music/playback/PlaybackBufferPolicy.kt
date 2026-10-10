package com.yichao.evilgodxu.data.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlin.math.max

/**
 * 音频轨的缓冲尺寸策略。
 *
 * 媒体3 对线性 PCM 的默认口径是「固定按 500ms 申请」（`DefaultAudioTrackBufferSizeProvider` 的
 * `DEFAULT_PCM_BUFFER_DURATION_US`）。本策略把 PCM 的申请量收到 [TARGET_BUFFER_MS]；压缩与卸载仍按
 * 媒体3 的默认口径交给它自己算——那是编码格式的固有缓冲，与输出链路的延迟无关。
 *
 * 这个量的两侧各自约束：
 * - 下限一侧是抗欠载余量。上游（解码 → 写出）的偶发停顿全靠这一段吸收，**没有别的兜底**：欠载不会被
 *   察觉并自动放大缓冲——动态改容量虽有 `AudioTrack.setBufferSizeInFrames`，媒体3 不会自己去调，
 *   重建轨道时仍按本策略取同一个值。平台自身的下限也不作数，它只保证「建得起轨道」，不保证「供不上
 *   也不断」。余量不足的表现就是断续。
 * - 上限一侧是延迟。缓冲里的音频是「写进音频轨 → 发声」这条链路上可压缩的那一段
 *   （见 [OutputLatencyReading.fullChainMs]）：留得越多，暂停、跳转与开始发声的滞后越明显。
 *   与之相对，「已离开音频轨 → 发声」那一段（[OutputLatencyReading.afterTrackMs]）由混音缓冲、HAL
 *   与设备传输决定，与本策略无关——判断本策略是否生效要看音频轨的实得容量与全链路读数，不能看那一段。
 *
 * 取 200ms 是两者的折中：足以吸收上游停顿，又远短于媒体3 的 500ms。目标按**时长**给定、用采样率换算
 * 成帧，而不是固定帧数——帧与时长的对应随采样率变化，固定帧数在高采样率上会短到不足以抗欠载。
 *
 * 抬回下限这一步由本策略自己完成（见 [trackBufferBytes] 的 `max`），不是平台「判断设备是否支持」后的
 * 回退：平台只在音频轨一侧再兜一次下限。抬回的目标是**平台下限**，既不是媒体3 的 500ms 默认口径，
 * 也不是某个「合理值」。故本策略在平台下限更高时（实测某平台约 80ms）不生效，此时实得量即平台下限。
 *
 * 起播阈值随之一起变，但它不是独立的保护层：轨道要灌到阈值才出声，而阈值的初值就等于缓冲容量
 * （`AudioTrack.getStartThresholdInFrames`），两者是随动关系。故 200ms 的代价之一就是起播要多等这一段
 * （暂停后恢复同理）——这是换取抗欠载余量所付的价，不是配置失误；要缩短它只能下调本值，
 * 而抗欠载余量随之减少，两者同源，不能各取一头。
 */
@OptIn(UnstableApi::class)
internal object PlaybackBufferPolicy {

    /**
     * 目标缓冲时长（毫秒）。
     *
     * 以时长而非帧数给定：同一帧数在不同采样率下对应的时长不同，而这条策略要保的是时间意义上的余量。
     */
    const val TARGET_BUFFER_MS = 200

    /** 目标缓冲帧数：按 [TARGET_BUFFER_MS] 与采样率换算 */
    fun targetBufferFrames(sampleRate: Int): Int = sampleRate * TARGET_BUFFER_MS / 1000

    /**
     * 按目标时长申请缓冲的字节数，且不低于平台下限。
     *
     * [pcmFrameSize] 为每帧字节数；按帧数换算的结果本就是帧的整数倍，符合媒体3 对缓冲尺寸的约定。
     * [sampleRate] 用于把目标时长换算成帧数，取值须与音频轨本身的采样率一致。
     */
    fun trackBufferBytes(minBufferSizeInBytes: Int, pcmFrameSize: Int, sampleRate: Int): Int =
        max(minBufferSizeInBytes, targetBufferFrames(sampleRate) * pcmFrameSize)

    /**
     * 交给媒体3 输出提供者的策略：PCM 走 [trackBufferBytes]，其余原样转回默认口径。
     *
     * 媒体3 在本策略的返回值之上还会再抬一次平台下限；此处仍自行取一次下界，一是接口约定要求返回值
     * 不低于 minBufferSizeInBytes，二是 [IntPcmAudioSink] 直接复用 [trackBufferBytes]，
     * 两处口径一致才不会在同一个目标上给出两种结论。
     */
    val provider: DefaultAudioSink.AudioTrackBufferSizeProvider =
        object : DefaultAudioSink.AudioTrackBufferSizeProvider {
            override fun getBufferSizeInBytes(
                minBufferSizeInBytes: Int,
                encoding: Int,
                outputMode: Int,
                pcmFrameSize: Int,
                sampleRate: Int,
                bitrate: Int,
                maxAudioTrackPlaybackSpeed: Double,
            ): Int = if (outputMode == DefaultAudioSink.OUTPUT_MODE_PCM) {
                trackBufferBytes(minBufferSizeInBytes, pcmFrameSize, sampleRate)
            } else {
                DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT.getBufferSizeInBytes(
                    minBufferSizeInBytes,
                    encoding,
                    outputMode,
                    pcmFrameSize,
                    sampleRate,
                    bitrate,
                    maxAudioTrackPlaybackSpeed,
                )
            }
        }
}
