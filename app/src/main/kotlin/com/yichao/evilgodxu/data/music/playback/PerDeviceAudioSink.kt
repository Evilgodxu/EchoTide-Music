package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.ByteBuffer

// 音频轨诊断的类名前缀：缓冲请求量与实得量写在此名下，与音频信息面板的延迟读数互为印证
private const val LOG_TAG = "PerDeviceAudioSink"

// 帧数换算时长用，与采样率相除即得毫秒
private const val MILLIS_PER_SECOND = 1000

/**
 * 按目标设备重建的音频输出。
 *
 * 写出编码是 [DefaultAudioSink] 的构造期取向，实例内不可更改：浮点变体把高分辨率 PCM 源写成 32 位浮点，
 * 整型变体把高分辨率源降回 16 位整型，24 位变体则由 [Int24PcmAudioSink] 自行写出 24 位整型——设备只声明
 * 该编码时，媒体3 的默认输出无从产出它，只能另起一个输出实现。而 USB 独占建立的专用输出流只接纳与混音器
 * 属性逐字段一致的播放，设备声明哪些格式随机型而变，因此这里持有三个变体，在每次 [configure] 时按目标设备
 * 决策，决策变化即切换变体——即按设备重建输出，使该设备上能挂上的格式成为当前写出格式。
 *
 * 平台与 media3 都不对线性 PCM 做设备级能力探测，故非独占时一律保持浮点输出；
 * 决策只在 [configure] 处落地——切换需要重开 AudioTrack，只能发生在渲染器重配点。
 * 变体切换不需要回放历史配置：所有设置类调用同时下发到三个变体。
 */
