package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * 按目标设备重建的音频输出。
 *
 * 浮点输出是 [DefaultAudioSink] 的构造期开关，实例内不可更改：它决定高分辨率 PCM 源写成 32 位浮点
 * 还是先降回 16 位整型。而位完美输出流（USB 独占）只接纳与混音器属性逐字段一致的播放，
 * 设备提供哪些格式随设备而变，因此这里持有两个变体，在每次 [configure] 时按目标设备决策，
 * 决策变化即切换变体——即按设备重建输出，使该设备上能挂上的格式成为当前写出格式。
 *
 * 平台与 media3 都不对线性 PCM 做设备级能力探测，故非独占时一律保持浮点输出；
 * 决策只在 [configure] 处落地——切换需要重开 AudioTrack，只能发生在渲染器重配点。
 * 变体切换不需要回放历史配置：所有设置类调用同时下发到两个变体。
 */
@OptIn(UnstableApi::class)
class PerDeviceAudioSink(
    context: Context,
    private val audioManager: AudioManager,
    /** 独占已钉定的 USB 输出设备，null 表示当前未独占 */
    private val exclusiveTarget: () -> AudioDeviceInfo?,
    /** 输出变体变更回调：报告本次配置后是否以浮点 PCM 写出 */
    private val onOutputVariantChanged: (Boolean) -> Unit = {},
    /** 输出编码变更回调：报告生效变体音频轨实际写出的 PCM 编码，null 表示当前链路无音频轨 */
    private val onOutputEncodingChanged: (Int?) -> Unit = {},
) : AudioSink {

    /** 默认变体：高分辨率源以 32 位浮点写出，保留解码精度 */
    private val floatSink: AudioSink = buildSink(context, enableFloatOutput = true)

    /** 降级变体：一律以 16 位整型写出，供位完美流只提供整型格式的设备使用 */
    private val intSink: AudioSink = buildSink(context, enableFloatOutput = false)

    /**
     * 音频轨的接收回调：先经 [OutputEncodingListener] 截取写出编码，再透传给渲染器。
     *
     * 两个变体各持一份，编码按变体归属上报；渲染器尚未接管时出口仍为静默实现，事件不外泄但照常截取，
     * 避免起播瞬间的编码漏报。
     */
    private val floatListener: AudioSink.Listener = OutputEncodingListener(
        delegate = { delegateFor(useFloat = true) },
        onOutputEncodingChanged = { reportOutputEncoding(useFloat = true, it) },
        onOutputReleased = { reportOutputReleased(useFloat = true) },
    )

    private val intListener: AudioSink.Listener = OutputEncodingListener(
        delegate = { delegateFor(useFloat = false) },
        onOutputEncodingChanged = { reportOutputEncoding(useFloat = false, it) },
        onOutputReleased = { reportOutputReleased(useFloat = false) },
    )

    /** 渲染器交给本接收器的回调出口：未接管时为静默实现 */
    private var rendererListener: AudioSink.Listener = SILENT_LISTENER

    /** 当前生效的变体；初始按浮点输出，与无独占设备时的决策一致 */
    private var floatActive = true

    /**
     * 自上次音频轨建立以来，输出是否已被请求释放。
     *
     * 释放音频轨走 media3 的共享异步释放线程（起步延迟 20ms），真正释放后才把释放事件投回播放线程，
     * 投递时不校验发出方是否已被替换。因此「上一个音频轨的释放」可能晚于「本次音频轨的建立」到达，
     * 若照搬释放事件清空编码，就会把当前链路的有效编码抹掉，且在下一次建轨前无从恢复——这正是编码
     * 偶发显示为未建立的成因。
     *
     * 故释放事件只在标志仍立着时才算属于当前链路：请求过释放（[flush]、[reset]）而其间未重新建轨，
     * 说明当前确无音频轨；建轨（[reportOutputEncoding]）即撤销标志。标志与音频轨事件同在播放线程读写，
     * 无需额外同步。
     */
    private var outputReleaseRequested = false

    init {
        floatSink.setListener(floatListener)
        intSink.setListener(intListener)
    }

    /**
     * 该变体的回传出口：生效方透传给渲染器，退出使用的一方改走静默实现。
     *
     * 退出方复位时会释放音频轨并回调，此时渲染器正在重配，事件不应再外泄。
     */
    private fun delegateFor(useFloat: Boolean): AudioSink.Listener =
        if (useFloat == floatActive) rendererListener else SILENT_LISTENER

    /**
     * 上报音频轨的写出编码。
     *
     * 只认生效方的取值：变体切换会复位退出方，其音频轨的释放回调随之到达，而该轨已不属于当前链路，
     * 照搬会把生效方已建立的值清成空。建轨即撤销释放请求——当前链路又有音频轨了。
     */
    private fun reportOutputEncoding(useFloat: Boolean, encoding: Int?) {
        if (useFloat != floatActive) return
        outputReleaseRequested = false
        onOutputEncodingChanged(encoding)
    }

    /**
     * 上报音频轨的释放。
     *
     * 释放事件异步投回，可能晚于后续建轨到达，那时链路已由新音频轨接管，清空会把它的编码一并抹掉，
     * 故只认「请求过释放而其间未重新建轨」的释放。
     */
    private fun reportOutputReleased(useFloat: Boolean) {
        if (useFloat != floatActive) return
        if (!outputReleaseRequested) return
        outputReleaseRequested = false
        onOutputEncodingChanged(null)
    }

    private fun buildSink(context: Context, enableFloatOutput: Boolean): AudioSink =
        // 变速/变调交给 AudioTrack 原生处理，避免 Sonic 软件变速在低速时产生噪声
        DefaultAudioSink.Builder(context)
            .setEnableAudioOutputPlaybackParameters(true)
            .setEnableFloatOutput(enableFloatOutput)
            .build()

    /** 当前生效的变体：流数据、位置查询与格式查询都只经它 */
    private fun active(): AudioSink = if (floatActive) floatSink else intSink

    /**
     * 该曲目是否需要浮点写出。
     *
     * 线性 PCM 的设备级浮点能力无从探测（AudioTrack 经混音输出普遍接受浮点，media3 也只按 API 级别
     * 判定支持），按设备分化的只有位完美流的格式匹配，因此仅在独占已钉定设备时决策。
     * 判定与独占侧共用 [selectBitPerfectMixer]：选中的条目即独占侧待下发的混音器属性，写出编码须与
     * 之逐字段一致——已核实，格式与偏好不符时 AudioFlinger 不会报错，而是把该轨静默混音输出，
     * 「已独占」名不副实，故两处必须取同一口径。
     */
    private fun requiresFloatOutput(format: Format): Boolean {
        val device = exclusiveTarget() ?: return true
        // 16 位及以下源在两种变体下都写成整型，重建不会改变挂接结果
        if (!Util.isEncodingHighResolutionPcm(format.pcmEncoding)) return true
        val bitPerfect = selectBitPerfectMixer(
            audioManager.getSupportedMixerAttributes(device),
            format.sampleRate,
            format.channelCount,
            format.pcmEncoding,
        )
        // 只有 16 位整型条目可挂接时降级为整型变体；其余（含仅 24/32 位整型条目、无条目）保持浮点
        return bitPerfect?.format?.encoding != AudioFormat.ENCODING_PCM_16BIT
    }

    private fun forEachSink(action: (AudioSink) -> Unit) {
        action(floatSink)
        action(intSink)
    }

    private fun switchTo(useFloat: Boolean) {
        if (useFloat == floatActive) return
        // 取向先翻转再复位退出方：其音频轨的释放回调因此被判为非生效方，既不外泄给渲染器，
        // 也不会把生效方的上报值抹掉
        val outgoing = active()
        floatActive = useFloat
        outgoing.reset()
        // 进入方此刻暂无音频轨，重建发生在下一次数据写入，届时由创建回调给出真实编码；
        // 退出方的编码已不属于当前链路，先清空，避免用上一变体的取值冒充当前写出编码
        onOutputEncodingChanged(null)
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        switchTo(requiresFloatOutput(audioSinkConfig.format))
        onOutputVariantChanged(floatActive)
        active().configure(audioSinkConfig)
    }

    override fun setListener(listener: AudioSink.Listener) {
        rendererListener = listener
    }

    override fun supportsFormat(format: Format): Boolean = active().supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = active().getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        active().getFormatOffloadSupport(format)

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        active().getCurrentPositionUs(sourceEnded)

    override fun getAudioTrackBufferSizeUs(): Long = active().getAudioTrackBufferSizeUs()

    override fun getAudioCapabilities(): AudioCapabilities? = active().audioCapabilities

    override fun getAudioAttributes(): AudioAttributes? = active().audioAttributes

    override fun getPlaybackParameters(): PlaybackParameters = active().playbackParameters

    override fun getSkipSilenceEnabled(): Boolean = active().skipSilenceEnabled

    override fun isEnded(): Boolean = active().isEnded

    override fun hasPendingData(): Boolean = active().hasPendingData()

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean = active().handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

    override fun play() = active().play()

    override fun pause() = active().pause()

    // 音频轨的实际释放只发生在 media3 的 flush 内（释放异步延后），故释放请求在此登记；
    // 登记的时点早于释放事件，后续建轨会撤销它，据此把迟到的释放事件判为不属于当前链路
    override fun flush() {
        outputReleaseRequested = true
        active().flush()
    }

    override fun playToEndOfStream() = active().playToEndOfStream()

    override fun handleDiscontinuity() = active().handleDiscontinuity()

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) =
        forEachSink { it.setPlaybackParameters(playbackParameters) }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) =
        forEachSink { it.setSkipSilenceEnabled(skipSilenceEnabled) }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) =
        forEachSink { it.setAudioAttributes(audioAttributes) }

    override fun setAudioSessionId(audioSessionId: Int) =
        forEachSink { it.setAudioSessionId(audioSessionId) }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) =
        forEachSink { it.setAuxEffectInfo(auxEffectInfo) }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) =
        forEachSink { it.setPreferredDevice(audioDeviceInfo) }

    override fun setVirtualDeviceId(virtualDeviceId: Int) =
        forEachSink { it.setVirtualDeviceId(virtualDeviceId) }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) =
        forEachSink { it.setOutputStreamOffsetUs(outputStreamOffsetUs) }

    override fun setPlayerId(playerId: PlayerId?) = forEachSink { it.setPlayerId(playerId) }

    override fun setClock(clock: Clock) = forEachSink { it.setClock(clock) }

    override fun setVolume(volume: Float) = forEachSink { it.setVolume(volume) }

    override fun enableTunnelingV21() = forEachSink { it.enableTunnelingV21() }

    override fun disableTunneling() = forEachSink { it.disableTunneling() }

    override fun setOffloadMode(offloadMode: Int) = forEachSink { it.setOffloadMode(offloadMode) }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) =
        forEachSink { it.setOffloadDelayPadding(delayInFrames, paddingInFrames) }

    override fun setAudioOutputProvider(audioOutputProvider: AudioOutputProvider) =
        forEachSink { it.setAudioOutputProvider(audioOutputProvider) }

    override fun reset() {
        outputReleaseRequested = true
        forEachSink { it.reset() }
    }

    override fun release() = forEachSink { it.release() }

    private companion object {
        /** 渲染器未接管期间与未生效变体的回调出口，事件不外泄 */
        val SILENT_LISTENER = object : AudioSink.Listener {
            override fun onPositionDiscontinuity() = Unit

            override fun onUnderrun(
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) = Unit

            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = Unit
        }
    }
}

