package com.yichao.evilgodxu.data.music.playback.usb

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.ByteBuffer

private const val LOG_TAG = "UsbOutputSink"

/**
 * 输出路由：在平台路径与 USB 独占路径之间二选一。
 *
 * 两条路在设备层面互斥，不可能同时成立——平台路径要求 USB 音频接口归内核驱动（平台的音频链路正是
 * 经它把声音送到解码器），而独占路径的成立条件恰恰是把该驱动解绑、把接口收归本应用。所以这里不是
 * "两条路叠加"，而是每次配置时选一条，选中谁就由谁承接本次播放的全部数据。
 *
 * 选择的依据有两条，且都不看设置开关本身：
 * - **独占是否已就绪**（[exclusiveReady]）：开关开着不等于接管成功，设备没接入、没授权、接管失败时
 *   平台路径照常；
 * - **本次格式能否原样送出**（[UsbExclusiveAudioSink.canHandle]）：设备未声明该采样率、或子槽放不下源
 *   位深时不勉强，退回平台路径。独占的意义就在于原样送出，做不到时不成立比勉强出声更诚实。
 *
 * 独占在建流或写出上失败即退回平台路径，并把这次失败报给设备接入侧（[onExclusiveFailed]）——
 * 本项目对直出的底线是"尽力而为，绝不带走整曲播放"，独占是这条路的一种成色，不该比它更硬。
 */