@OptIn(UnstableApi::class)
class PerDeviceAudioSink(
    private val context: Context,
    private val audioManager: AudioManager,
    /** 已建立独占输出流的 USB 输出设备，null 表示当前未独占 */
    private val exclusiveTarget: () -> AudioDeviceInfo?,
    /** 输出变体变更回调：报告本次配置后是否以浮点 PCM 写出 */
    private val onOutputVariantChanged: (Boolean) -> Unit = {},
    /** 输出编码变更回调：报告生效变体音频轨实际写出的 PCM 编码，null 表示当前链路无音频轨 */
    private val onOutputEncodingChanged: (Int?) -> Unit = {},
    /**
     * 音频轨变更回调：报告生效变体当前使用的音频轨，释放时报告 null。
     *
     * 输出延迟只能从音频轨本身取（平台没有按设备查询延迟的公开接口，默认值又与当前路由无关），
     * 故把轨道本体一并交给上层，时机与写出编码完全一致。
     */
    private val onAudioTrackChanged: (AudioTrack?) -> Unit = {},
    /**
     * 解码输出格式回调：报告解码头实际输出的采样率、声道与线性 PCM 编码。
     *
     * 独占输出据此下发混音器属性，且必须在音频轨建立前生效，故在 [configure] 的开头上报——
     * 这里是全链路最早拿到解码输出格式的地方：容器格式（轨道回调）只给采样率与声道，
     * 压缩源的 PCM 编码要等解码头出格式才知道。
     */
    private val onDecodedFormatChanged: (Int, Int, Int) -> Unit = { _, _, _ -> },
) : AudioSink {

    /** 默认变体：高分辨率源以 32 位浮点写出，保留解码精度 */
    private val floatSink: AudioSink = buildSink(OutputVariant.FLOAT, enableFloatOutput = true)

    /** 降级变体：一律以 16 位整型写出，供独占流只提供整型格式的设备使用 */
    private val intSink: AudioSink = buildSink(OutputVariant.INT16, enableFloatOutput = false)

    /** 24 位变体：自行写出独占流唯一声明的 24 位整型，供前两个变体都挂不上时使用 */
    private val int24Sink: Int24PcmAudioSink = Int24PcmAudioSink(context)

    /**
     * 音频轨的接收回调：先经 [OutputEncodingListener] 截取写出编码，再透传给渲染器。
     *
     * 三个变体各持一份，编码按变体归属上报；渲染器尚未接管时出口仍为静默实现，事件不外泄但照常截取，
     * 避免起播瞬间的编码漏报。
     */
    private val floatListener: AudioSink.Listener = OutputEncodingListener(
        delegate = { delegateFor(OutputVariant.FLOAT) },
        onOutputEncodingChanged = { reportOutputEncoding(OutputVariant.FLOAT, it) },
        onOutputReleased = { reportOutputReleased(OutputVariant.FLOAT) },
    )

    private val intListener: AudioSink.Listener = OutputEncodingListener(
        delegate = { delegateFor(OutputVariant.INT16) },
        onOutputEncodingChanged = { reportOutputEncoding(OutputVariant.INT16, it) },
        onOutputReleased = { reportOutputReleased(OutputVariant.INT16) },
    )

    private val int24Listener: AudioSink.Listener = OutputEncodingListener(
        delegate = { delegateFor(OutputVariant.INT24) },
        onOutputEncodingChanged = { reportOutputEncoding(OutputVariant.INT24, it) },
        onOutputReleased = { reportOutputReleased(OutputVariant.INT24) },
    )

    /** 渲染器交给本接收器的回调出口：未接管时为静默实现 */
    private var rendererListener: AudioSink.Listener = SILENT_LISTENER

    /** 当前生效的变体；初始按浮点输出，与无独占设备时的决策一致 */
    private var activeVariant = OutputVariant.FLOAT

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

    /**
     * 各默认变体最近一次建起的音频轨。
     *
     * 延迟只能从音频轨本身取得，而默认输出把轨道建在其内部，外部唯一能截住的入口是输出提供者，
     * 故由它在创建时登记，取值则推迟到该变体的建轨回调—— 创建失败会重试，登记可能早于真正的建轨，
     * 且期间生效变体也可能已经换过。登记与取值同在播放线程，无需额外同步。
     */
    private val capturedTracks = mutableMapOf<OutputVariant, AudioTrack?>()

    init {
        floatSink.setListener(floatListener)
        intSink.setListener(intListener)
        int24Sink.setListener(int24Listener)
    }

    /**
     * 该变体的回传出口：生效方透传给渲染器，退出使用的一方改走静默实现。
     *
     * 退出方复位时会释放音频轨并回调，此时渲染器正在重配，事件不应再外泄。
     */
    private fun delegateFor(variant: OutputVariant): AudioSink.Listener =
        if (variant == activeVariant) rendererListener else SILENT_LISTENER

    /**
     * 上报音频轨的写出编码。
     *
     * 只认生效方的取值：变体切换会复位退出方，其音频轨的释放回调随之到达，而该轨已不属于当前链路，
     * 照搬会把生效方已建立的值清成空。建轨即撤销释放请求——当前链路又有音频轨了。
     */
    private fun reportOutputEncoding(variant: OutputVariant, encoding: Int?) {
        if (variant != activeVariant) return
        outputReleaseRequested = false
        onOutputEncodingChanged(encoding)
        reportAudioTrack()
    }

    /**
     * 上报当前生效链路的音频轨。
     *
     * 24 位变体自建轨道因而直接可读，两个默认变体取输出提供者登记的那一个。
     */
    private fun reportAudioTrack() {
        onAudioTrackChanged(
            when (activeVariant) {
                OutputVariant.INT24 -> int24Sink.currentAudioTrack
                else -> capturedTracks[activeVariant]
            }
        )
    }

    /**
     * 上报音频轨的释放。
     *
     * 释放事件异步投回，可能晚于后续建轨到达，那时链路已由新音频轨接管，清空会把它的编码一并抹掉，
     * 故只认「请求过释放而其间未重新建轨」的释放。轨道本体随之一并作废。
     */
    private fun reportOutputReleased(variant: OutputVariant) {
        if (variant != activeVariant) return
        if (!outputReleaseRequested) return
        outputReleaseRequested = false
        capturedTracks.remove(variant)
        onOutputEncodingChanged(null)
        onAudioTrackChanged(null)
    }

    private fun buildSink(variant: OutputVariant, enableFloatOutput: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            // 默认变体的音频轨建在输出提供者内部，只有替换它才能拿到轨道本体（延迟取自该轨）
            .setAudioOutputProvider(
                TrackCapturingOutputProvider(
                    provider = AudioTrackAudioOutputProvider.Builder(context)
                        // 缓冲按本应用的目标帧数申请：媒体3 的固定 500ms 目标会让音频轨长期驻留半秒
                        // 音频，实测延迟随之与设备无关地高出一个数量级，按源格式直出换来的收益因此被淹没
                        .setAudioTrackBufferSizeProvider(PlaybackBufferPolicy.provider)
                        // 低延迟只在系统混音路径上开启：该路径要求采样率对齐设备原生采样率、会引入
                        // 重采样，且音效处理在这条路径上不可用（与本应用的「禁用音效」诉求一致）；
                        // 独占输出（USB 位完美/格式锁定）追求按源格式直出，不应被重采样破坏，
                        // 故独占成立时不设性能模式，退回平台默认路径。
                        .setAudioTrackBuilderModifier { builder, _ ->
                            if (exclusiveTarget() == null) {
                                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                            }
                        }
                        .build(),
                    onAudioTrackCreated = { track ->
                        capturedTracks[variant] = track
                        reportTrackBuffer(variant, track)
                    },
                )
            )
            .build()

    /**
     * 当前生效链路已写入音频轨的帧数，null 表示该变体给不出这一读数。
     *
     * 只有自研的 24 位输出逐次记账音频轨实际接受的字节；两个默认变体的写入量在媒体3 的输出实现内部，
     * 外部取不到，且它可能重采样，拿转发字节数推算并不成立。故这一读数只在 24 位变体生效时有值——
     * 供信息采集算出「写入音频轨 → 发声」的全链路延迟。
     */
    fun writtenOutputFrames(): Long? =
        if (activeVariant == OutputVariant.INT24) int24Sink.writtenOutputFrames else null

    /**
     * 记录音频轨的实得缓冲容量。
     *
     * 请求量未必等于实得量：平台会把低于自身下限的申请抬回下限，能否压到 [PlaybackBufferPolicy] 的
     * 目标帧数只能在建轨后由音频轨自报的容量判定，故两者一并落盘。实测延迟与这一段驻留同级，
     * 读数对不上时这里即是判断依据。
     */
    private fun reportTrackBuffer(variant: OutputVariant, track: AudioTrack) {
        val frames = track.bufferSizeInFrames
        val sampleRate = track.sampleRate
        val bufferMs = if (sampleRate > 0) frames * MILLIS_PER_SECOND / sampleRate else 0
        CrashLogManager.logInfo(
            LOG_TAG,
            "音频轨缓冲：变体=$variant 请求=${PlaybackBufferPolicy.TARGET_FRAMES}帧 " +
                "实得=${frames}帧(${bufferMs}ms)",
        )
    }

    /** 当前生效的变体：流数据、位置查询与格式查询都只经它 */
    private fun active(): AudioSink = when (activeVariant) {
        OutputVariant.FLOAT -> floatSink
        OutputVariant.INT16 -> intSink
        OutputVariant.INT24 -> int24Sink
    }

    /**
     * 该曲目应当用哪个变体写出。
     *
     * 线性 PCM 的设备级能力无从探测（AudioTrack 经混音输出普遍接受浮点与整型，media3 也只按 API 级别
     * 判定支持），按设备分化的只有独占输出流的格式匹配，因此仅在独占已建立时决策，其余情况保持浮点。
     * 判定与独占侧共用 [selectExclusiveMixer]：选中的条目即独占侧下发的混音器属性，写出编码须与
     * 之逐字段一致——已核实，格式与偏好不符时 AudioFlinger 不会报错，而是把该轨静默混音输出，
     * 「已独占」名不副实，故两处必须取同一口径；24 位可写入性也须与独占侧同一个结论，
     * 否则两处会挑出不同条目，由 [Int24OutputSupport] 缓存后统一给出。
     *
     * [format] 是解码头输出的格式，其 pcmEncoding 已是真实线性 PCM，正是 [selectExclusiveMixer]
     * 要的那一项；不是线性 PCM（直通等）时该函数即返回 null，此处随之回落到浮点。
     */
    private fun variantFor(format: Format): OutputVariant {
        val device = exclusiveTarget() ?: return OutputVariant.FLOAT
        val attributes = selectExclusiveMixer(
            audioManager.getSupportedMixerAttributes(device),
            format.sampleRate,
            format.channelCount,
            format.pcmEncoding,
            Int24OutputSupport.isSupported(format.sampleRate, format.channelCount),
        ) ?: return OutputVariant.FLOAT
        return when (attributes.format.encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> OutputVariant.INT16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> OutputVariant.INT24
            else -> OutputVariant.FLOAT
        }
    }

    private fun forEachSink(action: (AudioSink) -> Unit) {
        action(floatSink)
        action(intSink)
        action(int24Sink)
    }

    private fun switchTo(variant: OutputVariant) {
        if (variant == activeVariant) return
        // 取向先翻转再复位退出方：其音频轨的释放回调因此被判为非生效方，既不外泄给渲染器，
        // 也不会把生效方的上报值抹掉
        val outgoing = active()
        val outgoingVariant = activeVariant
        activeVariant = variant
        outgoing.reset()
        capturedTracks.remove(outgoingVariant)
        // 进入方此刻暂无音频轨，重建发生在下一次数据写入，届时由创建回调给出真实编码；
        // 退出方的编码已不属于当前链路，先清空，避免用上一变体的取值冒充当前写出编码。
        // 轨道本体同理：退出方的轨道已随复位释放，留在手里只会让延迟读到已作废的实例
        onOutputEncodingChanged(null)
        onAudioTrackChanged(null)
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        // 先上报再挑变体：独占侧据此下发混音器属性，属性生效后 exclusiveTarget 才给出设备，
        // 变体才能按与属性同一个条目来选；音频轨在下一次数据写入时才建立，属性来得及生效
        onDecodedFormatChanged(format.sampleRate, format.channelCount, format.pcmEncoding)
        switchTo(variantFor(format))
        onOutputVariantChanged(activeVariant == OutputVariant.FLOAT)
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

    override fun getSkipSilenceEnabled(): Boolean = active().skipSilenceEnabled

    // 变速功能已移除：playback parameters 保持默认值，不接受外部调速请求
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit

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
 * 登记所建音频轨的转发输出提供者。
 *
 * 默认输出的音频轨建在其内部的输出提供者里，外部拿不到实例；延迟却只能取自该轨，故由这里捕获。
 * 转发包装是媒体3 给出的官方扩展方式，除登记外一律透传给 [AudioTrackAudioOutputProvider]，
 * 不改变任何输出行为——建轨、另建与释放仍由默认输出自行决断。
 */
@OptIn(UnstableApi::class)
private class TrackCapturingOutputProvider(
    provider: AudioOutputProvider,
    private val onAudioTrackCreated: (AudioTrack) -> Unit,
) : ForwardingAudioOutputProvider(provider) {

    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val output = super.getAudioOutput(config)
        if (output is AudioTrackAudioOutput) onAudioTrackCreated(output.audioTrack)
        return output
    }
}

// 写出变体：三者的写出编码互不相同，且都是构造期取向，故以变体身份而非布尔标记区分当前生效者
private enum class OutputVariant { FLOAT, INT16, INT24 }

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
 *
 * [AudioSink.Listener] 及其回调参数属 media3 的非稳定接口，实现该接口须显式 opt-in
 */
@OptIn(UnstableApi::class)
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
