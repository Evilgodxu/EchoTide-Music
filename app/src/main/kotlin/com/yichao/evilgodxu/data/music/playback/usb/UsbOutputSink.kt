package com.yichao.evilgodxu.data.music.playback.usb

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Timeline
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.ByteBuffer

private const val LOG_TAG = "UsbOutputSink"

/**
 * 输出路由：平台与 USB 独占二选一。
 *
 * 两条路互斥，每次配置选一条。
 * 独占失败即退回平台，绝不带走整曲播放。
 */
@OptIn(UnstableApi::class)
internal class UsbOutputSink(
    /** 平台路径：现有直出与系统混音那一整套 */
    private val platform: AudioSink,
    /** 独占路径 */
    private val exclusive: UsbExclusiveAudioSink,
    /** 独占是否已就绪（开关开启且设备已接管） */
    private val exclusiveReady: () -> Boolean,
    /** 链路失效时归还设备，避免反复重试 */
    private val onExclusiveFailed: () -> Unit = {},
    /** 报告本次配置后是否由独占路径承接 */
    private val onRouteChanged: (Boolean) -> Unit = {},
    /**
     * 解码输出格式，供面板展示。
     * 平台由 PerDeviceAudioSink 自报，独占代报
     */
    private val onDecodedFormatChanged: (Int, Int, Int) -> Unit = { _, _, _ -> },
    /** 写出取向：独占一律整型子槽，固定报未以浮点写出 */
    private val onVariantChanged: (Boolean) -> Unit = {},
) : AudioSink {

    private enum class Route { PLATFORM, EXCLUSIVE }

    private var route = Route.PLATFORM

    /** 最近一次配置，链路失效退回平台时用它重配 */
    private var lastConfig: AudioSink.AudioSinkConfig? = null

    /** Timeline 查询的复用容器，省去每次新建 */
    private val period = Timeline.Period()
    private val window = Timeline.Window()

    /**
     * 播放线程 Handler，改道投回播放线程执行。
     * 构造在主线程，首次 configure 时捕获。
     */
    private var playbackHandler: Handler? = null

    /**
     * 渲染器最后下发的起停意图。
     * 复位会清状态，改道后须补一次 play。
     */
    private var playRequested = false

    /** 已整体释放，投递进来的改道不再执行 */
    private var released = false

    private val activeRoute: AudioSink
        get() = if (route == Route.EXCLUSIVE) exclusive else platform

    /**
     * 数据与位置问生效方，设置类同时下发两条。
     * 只给生效方会让另一条留旧值。
     */
    override fun setListener(listener: AudioSink.Listener) {
        platform.setListener(listener)
        exclusive.setListener(listener)
    }

    override fun setClock(clock: Clock) {
        platform.setClock(clock)
        exclusive.setClock(clock)
    }

    // 能力申报按平台回答，避免解码格式漂移
    override fun supportsFormat(format: Format): Boolean = platform.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = platform.getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        platform.getFormatOffloadSupport(format)

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        lastConfig = audioSinkConfig
        released = false
        if (playbackHandler == null) playbackHandler = Looper.myLooper()?.let(::Handler)
        val trackId = trackIdOf(audioSinkConfig)
        switchTo(routeFor(audioSinkConfig.format, trackId), audioSinkConfig, trackId)
    }

    /**
     * 本次配置对应的曲目 id。
     * 换曲一瞬只有 period 指向真正的曲目。
     */
    private fun trackIdOf(config: AudioSink.AudioSinkConfig): Long? {
        val mediaPeriodId = config.mediaPeriodId ?: return null
        val timeline = config.timeline
        if (timeline.isEmpty) return null
        val periodIndex = timeline.getIndexOfPeriod(mediaPeriodId.periodUid)
        if (periodIndex == C.INDEX_UNSET) return null
        timeline.getPeriod(periodIndex, period)
        return timeline.getWindow(period.windowIndex, window).mediaItem.mediaId.toLongOrNull()
    }

    /**
     * 本次格式该由哪条路承接。
     * 未就绪或送不了本曲格式都退回平台。
     */
    private fun routeFor(format: Format, trackId: Long?): Route = when {
        !exclusiveReady() -> Route.PLATFORM
        exclusive.canHandle(format, trackId) -> Route.EXCLUSIVE
        else -> Route.PLATFORM
    }

    /**
     * 切到 next 并完成配置。
     * 先复位退出方再配进入方。
     * 同路由也照常配置，供自发改道重建平台。
     */
    private fun switchTo(next: Route, audioSinkConfig: AudioSink.AudioSinkConfig, trackId: Long?) {
        if (next != route) {
            val outgoing = activeRoute
            route = next
            outgoing.reset()
            onRouteChanged(next == Route.EXCLUSIVE)
        }
        if (next == Route.PLATFORM) {
            platform.configure(audioSinkConfig)
        } else {
            try {
                exclusive.configure(audioSinkConfig, trackId)
            } catch (e: AudioSink.ConfigurationException) {
                // 独占失败退回平台，会话交还
                CrashLogManager.logException(LOG_TAG, "独占配置失败，退回平台路径", e)
                onExclusiveFailed()
                switchTo(Route.PLATFORM, audioSinkConfig, trackId)
                return
            }
            // 独占没有 PerDeviceAudioSink 那一环
            val format = audioSinkConfig.format
            onDecodedFormatChanged(format.sampleRate, format.channelCount, format.pcmEncoding)
            onVariantChanged(false)
        }
        // 进入方可能刚复位，不补 play 会停滞超时
        if (playRequested) activeRoute.play()
    }

    /**
     * 独占成色在曲中变化时重跑选路。
     *
     * 接管一成立平台即写不出去，归还后独占只剩废会话。
     * 路由不变而位深该变时，补一次重建。
     */
    fun syncRouting() {
        val handler = playbackHandler ?: return
        handler.post {
            val config = lastConfig ?: return@post
            if (released) return@post
            val trackId = trackIdOf(config)
            val next = routeFor(config.format, trackId)
            if (next == route) {
                // 路由不变而位深变了：预读没赶上，补一次重建
                if (next == Route.EXCLUSIVE && exclusive.planDiffers(config.format, trackId)) {
                    runCatching { switchTo(next, config, trackId) }.onFailure {
                        CrashLogManager.logException(LOG_TAG, "源位深落定后的重配失败", it)
                    }
                }
                return@post
            }
            // 改道由本类自发起，失败吞掉，下次重配会照常重来
            runCatching { switchTo(next, config, trackId) }.onFailure {
                CrashLogManager.logException(LOG_TAG, "独占成色变化后的改道失败", it)
            }
        }
    }

    /**
     * 接管方发起的一次接管：放开平台、接管、改道。
     *
     * 须先放开平台，其异步收尾会抹掉备用设置。
     * 三步放进同一任务，中途不让出。
     */
    fun runTakeover(claim: () -> Unit) {
        val handler = playbackHandler
        if (handler == null) {
            claim()
            return
        }
        handler.post {
            if (released) {
                // 输出已作废，但接管仍该发生，供下次配置选到独占
                claim()
                return@post
            }
            if (route == Route.PLATFORM) platform.reset()
            claim()
            val config = lastConfig ?: return@post
            val trackId = trackIdOf(config)
            runCatching { switchTo(routeFor(config.format, trackId), config, trackId) }.onFailure {
                CrashLogManager.logException(LOG_TAG, "接管后的改道失败", it)
            }
        }
    }

    /**
     * 接管方发起的一次归还：先拆流再放手。
     *
     * 顺序与接管相反，在飞 URB 会打向已归还的端点。
     * 之后重跑选路，落回平台。
     */
    fun runRelease(release: () -> Unit) {
        val handler = playbackHandler
        if (handler == null) {
            exclusive.reset()
            release()
            return
        }
        handler.post {
            exclusive.reset()
            release()
            if (released) return@post
            val config = lastConfig ?: return@post
            val trackId = trackIdOf(config)
            // 改道由本类自发起，失败吞掉，下次重配会照常重来
            runCatching { switchTo(routeFor(config.format, trackId), config, trackId) }.onFailure {
                CrashLogManager.logException(LOG_TAG, "归还设备后的改道失败", it)
            }
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
     * 独占链路已终止时就地退回平台路径。
     *
     * 缓冲已消费，只用短缺口换整曲继续。
     * 先归还设备再重配，免打到旧接口。
     */
    private fun recoverToPlatform(cause: Exception) {
        CrashLogManager.logException(LOG_TAG, "独占链路写出失败，退回平台路径", cause)
        onExclusiveFailed()
        lastConfig?.let { config -> switchTo(Route.PLATFORM, config, trackIdOf(config)) }
    }

    /**
     * 启停同时下发两条路。
     * 未生效方记下启停意图，切入才不旧。
     */
    override fun play() {
        playRequested = true
        platform.play()
        exclusive.play()
    }

    override fun pause() {
        playRequested = false
        platform.pause()
        exclusive.pause()
    }

    override fun handleDiscontinuity() = activeRoute.handleDiscontinuity()

    override fun flush() = activeRoute.flush()

    // 复位两条路，下一曲配置重新选路
    override fun reset() {
        platform.reset()
        exclusive.reset()
    }

    override fun release() {
        released = true
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

    // 变速已移除，保持默认参数，不接受外部调速
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

    // 隧道仅平台音频轨有，固定交给平台一侧
    override fun enableTunnelingV21() = platform.enableTunnelingV21()

    override fun disableTunneling() {
        platform.disableTunneling()
        exclusive.disableTunneling()
    }

    override fun setAudioOutputProvider(audioProvider: androidx.media3.exoplayer.audio.AudioOutputProvider) {
        platform.setAudioOutputProvider(audioProvider)
    }
}