@OptIn(UnstableApi::class)
internal class UsbOutputSink(
    /** 平台路径：现有直出与系统混音那一整套 */
    private val platform: AudioSink,
    /** 独占路径 */
    private val exclusive: UsbExclusiveAudioSink,
    /** 独占是否已就绪（开关开启且设备已接管） */
    private val exclusiveReady: () -> Boolean,
    /** 独占链路失效的报告出口：由接入侧归还设备，避免带着一个已废的会话反复重试 */
    private val onExclusiveFailed: () -> Unit = {},
) : AudioSink {

    private enum class Route { PLATFORM, EXCLUSIVE }

    private var route = Route.PLATFORM

    /** 渲染器最近一次下发的配置：独占链路失效时就地退回平台路径要用它重配 */
    private var lastConfig: AudioSink.AudioSinkConfig? = null

    private val activeRoute: AudioSink
        get() = if (route == Route.EXCLUSIVE) exclusive else platform

    /**
     * 播放在两条路上都各自成立，故只作转发；数据与位置的查询一律问生效的那一条。
     *
     * 设置类调用（音量、静音跳过、音频属性、会话号、效果、时钟）**同时下发两条**：渲染器只在需要时
     * 下发一次，若只给生效方，另一条路上留着的旧值会在路由切换后才显出问题——最典型的是音量停在满幅，
     * 表现为"一换到独占就突然变响"。
     */
    override fun setListener(listener: AudioSink.Listener) {
        platform.setListener(listener)
        exclusive.setListener(listener)
    }

    override fun setClock(clock: Clock) {
        platform.setClock(clock)
        exclusive.setClock(clock)
    }

    // 能力申报一律按平台路径回答：两条路都接受线性 PCM，而平台路径的默认变体才决定解码器是否被要求
    // 输出浮点（见 PerDeviceAudioSink 的能力基准），路由切换不该让解码输出格式跟着漂移
    override fun supportsFormat(format: Format): Boolean = platform.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = platform.getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        platform.getFormatOffloadSupport(format)

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        lastConfig = audioSinkConfig
        val next = when {
            !exclusiveReady() -> Route.PLATFORM
            exclusive.canHandle(format) -> Route.EXCLUSIVE
            else -> Route.PLATFORM
        }
        switchTo(next, audioSinkConfig)
    }

    /**
     * 切到 [next] 并完成本次配置。
     *
     * 退出使用的一方先复位再配进入方：复位会释放它持有的输出并回调，而进入方此刻尚未建立输出，
     * 顺序反了会让渲染器先看到"新输出已建立"、再看到"输出已释放"，界面与延迟采集随之读到空值。
     */
    private fun switchTo(next: Route, audioSinkConfig: AudioSink.AudioSinkConfig) {
        if (next != route) {
            val outgoing = activeRoute
            val from = route
            route = next
            outgoing.reset()
            CrashLogManager.logInfo(
                LOG_TAG,
                "路由切换：${if (from == Route.EXCLUSIVE) "独占" else "平台"} → " +
                    "${if (next == Route.EXCLUSIVE) "独占" else "平台"}",
            )
        }
        if (next == Route.PLATFORM) {
            platform.configure(audioSinkConfig)
            return
        }
        try {
            exclusive.configure(audioSinkConfig)
        } catch (e: AudioSink.ConfigurationException) {
            // 独占建不起来就退回平台路径，本次播放照常；会话随之交还，免得下次配置又走一遍同样的失败
            CrashLogManager.logException(LOG_TAG, "独占配置失败，退回平台路径", e)
            onExclusiveFailed()
            switchTo(Route.PLATFORM, audioSinkConfig)
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (route != Route.EXCLUSIVE) {
            return platform.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        return try {
            exclusive.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        } catch (e: AudioSink.WriteException) {
            recoverToPlatform(e)
            true
        } catch (e: AudioSink.InitializationException) {
            recoverToPlatform(e)
            true
        }
    }

    /**
     * 独占链路已终止（停止或设备失联）时就地退回平台路径。
     *
     * 本次缓冲已被等时路径消费，重放不了，故调用方如实判为已消费——只用一段极短的缺口换整曲继续。
     * 本项目对直出的底线是"尽力而为、绝不带走整曲播放"，独占是这条路的一种成色，不该比它更硬。
     */
    private fun recoverToPlatform(cause: Exception) {
        CrashLogManager.logException(LOG_TAG, "独占链路写出失败，退回平台路径", cause)
        onExclusiveFailed()
        lastConfig?.let { config -> switchTo(Route.PLATFORM, config) }
    }

    override fun play() = activeRoute.play()

    override fun pause() = activeRoute.pause()

    override fun handleDiscontinuity() = activeRoute.handleDiscontinuity()

    override fun flush() = activeRoute.flush()

    // 复位两条路：下一曲的配置会重新决定走哪条，留在旧状态里的那一方可能在下次切换时才暴露
    override fun reset() {
        platform.reset()
        exclusive.reset()
    }

    override fun release() {
        platform.release()
        exclusive.release()
    }

    override fun playToEndOfStream() = activeRoute.playToEndOfStream()

    override fun isEnded(): Boolean = activeRoute.isEnded

    override fun hasPendingData(): Boolean = activeRoute.hasPendingData()

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        activeRoute.getCurrentPositionUs(sourceEnded)

    override fun getAudioTrackBufferSizeUs(): Long = activeRoute.getAudioTrackBufferSizeUs()

    override fun getAudioCapabilities(): AudioCapabilities? = activeRoute.audioCapabilities

    override fun setVolume(volume: Float) {
        platform.setVolume(volume)
        exclusive.setVolume(volume)
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        platform.setSkipSilenceEnabled(skipSilenceEnabled)
        exclusive.setSkipSilenceEnabled(skipSilenceEnabled)
    }

    override fun getSkipSilenceEnabled(): Boolean = activeRoute.skipSilenceEnabled

    // 变速功能已移除：playback parameters 保持默认值，不接受外部调速请求
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        platform.setPlaybackParameters(playbackParameters)
        exclusive.setPlaybackParameters(playbackParameters)
    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        platform.setAudioAttributes(audioAttributes)
        exclusive.setAudioAttributes(audioAttributes)
    }

    override fun getAudioAttributes(): AudioAttributes? = activeRoute.audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {
        platform.setAudioSessionId(audioSessionId)
        exclusive.setAudioSessionId(audioSessionId)
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        platform.setAuxEffectInfo(auxEffectInfo)
        exclusive.setAuxEffectInfo(auxEffectInfo)
    }

    // 隧道是平台音频轨的概念，独占路径上不存在；固定交给平台一侧，免得独占生效时把请求变成异常
    override fun enableTunnelingV21() = platform.enableTunnelingV21()

    override fun disableTunneling() {
        platform.disableTunneling()
        exclusive.disableTunneling()
    }

    override fun setAudioOutputProvider(audioProvider: androidx.media3.exoplayer.audio.AudioOutputProvider) {
        platform.setAudioOutputProvider(audioProvider)
    }
}
