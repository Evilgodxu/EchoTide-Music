package com.yichao.evilgodxu.data.music.playback

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

// 音频输出诊断的类名前缀
private const val LOG_TAG = "PcmAudioSink"

// 下限取不到时的兜底缓冲帧数
private const val FALLBACK_BUFFER_FRAMES = 4096

/**
 * 线性 PCM 输出：转码后写出
 * 默认输出只有浮点与 16 位，自建补 24/32 位
 * 精度经 32 位中转：24 位丢低八位
 */
@OptIn(UnstableApi::class)
internal class PcmAudioSink(
    /** 写出编码：浮点或 16/24/32 位整型 */
    private val outputEncoding: Int,
    /** 是否申请低延迟模式，仅系统混音路径有效 */
    private val preferLowLatency: () -> Boolean = { false },
) : AudioSink {

    /** 写出每样本字节数 */
    private val bytesPerOutputSample = PcmConversion.byteDepth(outputEncoding)

    private var listener: AudioSink.Listener = SILENT_LISTENER
    private var clock: Clock = Clock.DEFAULT
    private var audioAttributes = AudioAttributes.DEFAULT
    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET
    private var auxEffectInfo = AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f)
    private var preferredDevice: AudioDeviceInfo? = null
    private var volume = 1f
    private var skipSilenceEnabled = false
    /** 解码格式，写出统一为 outputEncoding */
    private var inputFormat: Format? = null
    private var outputChannelMask = AudioFormat.CHANNEL_INVALID

    // 由播放线程写、采集线程读，须 volatile
    @Volatile
    private var outputFrameSize = 0

    /** 解码每帧字节数，裁剪按输入帧计 */
    private var inputFrameSize = 0

    private var output: AudioTrackOutput? = null
    private var audioTrackConfig: AudioSink.AudioTrackConfig? = null

    /** 轨道本体，排空与延迟都取它；采集线程读 */
    @Volatile
    private var audioTrack: AudioTrack? = null

    /** 当前可读的轨道，按状态过滤 */
    val currentAudioTrack: AudioTrack?
        get() = audioTrack?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }

    /** 已交给轨道的帧数，null 为无轨道 */
    val writtenOutputFrames: Long?
        get() {
            if (audioTrack == null) return null
            val frameSize = outputFrameSize
            if (frameSize <= 0) return null
            return writtenPcmBytes / frameSize
        }

    /** 轨道缓冲字节数，欠载按字节上报 */
    private var trackBufferBytes = 0

    /** 未写完的输出余量，下次续写 */
    private var pendingOutput: ByteBuffer? = null

    /** 转换缓冲，按需增长复用 */
    private var scratch: ByteBuffer? = null

    /** 裁剪装配缓冲，按需增长复用 */
    private var assembly: ByteBuffer? = null

    /** 空写出缓冲，避免误清复用缓冲 */
    private val emptyOutput: ByteBuffer = ByteBuffer.allocateDirect(0)

    /** 起裁余量（字节），延迟样本不属于媒体时间 */
    private var trimStartBytesLeft = 0

    /** 尾裁滞留区，留够填充字节待曲末判定 */
    private var endTrimBuffer: ByteArray = EMPTY_BYTES
    private var endTrimSize = 0

    /** 尾裁是否成立，跳转后作废 */
    private var endTrimArmed = false

    /** 已交给输出的字节数，采集线程读故 volatile */
    @Volatile
    private var writtenPcmBytes = 0L

    private var handledEndOfStream = false

    /** 起停意图，重建轨道时据此接续 */
    private var playRequested = false

    /** 已上报的欠载计数 */
    private var lastReportedUnderruns = 0

    /** 媒体时间锚点：媒体时刻配写出位置，按速率外推 */
    private val checkpoints = ArrayDeque<PositionCheckpoint>()

    /** 是否需在下次写入重落锚点 */
    private var checkpointPending = true

    /** 上次接纳写入时刻，欠载回调需要 */
    private var lastAcceptedWriteMs = 0L

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun setPlayerId(playerId: androidx.media3.exoplayer.analytics.PlayerId?) = Unit

    override fun setClock(clock: Clock) {
        this.clock = clock
    }

    // 只接线性 PCM，压缩与直通不经过
    override fun supportsFormat(format: Format): Boolean =
        MimeTypes.AUDIO_RAW == format.sampleMimeType &&
            PcmConversion.isLinearPcm(format.pcmEncoding)

    override fun getFormatSupport(format: Format): Int =
        pcmFormatSupport(format.pcmEncoding)

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        inputFormat = format
        outputChannelMask = format.channelMask.takeIf { it != Format.NO_VALUE }
            ?: PcmConversion.audioTrackChannelConfig(format.channelCount)
        inputFrameSize = PcmConversion.frameSize(format.pcmEncoding, format.channelCount)
        outputFrameSize = PcmConversion.frameSize(outputEncoding, format.channelCount)
        setupTrimming(format)
        releaseOutput()
        handledEndOfStream = false
        pendingOutput = null
        writtenPcmBytes = 0
        lastReportedUnderruns = 0
        checkpointPending = true
        checkpoints.clear()
    }

    /** 按编码器延迟与填充准备裁剪，按输入帧换算字节 */
    private fun setupTrimming(format: Format) {
        trimStartBytesLeft = (format.encoderDelay.takeIf { it > 0 } ?: 0) * inputFrameSize
        val endTrimBytes = (format.encoderPadding.takeIf { it > 0 } ?: 0) * inputFrameSize
        if (endTrimBuffer.size != endTrimBytes) {
            endTrimBuffer = if (endTrimBytes > 0) ByteArray(endTrimBytes) else EMPTY_BYTES
        }
        endTrimSize = 0
        endTrimArmed = true
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val format = checkNotNull(inputFormat) { "handleBuffer 前必须先 configure" }
        if (checkpoints.isEmpty() || checkpointPending) {
            checkpointPending = false
            checkpoints.addLast(
                PositionCheckpoint(
                    mediaTimeUs = presentationTimeUs.coerceAtLeast(0),
                    outputPositionUs = framesToDurationUs(writtenPcmBytes, format.sampleRate),
                    speed = playbackParameters.speed,
                )
            )
        }
        val output = ensureOutput()
        val converted = pendingOutput ?: convertTrimmed(buffer, format)
        val accepted = try {
            output.write(converted)
        } catch (e: AudioTrackOutput.WriteException) {
            // 写入失败后该输出不可复用，交回渲染器按可恢复与否处置
            releaseOutput()
            throw createWriteException(e)
        }
        if (accepted > 0) {
            writtenPcmBytes += accepted.toLong()
            lastAcceptedWriteMs = clock.elapsedRealtime()
        }
        pendingOutput = converted.takeIf { it.hasRemaining() }
        reportUnderruns(output)
        // 余量未清空即返回 false，让 media3 再调用
        return pendingOutput == null
    }

    /** 启动输出；启动失败即作废待重建 */
    override fun play() {
        playRequested = true
        val current = output ?: return
        if (!current.start()) releaseOutput()
    }

    // 暂停须收回意图，否则重建后会自播
    override fun pause() {
        playRequested = false
        output?.pause()
    }

    // 跳转后须在新缓冲重落锚点
    override fun handleDiscontinuity() {
        checkpointPending = true
    }

    override fun flush() {
        // 冲刷即重建，部分机型冲刷不净
        releaseOutput()
        pendingOutput = null
        writtenPcmBytes = 0
        handledEndOfStream = false
        lastReportedUnderruns = 0
        checkpointPending = true
        checkpoints.clear()
        // 跳转后末段不再是文件尾，放弃尾裁照常播出
        endTrimSize = 0
        endTrimArmed = false
    }

    override fun reset() {
        flush()
        playRequested = false
    }

    override fun release() {
        releaseOutput()
        inputFormat = null
        playRequested = false
    }

    override fun playToEndOfStream() {
        if (handledEndOfStream) return
        val output = output ?: run {
            handledEndOfStream = true
            return
        }
        val format = checkNotNull(inputFormat)
        // 余量写完才算结束
        val remaining = pendingOutput ?: when {
            // 跳转过：滞留区照常播出
            !endTrimArmed -> consumeEndTrim(format)
            // 曲末：滞留区即填充，丢弃
            endTrimSize > 0 -> {
                endTrimSize = 0
                null
            }

            else -> null
        }
        if (remaining != null) {
            val accepted = try {
                output.write(remaining)
            } catch (e: AudioTrackOutput.WriteException) {
                releaseOutput()
                return
            }
            if (accepted > 0) writtenPcmBytes += accepted.toLong()
            if (remaining.hasRemaining()) {
                pendingOutput = remaining
                return
            }
            pendingOutput = null
        }
        // 尾部由系统播完，位置按时钟模拟
        output.markEndOfStream(writtenPcmBytesAsFrames())
        handledEndOfStream = true
    }

    override fun isEnded(): Boolean = output == null || (handledEndOfStream && !hasPendingData())

    /** 是否仍有已写未播数据：以设备播放头判断，防止假性缓冲 */
    override fun hasPendingData(): Boolean {
        val output = output ?: return false
        // 失效轨道播放头停住，判据会恒真，如实报空
        if (!output.isUsable) return false
        val writtenFrames = writtenPcmBytesAsFrames()
        return pendingOutput != null || writtenFrames > output.consumedFrameCount
    }

    // 轨道缓冲时长（毫秒），取不到返回未知
    private fun trackBufferSizeMs(): Long {
        val output = output ?: return C.TIME_UNSET
        if (outputFrameSize <= 0) return C.TIME_UNSET
        return PcmConversion.usToMs(
            PcmConversion.sampleCountToDurationUs(
                output.bufferSizeInFrames.toLong(),
                output.audioTrack.sampleRate,
            )
        )
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val output = output ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (checkpoints.isEmpty()) return AudioSink.CURRENT_POSITION_NOT_SET
        // 上限只钳输出位置，媒体时间含渲染器偏移
        val outputPositionUs = minOf(
            output.positionUs,
            framesToDurationUs(writtenPcmBytes, checkNotNull(inputFormat).sampleRate),
        )
        var checkpoint = checkpoints.first()
        while (checkpoints.size > 1 && outputPositionUs >= checkpoints[1].outputPositionUs) {
            checkpoints.removeFirst()
            checkpoint = checkpoints.first()
        }
        val playoutDeltaUs = outputPositionUs - checkpoint.outputPositionUs
        return checkpoint.mediaTimeUs +
            PcmConversion.mediaDurationForPlayoutDuration(playoutDeltaUs, checkpoint.speed)
    }

    // 休眠上界用轨道自报采样率
    override fun getAudioTrackBufferSizeUs(): Long {
        val output = output ?: return C.TIME_UNSET
        return framesToDurationUs(
            output.bufferSizeInFrames.toLong() * outputFrameSize,
            output.audioTrack.sampleRate,
        )
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        output?.setVolume(volume)
    }

    // 不支持静音跳过，仅回报开关
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilenceEnabled

    // 不支持变速，保持默认参数
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {
        this.audioSessionId = audioSessionId
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        this.auxEffectInfo = auxEffectInfo
        output?.let { output ->
            if (auxEffectInfo.effectId != AuxEffectInfo.NO_AUX_EFFECT_ID) {
                output.attachAuxEffect(auxEffectInfo.effectId)
                output.setAuxEffectSendLevel(auxEffectInfo.sendLevel)
            }
        }
    }

    // 首选设备须传到轨道，直出才挂得上
    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        preferredDevice = audioDeviceInfo
        output?.setPreferredDevice(audioDeviceInfo)
    }

    // 启用隧道显式拒绝，关闭为空操作
    override fun enableTunnelingV21() = throw UnsupportedOperationException("线性 PCM 输出不支持隧道")

    override fun disableTunneling() = Unit

    // 自带输出实现，忽略外部提供者
    override fun setAudioOutputProvider(audioProvider: androidx.media3.exoplayer.audio.AudioOutputProvider) = Unit

    /** 建起并按意图启动输出，失败抛初始化异常 */
    private fun ensureOutput(): AudioTrackOutput {
        val current = output
        if (current != null) {
            // 轨道失效即就地重建，否则位置停住
            if (current.isUsable) return current
            releaseOutput()
        }
        val format = checkNotNull(inputFormat) { "建输出前必须先 configure" }
        val sampleRate = format.sampleRate
        val minimumBufferSize =
            AudioTrack.getMinBufferSize(sampleRate, outputChannelMask, outputEncoding)
        // 下限为负时用兜底帧数，真正下限由轨道判定
        val bufferSize = if (minimumBufferSize > 0) {
            PlaybackBufferPolicy.trackBufferBytes(minimumBufferSize, outputFrameSize, sampleRate)
        } else {
            outputFrameSize * FALLBACK_BUFFER_FRAMES
        }
        var trackState = AudioTrack.STATE_UNINITIALIZED
        val created = try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes.platformAudioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(outputChannelMask)
                        .setEncoding(outputEncoding)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .apply {
                    if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) setSessionId(audioSessionId)
                    // 低延迟只是取向，还须速率与缓冲匹配
                    if (preferLowLatency()) {
                        setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    }
                }
                .build()
            trackState = track.state
            track
        } catch (e: RuntimeException) {
            throw AudioSink.InitializationException(
                "${bitDepthLabel(outputEncoding)}轨道建立失败",
                trackState,
                format,
                /* isRecoverable= */ false,
                e,
            )
        }
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            trackState = created.state
            created.release()
            throw AudioSink.InitializationException(
                "${bitDepthLabel(outputEncoding)}轨道未初始化",
                trackState,
                format,
                /* isRecoverable= */ false,
                /* cause= */ null,
            )
        }
        // 起播阈值取实得缓冲一半，失败沿用平台初值
        runCatching {
            created.setStartThresholdInFrames(
                PlaybackBufferPolicy.startThresholdFrames(created.bufferSizeInFrames)
            )
        }.onFailure {
            CrashLogManager.logException(LOG_TAG, "设置起播阈值失败，沿用平台初值", it)
        }
        val createdOutput = AudioTrackOutput(created, sampleRate, bufferSize)
        createdOutput.setVolume(volume)
        preferredDevice?.let(createdOutput::setPreferredDevice)
        if (auxEffectInfo.effectId != AuxEffectInfo.NO_AUX_EFFECT_ID) {
            createdOutput.attachAuxEffect(auxEffectInfo.effectId)
            createdOutput.setAuxEffectSendLevel(auxEffectInfo.sendLevel)
        }
        if (playRequested) createdOutput.start()
        output = createdOutput
        audioTrack = created
        trackBufferBytes = bufferSize
        val trackConfig = AudioSink.AudioTrackConfig(
            outputEncoding,
            sampleRate,
            outputChannelMask,
            /* tunneling= */ false,
            /* offload= */ false,
            bufferSize,
        )
        audioTrackConfig = trackConfig
        // 信息面板依赖此事件
        listener.onAudioTrackInitialized(trackConfig)
        return createdOutput
    }

    private fun releaseOutput() {
        val current = output ?: return
        current.release()
        output = null
        audioTrack = null
        audioTrackConfig?.let(listener::onAudioTrackReleased)
        audioTrackConfig = null
    }

    /** 按编码器延迟与填充裁剪并转换一块输入 */
    private fun convertTrimmed(source: ByteBuffer, format: Format): ByteBuffer {
        if (inputFrameSize <= 0) return emptyOutput
        if (trimStartBytesLeft > 0) {
            val skip = min(source.remaining(), trimStartBytesLeft)
            source.position(source.position() + skip)
            trimStartBytesLeft -= skip
            if (trimStartBytesLeft > 0) return emptyOutput
        }
        val remaining = source.remaining()
        // 可写字节数＝滞留区腾出的量
        val releaseBytes = endTrimSize + remaining - endTrimBuffer.size
        val fromEnd = releaseBytes.coerceIn(0, endTrimSize)
        val fromSource = (releaseBytes - fromEnd).coerceIn(0, remaining)
        val assemblyBytes = fromEnd + fromSource
        val assembled = if (assemblyBytes > 0) {
            val target = assemblyBuffer(assemblyBytes)
            target.put(endTrimBuffer, 0, fromEnd)
            if (fromSource > 0) {
                val chunk = source.duplicate()
                chunk.limit(source.position() + fromSource)
                target.put(chunk)
                source.position(source.position() + fromSource)
            }
            target.flip()
            target
        } else {
            emptyOutput
        }
        // 左移滞留区，再接本次输入的剩余
        val kept = endTrimSize - fromEnd
        if (kept > 0) System.arraycopy(endTrimBuffer, fromEnd, endTrimBuffer, 0, kept)
        endTrimSize = kept
        val tail = source.remaining()
        if (tail > 0) {
            source.get(endTrimBuffer, endTrimSize, tail)
            endTrimSize += tail
        }
        if (assembled === emptyOutput) return emptyOutput
        return convert(assembled, format)
    }

    /** 曲末整块交出滞留区（仅跳转后） */
    private fun consumeEndTrim(format: Format): ByteBuffer? {
        val size = endTrimSize
        if (size <= 0) return null
        val target = assemblyBuffer(size)
        target.put(endTrimBuffer, 0, size)
        target.flip()
        endTrimSize = 0
        return convert(target, format)
    }

    /** 把整块输入转成所设编码，余字节跳过 */
    private fun convert(source: ByteBuffer, format: Format): ByteBuffer {
        val bytesPerSample = PcmConversion.frameSize(format.pcmEncoding, 1)
        val output = scratchBuffer(source.remaining() / bytesPerSample * bytesPerOutputSample)
        packIntPcm(source, output, format.pcmEncoding, outputEncoding)
        output.flip()
        return output
    }

    private fun scratchBuffer(size: Int): ByteBuffer {
        val current = scratch
        if (current != null && current.capacity() >= size) {
            current.clear()
            return current
        }
        return ByteBuffer.allocateDirect(size)
            .order(ByteOrder.nativeOrder())
            .also { scratch = it }
    }

    private fun assemblyBuffer(size: Int): ByteBuffer {
        val current = assembly
        if (current != null && current.capacity() >= size) {
            current.clear()
            return current
        }
        return ByteBuffer.allocateDirect(size)
            .order(ByteOrder.nativeOrder())
            .also { assembly = it }
    }

    private fun writtenPcmBytesAsFrames(): Long =
        outputFrameSize.takeIf { it > 0 }?.let { writtenPcmBytes / it } ?: 0L

    private fun framesToDurationUs(bytes: Long, sampleRate: Int): Long {
        val frameSize = outputFrameSize.takeIf { it > 0 } ?: return 0
        if (sampleRate <= 0) return 0
        return PcmConversion.sampleCountToDurationUs(bytes / frameSize, sampleRate)
    }

    /** 欠载计数增加即上报，间隔自上次写入 */
    private fun reportUnderruns(output: AudioTrackOutput) {
        val count = runCatching { output.underrunCount }.getOrDefault(lastReportedUnderruns)
        if (count <= lastReportedUnderruns) return
        lastReportedUnderruns = count
        listener.onUnderrun(
            /* bufferSize= */ trackBufferBytes,
            /* bufferSizeMs= */ trackBufferSizeMs(),
            /* elapsedSinceLastFeedMs= */ clock.elapsedRealtime() - lastAcceptedWriteMs,
        )
    }

    private fun createWriteException(e: AudioTrackOutput.WriteException): AudioSink.WriteException =
        AudioSink.WriteException(
            e.errorCode,
            checkNotNull(inputFormat),
            /* isRecoverable= */ e.errorCode == AudioTrack.ERROR_DEAD_OBJECT,
        )

    /** 媒体时间锚点：媒体时刻配写出位置 */
    private class PositionCheckpoint(
        val mediaTimeUs: Long,
        val outputPositionUs: Long,
        val speed: Float,
    )

    private companion object {

        val EMPTY_BYTES = ByteArray(0)

        /** 默认监听器，事件不外泄 */
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

// 编码名，用于异常消息
private fun bitDepthLabel(encoding: Int): String = when (encoding) {
    C.ENCODING_PCM_FLOAT -> "浮点"
    C.ENCODING_PCM_16BIT -> "16 位整型"
    C.ENCODING_PCM_24BIT -> "24 位整型"
    C.ENCODING_PCM_32BIT -> "32 位整型"
    else -> "编码$encoding"
}

/** 浮点直出，其余转码；非 PCM 不支持 */
@OptIn(UnstableApi::class)
internal fun pcmFormatSupport(pcmEncoding: Int): Int {
    if (!PcmConversion.isLinearPcm(pcmEncoding)) return AudioSink.SINK_FORMAT_UNSUPPORTED
    return if (pcmEncoding == C.ENCODING_PCM_FLOAT) {
        AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    } else {
        AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
    }
}

/** 逐样本经 32 位整型中转写出，返回末尾不足一样本的字节数 */
internal fun packIntPcm(
    source: ByteBuffer,
    output: ByteBuffer,
    inputEncoding: Int,
    outputEncoding: Int,
): Int {
    val bytesPerSample = PcmConversion.frameSize(inputEncoding, 1)
    while (source.remaining() >= bytesPerSample) {
        PcmConversion.write32BitIntPcm(
            output,
            PcmConversion.readAs32BitIntPcm(source, inputEncoding),
            outputEncoding,
        )
    }
    val leftover = source.remaining()
    if (leftover > 0) {
        // 消费余数，避免重复读取
        source.position(source.limit())
    }
    return leftover
}
