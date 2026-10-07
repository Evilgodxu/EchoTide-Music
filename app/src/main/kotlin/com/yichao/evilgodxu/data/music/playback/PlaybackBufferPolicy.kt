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
 * 它是「写进音频轨 → 发声」这条全链路里可压缩的那一段（见 [OutputLatencyReading.fullChainMs]），
 * 也决定了音频轨的起播阈值——轨道要灌到阈值才出声，而平台把阈值默认取为缓冲容量。
 *
 * 与之相对，「已离开音频轨 → 发声」那一段（[OutputLatencyReading.afterTrackMs]）由混音缓冲、HAL
 * 与设备传输决定，与本策略无关。判断本策略是否生效要看音频轨的实得容量与全链路读数，不能看那一段。
 *
 * 本策略把 PCM 的申请量降到 [TARGET_FRAMES] 帧（44.1kHz 下 11.6ms、48kHz 下 10.7ms）。压缩与卸载
 * 仍按媒体3 的默认口径交给它自己算：那是编码格式的固有缓冲，与输出链路的延迟无关。
 *
 * 平台下限是硬边界，而且常远大于本目标：`AudioTrack.getMinBufferSize` 给出的量由输出端口的周期决定，
 * 低于它的申请会被抬回下限。实测某平台的下限即约 80ms——48kHz 下 3844 帧、44.1kHz 下 3536 帧，
 * 500ms 的默认申请因此只压到下限，[TARGET_FRAMES] 报多少都一样。故本值应读作「请求量」而非最终容量：
 * 它只保证不超过媒体3 的默认口径，能否落到目标由平台决定，实得量随建轨日志记录（见 [PerDeviceAudioSink]
 * 与 [Int24PcmAudioSink] 的留痕），据此可查是否被抬回。
 *
 * 下限占主导时，继续下调本值不再有任何效果：那一段的时长由平台的输出端口周期决定，本策略已无余量，
 * 再往下只能离开平台默认的输出路径（低延迟或直出通路），不属本策略的范围。
 *
 * 取舍：缓冲同时是抗欠载的余量，压到下限意味着上游一旦停供就立刻欠载。播放稳定性优先时上调
 * [TARGET_FRAMES] 即可；但要留意上调到下限之上才会真正放宽，在下限之内调多少都一样。
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