/**
 * 音频接收回调的转接器：透传渲染器的回调，并截取音频轨被创建与被释放时的写出编码。
 *
 * 写出编码只在音频轨被创建的那一刻可知，且不经监听器无从取得，故在此截取而非按源格式与变体推测
 * ——16 位及以下源在浮点变体下同样写成整型，推测值未必等于实际写出的编码。
 *
 * 出口按调用时刻取值：变体退出使用后其音频轨的释放回调仍会到达，此时出口已回到静默实现，事件不外泄；
 * 编码与释放则交由 [PerDeviceAudioSink] 按变体归属与释放请求判定去留。
 *
 * 接口的默认方法不会随委托转出（Kotlin 的接口委托只为抽象方法生成转发），故每个回调都必须显式透传，
 * 漏写会让渲染器收不到对应事件。
 */
private class OutputEncodingListener(
    private val delegate: () -> AudioSink.Listener,
    private val onOutputEncodingChanged: (Int?) -> Unit,
    private val onOutputReleased: () -> Unit,
) : AudioSink.Listener {

    override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) {
        onOutputEncodingChanged(audioTrackConfig.encoding)
        delegate().onAudioTrackInitialized(audioTrackConfig)
    }

    override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) {
        onOutputReleased()
        delegate().onAudioTrackReleased(audioTrackConfig)
    }

    override fun onPositionDiscontinuity() = delegate().onPositionDiscontinuity()

    override fun onPositionAdvancing(playbackPositionUs: Long) =
        delegate().onPositionAdvancing(playbackPositionUs)

    override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) =
        delegate().onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)

    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) =
        delegate().onSkipSilenceEnabledChanged(skipSilenceEnabled)

    override fun onOffloadBufferEmptying() = delegate().onOffloadBufferEmptying()

    override fun onOffloadBufferFull() = delegate().onOffloadBufferFull()

    override fun onAudioSinkError(audioSinkError: Exception) =
        delegate().onAudioSinkError(audioSinkError)

    override fun onAudioCapabilitiesChanged() = delegate().onAudioCapabilitiesChanged()

    override fun onSilenceSkipped() = delegate().onSilenceSkipped()

    override fun onAudioSessionIdChanged(audioSessionId: Int) =
        delegate().onAudioSessionIdChanged(audioSessionId)
}
