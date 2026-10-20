package com.yichao.evilgodxu.data.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlin.math.max

/**
 * 输出缓冲的时长口径：直出与独占两条路取同一个值。
 *
 * 两条路各有一处缓冲，尺寸都由 [TARGET_BUFFER_MS] 定：平台侧是音频轨申请多大（媒体3 的默认变体经
 * [provider]，自建音频轨的 [IntPcmAudioSink] 经 [trackBufferBytes]），独占侧是等时环留多长
 * （`UsbIsoOutput` 的环形缓冲）。两处取不同的值会让同一台设备「换个开关就换一种表现」，故统一口径。
 *
 * **显式钉定而不是沿用媒体3 的默认值**：媒体3 对线性 PCM 的默认目标同为 500ms
 * （`DefaultAudioTrackBufferSizeProvider` 的 `DEFAULT_PCM_BUFFER_DURATION_US`），但它只覆盖由媒体3
 * 自己建轨的那几个变体——[IntPcmAudioSink] 自建音频轨，独占路径根本不经过媒体3，靠默认值就只剩一处
 * 受管。钉在本处，三个落点才会同时变。压缩与卸载仍交回媒体3 的默认口径：那是编码格式的固有缓冲，
 * 与输出链路的延迟无关。
 *
 * 这个量的两侧各自约束：
 * - 下限一侧是抗欠载余量。上游（解码 → 写出）的偶发停顿全靠这一段吸收，**没有别的兜底**：欠载不会被
 *   察觉并自动放大缓冲——动态改容量虽有 `AudioTrack.setBufferSizeInFrames`，媒体3 不会自己去调，
 *   重建轨道时仍按本策略取同一个值。平台自身的下限也不作数，它只保证「建得起轨道」，不保证「供不上
 *   也不断」。余量不足的表现就是断续。
 * - 上限一侧是延迟。缓冲里的音频是「写进音频轨 → 发声」这条链路上可压缩的那一段
 *   （见 [OutputLatencyReading.trackResidentMs]）：留得越多，暂停、跳转与开始发声的滞后越明显。
 *   与之相对，「已离开音频轨 → 发声」那一段（[OutputLatencyReading.afterTrackMs]）由混音缓冲、HAL
 *   与设备传输决定，与本策略无关——判断本策略是否生效要看面板上「当前缓冲」那一行是否等于本值换算出的
 *   时长（高于即被平台下限或独占侧的在飞段下限抬高），以及「缓冲驻留」读数，不能看那一段。
 *
 * [TARGET_BUFFER_MS] 是这两侧的取向选择：余量够平掉上游的偶发停顿即可，不为此把暂停、跳转与起播的
 * 滞后一并拖长（独占侧的等时环同理，见 `UsbIsoOutput`）。目标按**时长**给定、用采样率换算成帧，而不是
 * 固定帧数——帧与时长的对应随采样率变化，固定帧数在高采样率上会短到不足以抗欠载。
 *
 * **本值只管「上游供不上」这一类停顿，管不了线程被抢占**：独占路径上真正吸收调度抖动的是原生层的在飞
 * URB 段与回收线程的优先级（见 `UsbIsoOutput`、`UsbIsoStream`）；平台路径上音频轨由音频服务按自己的
 * 节奏取走，本应用线程被抢占并不会让已写入的那一段停下。两处都不由本值决定，调大它换不来对抢占的容忍度。
 *
 * 抬回下限这一步由本策略自己完成（见 [trackBufferBytes] 的 `max`），不是平台「判断设备是否支持」后的
 * 回退：平台只在音频轨一侧再兜一次下限。抬回的目标是**平台下限**，不是某个「合理值」。故本策略在平台
 * 下限更高时不生效，此时实得量即平台下限；独占侧的下限则是「不得短于在飞段」（见 `UsbIsoOutput`）。
 *
 * 起播阈值（轨道灌到多少才出声）另按 [startThresholdFrames] 取容量的一半，不再沿用「等于容量」的初值：
 * 初值让起播与暂停恢复都要多等整个缓冲，而阈值只作用于**起播那一刻**——一旦起唱，播放只要求「有数据
 * 可播」，存量随即回升到容量，稳态余量不受影响。代价落在起播那一小段：起始存量减半，上游若恰好在此刻
 * 停顿，欠载会更早出现。要缩短这段等待只能下调本值或该阈值，两者同源，不能各取一头。
 */
@OptIn(UnstableApi::class)
internal object PlaybackBufferPolicy {

    /**
     * 目标缓冲时长（毫秒）：直出与独占两条路共用。
     *
     * 以时长而非帧数给定：同一帧数在不同采样率下对应的时长不同，而这条策略要保的是时间意义上的余量。
     * 取值是这两侧的基准取向——够吸收上游的偶发停顿，又不把暂停、跳转与起播的等待一并拖长。
     */
    const val TARGET_BUFFER_MS = 80

    /**
     * 目标缓冲帧数：按 [TARGET_BUFFER_MS] 与采样率换算。
     *
     * 独占侧同样经它换算等时环长度，两条路的缓冲深度因此由同一个式子给出。
     */
    fun targetBufferFrames(sampleRate: Int): Int = sampleRate * TARGET_BUFFER_MS / 1000

    /**
     * 起播阈值帧数：取实际申请到的容量的一半。
     *
     * 这是轨道建起后要经 `AudioTrack.setStartThresholdInFrames` 写入的值——平台没有在
     * `AudioTrack.Builder` 上给出对应入口，只能建轨后设置。阈值只作用于起播与欠载后的恢复：
     * 音频轨一旦低于它就停、灌到它才重新出声，因此取**容量的一半**而不是整份——欠载后的静默段才不至于
     * 拖到整个缓冲那么长，那段静默就是听感上的断口。以实得帧数入参而不是另按采样率重算：平台下限
     * 更高时实得量大于目标值，阈值应跟着实得量走。至少取 1 帧，否则平台按非法值拒绝。
     */
    fun startThresholdFrames(grantedFrames: Int): Int = max(1, grantedFrames / 2)

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
