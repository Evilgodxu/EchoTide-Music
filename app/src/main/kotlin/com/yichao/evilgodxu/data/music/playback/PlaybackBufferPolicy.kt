package com.yichao.evilgodxu.data.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlin.math.max

/**
 * 音频轨的缓冲尺寸策略。
 *
 * 媒体3 对线性 PCM 的默认口径是「固定按 500ms 申请」（`DefaultAudioTrackBufferSizeProvider` 的
 * `DEFAULT_PCM_BUFFER_DURATION_US`）。这个量照搬的是播放稳定性，代价是音频轨长期驻留半秒音频：
 * 它是「写进音频轨 → 发声」这条全链路里可压缩的那一段（见 [OutputLatencyReading.fullChainMs]）。
 *
 * 与之相对，「已离开音频轨 → 发声」那一段（[OutputLatencyReading.afterTrackMs]）由混音缓冲、HAL
 * 与设备传输决定，与本策略无关。判断本策略是否生效要看音频轨的实得容量与全链路读数，不能看那一段。
 *
 * 本策略把 PCM 的申请量降到 [TARGET_FRAMES] 帧（44.1kHz 下 11.6ms、48kHz 下 10.7ms）。压缩与卸载
 * 仍按媒体3 的默认口径交给它自己算：那是编码格式的固有缓冲，与输出链路的延迟无关。
 *
 * 抬回下限这一步由本策略自己完成（见 [trackBufferBytes] 的 `max`），不是平台「判断设备是否支持」后的
 * 回退：平台只在音频轨一侧再兜一次下限。抬回的目标是**平台下限**，既不是媒体3 的 500ms 默认口径，
 * 也不是某个「合理值」——两者在实得量上完全不同，别把前者当后者。
 *
 * 更要留意的是反过来的一侧：**缓冲过小没有任何兜底**。欠载不会被察觉并自动放大缓冲——动态改容量虽有
 * `AudioTrack.setBufferSizeInFrames`，媒体3 不会自己去调，重建轨道时仍按本策略取同一个值。故本值是否
 * 有风险取决于平台下限，不能一概而论：
 * - 下限高于本值（实测某平台为约 80ms：48kHz 下 3844 帧、44.1kHz 下 3536 帧）：本值不生效，
 *   改动的只是「不再按媒体3 的 500ms 申请」，抗欠载余量等于平台下限，无额外风险；
 * - 下限低于本值（低延迟通路的下限常在几毫秒级）：本值会被真正采纳，抗欠载余量随之只剩十几毫秒，
 *   上游一旦停供就出现断续。
 *
 * 上限一侧则无关紧要：继续下调本值在下限占主导时没有任何效果——那一段的时长由平台的输出端口周期决定，
 * 再往下只能离开平台默认的输出路径（低延迟或直出通路），不属本策略的范围。反之要放宽抗欠载余量，
 * 须上调到平台下限之上；在下限之内调多少都一样。
 *
 * 起播阈值随之一起变，但它不是独立的保护层：轨道要灌到阈值才出声，而阈值的初值就等于缓冲容量
 * （`AudioTrack.getStartThresholdInFrames`），容量变小阈值同步变小，是随动而非兜底。
 */
@OptIn(UnstableApi::class)
internal object PlaybackBufferPolicy {

    /**
     * 目标缓冲帧数。
     *
     * 音频轨的容量以帧为单位，与采样率无关地表达时长：采样率越高，同一帧数对应的时长越短。
     */
    const val TARGET_FRAMES = 512

    /**
     * 按目标帧数申请缓冲的字节数，且不低于平台下限。
     *
     * [pcmFrameSize] 为每帧字节数；按帧数换算的结果本就是帧的整数倍，符合媒体3 对缓冲尺寸的约定。
     */
    fun trackBufferBytes(minBufferSizeInBytes: Int, pcmFrameSize: Int): Int =
        max(minBufferSizeInBytes, TARGET_FRAMES * pcmFrameSize)

    /**
     * 交给媒体3 输出提供者的策略：PCM 走 [trackBufferBytes]，其余原样转回默认口径。
     *
     * 媒体3 在本策略的返回值之上还会再抬一次平台下限；此处仍自行取一次下界，一是接口约定要求返回值
     * 不低于 minBufferSizeInBytes，二是 [Int24PcmAudioSink] 直接复用 [trackBufferBytes]，
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
                trackBufferBytes(minBufferSizeInBytes, pcmFrameSize)
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
