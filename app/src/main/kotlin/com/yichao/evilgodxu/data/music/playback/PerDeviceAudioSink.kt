package com.yichao.evilgodxu.data.music.playback

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

// 日志前缀，变体切换与配置异常写在此下
private const val LOG_TAG = "PerDeviceAudioSink"

/**
 * 按直出已受理的写出格式重建的音频输出。
 * 变体随编码切换，须重开轨，只在 configure
 * 非直出一律浮点；切换按登记的播放意图接续。
 * 倍率 >1 时内层按倍率开轨，本层插值与冲段
 */
@OptIn(UnstableApi::class)
class PerDeviceAudioSink(
    /** 已建立专用流的 USB 设备，null 为未直出 */
    private val directTarget: () -> AudioDeviceInfo?,
    /** 已受理的写出编码，null 为未直出 */
    private val directOutputEncoding: () -> Int?,
    /** 直出倍率，未直出或同率时为 1 */
    private val directOutputRateMultiple: () -> Int = { 1 },
    /** 变体建不起轨时上报，直出侧据此改挂 */
    private val onDirectOutputUnrealizable: () -> Unit = {},
    /** 变体变更回调：是否以浮点写出 */
    private val onOutputVariantChanged: (Boolean) -> Unit = {},
    /** 写出编码回调，null 为无音频轨 */
    private val onOutputEncodingChanged: (Int?) -> Unit = {},
    /** 输出速率回调，null 为无音频轨；取轨道自报值 */
    private val onOutputStreamRateChanged: (Int?) -> Unit = {},
    /** 音频轨回调，释放时 null；延迟只能从轨道取 */
    private val onAudioTrackChanged: (AudioTrack?) -> Unit = {},
    /** 解码格式回调；须在 configure 开头上报，早于建轨 */
    private val onDecodedFormatChanged: (Int, Int, Int) -> Unit = { _, _, _ -> },
) : AudioSink {

    /** 默认变体：以 32 位浮点写出，保留解码精度 */
    private val floatSink: PcmAudioSink = PcmAudioSink(
        C.ENCODING_PCM_FLOAT,
        preferLowLatency = { directTarget() == null },
    )

    /** 16 位变体，供只给整型的直出流 */
    private val intSink: PcmAudioSink = PcmAudioSink(
        C.ENCODING_PCM_16BIT,
        preferLowLatency = { directTarget() == null },
    )

    /** 24 位变体，直出流唯一的 24 位档 */
    private val int24Sink: PcmAudioSink = PcmAudioSink(C.ENCODING_PCM_24BIT)

    /** 32 位变体，供只声明 32 位的设备 */
    private val int32Sink: PcmAudioSink = PcmAudioSink(C.ENCODING_PCM_32BIT)

    /** 变体回调转接：截取写出编码再透传；四变体各一份 */
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

    private val int32Listener: AudioSink.Listener = OutputEncodingListener(
        delegate = { delegateFor(OutputVariant.INT32) },
        onOutputEncodingChanged = { reportOutputEncoding(OutputVariant.INT32, it) },
        onOutputReleased = { reportOutputReleased(OutputVariant.INT32) },
    )

    /** 渲染器回调出口，未接管时为静默实现 */
    private var rendererListener: AudioSink.Listener = SILENT_LISTENER

    /** 最近一次配置；直出曲中成立时据此重跑 */
    private var lastConfig: AudioSink.AudioSinkConfig? = null

    /** 交给内层的配置，倍率档已放大采样率 */
    private var innerConfig: AudioSink.AudioSinkConfig? = null

    /** 本次插值器，null 为按源速率写出 */
    private var upsampler: SameFamilyUpsampler? = null

    /** 已插值未写完的余量，null 为无 */
    private var pendingUpsampled: ByteBuffer? = null

    /** 插值落点，按需增长复用；同一时刻仅一块 */
    private var upsampleScratch: ByteBuffer? = null

    /** 曲末延迟段是否已冲出；只冲一次 */
    private var tailFlushed = false

    /** 音频线程 Handler，路由变更时投回重配 */
    private var playbackHandler: Handler? = null

    /** 是否已整体释放，释放后不再重配 */
    private var released = false

    /** 当前生效变体，初始浮点 */
    private var activeVariant = OutputVariant.FLOAT

    /** 播放意图：重配后按它接续，reset 不动 */
    private var playRequested = false

    /** 自建轨以来是否请求过释放，用于过滤迟到的释放事件 */
    private var outputReleaseRequested = false

    init {
        floatSink.setListener(floatListener)
        intSink.setListener(intListener)
        int24Sink.setListener(int24Listener)
        int32Sink.setListener(int32Listener)
    }

    /** 变体的回调出口，退出方走静默实现 */
    private fun delegateFor(variant: OutputVariant): AudioSink.Listener =
        if (variant == activeVariant) rendererListener else SILENT_LISTENER

    /** 上报写出编码，只认生效方；建轨即撤销释放请求 */
    private fun reportOutputEncoding(variant: OutputVariant, encoding: Int?) {
        if (variant != activeVariant) return
        outputReleaseRequested = false
        onOutputEncodingChanged(encoding)
        reportAudioTrack()
    }

    /** 上报音频轨与速率，两者同生同灭 */
    private fun reportAudioTrack() {
        val track = active().currentAudioTrack
        onAudioTrackChanged(track)
        onOutputStreamRateChanged(track?.sampleRate?.takeIf { it > 0 })
    }

    /** 上报释放；仅认请求过释放的迟到事件 */
    private fun reportOutputReleased(variant: OutputVariant) {
        if (variant != activeVariant) return
        if (!outputReleaseRequested) return
        outputReleaseRequested = false
        onOutputEncodingChanged(null)
        onAudioTrackChanged(null)
        onOutputStreamRateChanged(null)
    }

    /** 已写入音频轨帧数，null 为无音频轨 */
    fun writtenOutputFrames(): Long? = active().writtenOutputFrames

    /** 生效变体，流与位置都经它；能力另问浮点 */
    private fun active(): PcmAudioSink = when (activeVariant) {
        OutputVariant.FLOAT -> floatSink
        OutputVariant.INT16 -> intSink
        OutputVariant.INT24 -> int24Sink
        OutputVariant.INT32 -> int32Sink
    }

    /**
     * 该曲目用哪个变体写出：只按已受理编码，不另挑。
     * 线性 PCM 无设备级能力探测，非直出一律浮点。
     */
    private fun variantFor(): OutputVariant {
        if (directTarget() == null) return OutputVariant.FLOAT
        return when (directOutputEncoding()) {
            AudioFormat.ENCODING_PCM_16BIT -> OutputVariant.INT16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> OutputVariant.INT24
            AudioFormat.ENCODING_PCM_32BIT -> OutputVariant.INT32
            // 浮点变体本就以浮点写出
            else -> OutputVariant.FLOAT
        }
    }

    private fun forEachSink(action: (AudioSink) -> Unit) {
        action(floatSink)
        action(intSink)
        action(int24Sink)
        action(int32Sink)
    }

    private fun switchTo(variant: OutputVariant) {
        if (variant == activeVariant) return
        // 先翻转取向再复位退出方，其释放回调因此不外泄、不清值
        val outgoing = active()
        activeVariant = variant
        outgoing.reset()
        // 进入方暂无音频轨，先清退出方的编码与轨道
        // 重建在下一次写入时给出真实编码
        onOutputEncodingChanged(null)
        onAudioTrackChanged(null)
        // 两个变体都已被复位，按登记的播放意图补一次启动
        // 漏掉则位置停滞，10s 后以超时终止播放
        if (playRequested) active().play()
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        // 留一份最近配置，直出曲中变化时重跑
        lastConfig = audioSinkConfig
        if (playbackHandler == null) playbackHandler = Looper.myLooper()?.let(::Handler)
        // 先上报再挑变体，属性才生效
        // 轨道在下次写入时才建，属性来得及生效
        onDecodedFormatChanged(format.sampleRate, format.channelCount, format.pcmEncoding)
        switchTo(variantFor())
        onOutputVariantChanged(activeVariant == OutputVariant.FLOAT)
        // 换了流或档位：余量、冲段与插值器都属旧流，一律作废
        discardUpsampled()
        upsampler = buildUpsampler(format)
        innerConfig = scaledConfig(audioSinkConfig, upsampler?.multiple ?: 1)
        active().configure(checkNotNull(innerConfig))
    }

    /**
     * 本次插值器：倍率 >1 且解码编码插得动时才有。
     * 插不动则退回源速率，正常不会出现，此处兜底。
     */
    private fun buildUpsampler(format: Format): SameFamilyUpsampler? {
        val multiple = directOutputRateMultiple()
        if (multiple <= 1) return null
        if (!canInterpolatePcm(format.pcmEncoding)) return null
        return SameFamilyUpsampler(multiple)
    }

    /**
     * 倍率档放大采样率与延迟/填充计数。
     * 轨道须开在倍率采样率上；帧计数也须放大
     */
    private fun scaledConfig(
        config: AudioSink.AudioSinkConfig,
        multiple: Int,
    ): AudioSink.AudioSinkConfig {
        if (multiple <= 1) return config
        val format = config.format
        val scaled = format.buildUpon()
            .setSampleRate(format.sampleRate * multiple)
            .apply {
                if (format.encoderDelay != Format.NO_VALUE) {
                    setEncoderDelay(format.encoderDelay * multiple)
                }
                if (format.encoderPadding != Format.NO_VALUE) {
                    setEncoderPadding(format.encoderPadding * multiple)
                }
            }
            .build()
        return AudioSink.AudioSinkConfig.Builder(scaled)
            .setOutputChannelMapping(config.outputChannelMapping)
            .setTimeline(config.timeline)
            .setMediaPeriodId(config.mediaPeriodId)
            .build()
    }

    /** 丢弃余量与冲段状态，插值器延迟线一并清空 */
    private fun discardUpsampled() {
        pendingUpsampled = null
        tailFlushed = false
        upsampler?.reset()
    }

    /**
     * 直出路由变化后让生效变体跟上：重跑一次配置。
     * 变体未变则不动；一律投回音频线程。
     */
    fun syncRouting() {
        val handler = playbackHandler ?: return
        handler.post {
            val config = lastConfig ?: return@post
            if (released) return@post
            if (variantFor() == activeVariant) return@post
            // 自发起重配，失败自己吞掉，下次换曲会重来
            runCatching { configure(config) }.onFailure {
                CrashLogManager.logException(LOG_TAG, "直出路由变化后的重配失败", it)
            }
        }
    }

    override fun setListener(listener: AudioSink.Listener) {
        rendererListener = listener
    }

    /**
     * 能力查询按浮点变体回答，不随生效变体变化。
     * 蓄意报浮点：media3 仅在直接支持时索浮点
     */
    override fun supportsFormat(format: Format): Boolean = floatSink.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int = floatSink.getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        active().getFormatOffloadSupport(format)

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        active().getCurrentPositionUs(sourceEnded)

    override fun getAudioTrackBufferSizeUs(): Long = active().getAudioTrackBufferSizeUs()

    override fun getAudioCapabilities(): AudioCapabilities? = active().audioCapabilities

    override fun getAudioAttributes(): AudioAttributes? = active().audioAttributes

    override fun getSkipSilenceEnabled(): Boolean = active().skipSilenceEnabled

    // 不支持变速，保持默认参数
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit

    override fun isEnded(): Boolean = active().isEnded

    // 余量未写出须并进来，否则媒体3 会提前判空
    override fun hasPendingData(): Boolean =
        pendingUpsampled != null || active().hasPendingData()

    /**
     * 写出数据；建轨或写出失败都逐档降级，不报致命错误。
     * 下一档由直出重挑，用尽交回渲染器。
     * 失败后统一退回本轮缓冲起点重写，保证连续。
     */
    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val upsampler = upsampler
            ?: return writeWithFallback(buffer, presentationTimeUs, encodedAccessUnitCount)
        // 余量优先：插值只做一次，重试与续写都从余量继续
        val pending = pendingUpsampled ?: upsampleInto(buffer, upsampler).also { pendingUpsampled = it }
        val handled = writeWithFallback(pending, presentationTimeUs, encodedAccessUnitCount)
        // 降级后倍率可能变，余量作废；源缓冲原样再送
        if (pendingUpsampled !== pending) return false
        // 插值已接过整块输入，源缓冲算消费完
        buffer.position(buffer.limit())
        if (pending.hasRemaining()) return false
        pendingUpsampled = null
        return handled
    }

    /**
     * 交给生效变体写出，失败按下一档降级；返回是否写完。
     * 倍率变了则整体重配并交回源缓冲，不丢数据。
     */
    private fun writeWithFallback(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val tried = mutableSetOf<OutputVariant>()
        var cause: Exception? = null
        while (true) {
            tried += activeVariant
            val startPosition = buffer.position()
            try {
                return active().handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            } catch (e: AudioSink.InitializationException) {
                cause = e
            } catch (e: AudioSink.WriteException) {
                // 可恢复的写出错误交回渲染器，不在本层吞掉
                if (e.isRecoverable) throw e
                cause = e
            }
            buffer.position(startPosition)
            onDirectOutputUnrealizable()
            if (upsampler?.multiple != directOutputRateMultiple()) {
                pendingUpsampled = null
                runCatching { configure(checkNotNull(lastConfig)) }.onFailure {
                    CrashLogManager.logException(LOG_TAG, "倍率变更后的重配失败", it)
                }
                return false
            }
            // 重挑后才知道下一档；无档位可退即浮点（系统混音）
            val next = variantFor()
            if (next in tried) throw recoverable(checkNotNull(cause))
            // 直切下一档，不经 configure
            switchTo(next)
            onOutputVariantChanged(next == OutputVariant.FLOAT)
            runCatching { active().configure(checkNotNull(innerConfig)) }.onFailure {
                CrashLogManager.logException(LOG_TAG, "直出降级后的重配失败", it)
            }
        }
    }

    /**
     * 整块插值到复用缓冲，返回已 flip 的
     * 输入位置借还：是否算消费由调用方决定。
     */
    private fun upsampleInto(source: ByteBuffer, upsampler: SameFamilyUpsampler): ByteBuffer {
        val format = checkNotNull(lastConfig).format
        val frames = source.remaining() /
            PcmConversion.frameSize(format.pcmEncoding, format.channelCount)
        val output = scratchFor(
            upsampler.outputBytes(frames, format.channelCount, format.pcmEncoding)
        )
        source.mark()
        upsampler.process(source, format.channelCount, format.pcmEncoding, output)
        source.reset()
        output.flip()
        return output
    }

    /**
     * 冲出插值器尾段，返回已 flip 的缓冲。
     * 半带滤波有延迟，冲段按静音补输入。
     */
    private fun drainInto(upsampler: SameFamilyUpsampler): ByteBuffer {
        val format = checkNotNull(lastConfig).format
        val frameSize = PcmConversion.frameSize(format.pcmEncoding, format.channelCount)
        val output = scratchFor(upsampler.drainFrames * frameSize)
        upsampler.drain(format.channelCount, format.pcmEncoding, output)
        output.flip()
        return output
    }

    /**
     * 把尾段写进生效变体，返回是否已冲完。
     * 先写完余量再冲段，失败即放弃。
     */
    private fun flushUpsamplerTail(): Boolean {
        val upsampler = upsampler ?: return true
        if (tailFlushed && pendingUpsampled == null) return true
        val pending = pendingUpsampled
            ?: drainInto(upsampler).also {
                pendingUpsampled = it
                tailFlushed = true
            }
        val written = runCatching { writeWithFallback(pending, C.TIME_END_OF_SOURCE, 1) }
        if (written.isFailure) {
            CrashLogManager.logException(LOG_TAG, "插值尾段写出失败，放弃冲段", written.exceptionOrNull())
            pendingUpsampled = null
            return true
        }
        if (pendingUpsampled !== pending) return true
        if (pending.hasRemaining()) return false
        pendingUpsampled = null
        return written.getOrDefault(true)
    }

    /** 插值落点，按需增长复用；直接缓冲与平台字节序 */
    private fun scratchFor(bytes: Int): ByteBuffer {
        val current = upsampleScratch
        if (current != null && current.capacity() >= bytes) {
            current.clear()
            return current
        }
        return ByteBuffer.allocateDirect(bytes)
            .order(ByteOrder.nativeOrder())
            .also { upsampleScratch = it }
    }

    // 全部变体用尽：交可恢复错误让渲染器重试
    private fun recoverable(cause: Throwable): AudioSink.InitializationException =
        AudioSink.InitializationException(
            "全部输出变体均无法建轨或写出",
            AudioTrack.STATE_UNINITIALIZED,
            checkNotNull(lastConfig).format,
            /* isRecoverable= */ true,
            cause,
        )

    override fun play() {
        playRequested = true
        active().play()
    }

    override fun pause() {
        playRequested = false
        active().pause()
    }

    // 轨道异步释放，故在此登记释放请求；建轨会撤销它
    override fun flush() {
        outputReleaseRequested = true
        // 余量与冲段属旧流，冲刷后作废
        discardUpsampled()
        active().flush()
    }

    /** 收尾：先冲出延迟段再让内层收尾；未冲完不报结束 */
    override fun playToEndOfStream() {
        if (!flushUpsamplerTail()) return
        active().playToEndOfStream()
    }

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
        // reset 只作废资源，不动起停意图
        // 清掉意图会让重配后变体不启动
        outputReleaseRequested = true
        // 余量与冲段属被复位的旧流，作废
        discardUpsampled()
        forEachSink { it.reset() }
    }

    override fun release() {
        released = true
        discardUpsampled()
        upsampler = null
        forEachSink { it.release() }
    }

    private companion object {
        /** 静默出口，事件不外泄 */
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

// 写出变体：构造期取向，以变体身份区分
private enum class OutputVariant { FLOAT, INT16, INT24, INT32 }

/** 回调转接：截取建轨/释放编码，其余透传 */
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
