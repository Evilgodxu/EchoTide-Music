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

// 打包整型输出的类名前缀
private const val LOG_TAG = "IntPcmAudioSink"

// 平台下限取不到时的兜底缓冲帧数：仅用于该异常分支的建轨，与常规路径的目标帧数无关
private const val FALLBACK_BUFFER_FRAMES = 4096

/**
 * 打包整型输出：自行接受解码数据并写出设备动态混音端口声明的打包整型（[outputEncoding]，24 位或 32 位）。
 *
 * 为什么另起一个 sink（已核实，无更轻的做法）：
 * - media3 的默认音频输出只产出浮点与 16 位整型；高分辨率源走的浮点分支不挂用户处理器链，无从注入转换，
 *   而整型分支又会先把高分辨率源截成 16 位，故打包整型拿不到对应写出。
 * - 把默认输出声明的编码改写成打包整型也不行：媒体3 会用该编码解读管线缓冲（音量渐入
 *   `PcmAudioUtil.rampUpVolume` 取 configuration.outputConfig.encoding），声明与数据不符即越界崩溃；
 *   反过来让声明与数据一致，则音频轨的帧长记账按浮点算，与实际轨道差出一截。
 *
 * 两种位深共用本实现：它们只差每样本字节数，转换、记账、位置换算的口径完全一致，
 * 分写两套只会让同一处缺陷要修两遍。选哪一种由动态混音端口声明决定——[PerDeviceAudioSink] 按目标
 * 设备声明的条目各持一个实例。
 *
 * 本实现只做「接受解码数据 — 转成所设位深的整型帧 — 交给输出」这一段；位置、欠载与写入由
 * [AudioTrackOutput] 承担。位置取设备侧播放头，曲末按系统时钟模拟尾音。
 *
 * 与默认输出的已知差别（均为有意取舍）：
 * - 不裁剪编码器延迟与填充：曲目间隙会比其它变体略长，但不会出现重叠或杂音；
 * - 不支持静音跳过、隧道与卸载：直出优先保真，这些能力在直出成立时不起作用；
 * - 不参与声道映射：输出声道数取解码格式本身。
 *
 * 精度：逐样本经 [PcmConversion] 的左对齐 32 位约定中转。32 位整型容得下任意线性源——32 位及以下源
 * 左移补零后逐位无损，浮点源按中间态的 32 位整型取整（偏差不超过一个最低有效位）；24 位则放不下
 * 32 位源的低八位。
 */
@OptIn(UnstableApi::class)
internal class IntPcmAudioSink(
    /** 写出编码：仅 [C.ENCODING_PCM_24BIT] 与 [C.ENCODING_PCM_32BIT] 两种打包整型，取值由动态混音端口声明 */
    private val outputEncoding: Int,
) : AudioSink {

    /** 写出每样本字节数：转换时按它申请缓冲，与位深同源 */
    private val bytesPerOutputSample = PcmConversion.byteDepth(outputEncoding)

    private var listener: AudioSink.Listener = SILENT_LISTENER
    private var clock: Clock = Clock.DEFAULT
    private var audioAttributes = AudioAttributes.DEFAULT
    private var audioSessionId = C.AUDIO_SESSION_ID_UNSET
    private var auxEffectInfo = AuxEffectInfo(AuxEffectInfo.NO_AUX_EFFECT_ID, 0f)
    private var preferredDevice: AudioDeviceInfo? = null
    private var volume = 1f
    private var skipSilenceEnabled = false
    /** 解码输出格式：本类接受任意线性 PCM 编码，写出统一为 [outputEncoding] */
    private var inputFormat: Format? = null
    private var outputChannelMask = AudioFormat.CHANNEL_INVALID

    // 帧长与写入量成对使用（换算已写入的帧数），且一在配置期、一在写入期各自更新；
    // 两者都由播放线程写、由信息采集在别的线程读，故各自 volatile 发布
    @Volatile
    private var outputFrameSize = 0

    private var output: AudioTrackOutput? = null
    private var audioTrackConfig: AudioSink.AudioTrackConfig? = null

    /**
     * 建起的轨道本体：排空判定要直接读它的播放头，而不经媒体3 的位置估计。
     *
     * 输出延迟同样只能从它身上取（平台没有按设备查询延迟的公开接口），取值的采集器跑在后台线程，故代为 volatile。
     */
    @Volatile
    private var audioTrack: AudioTrack? = null

    /** 当前仍可读取的音频轨：释放后的实例读不出有效延迟，故按轨道自身的状态过滤 */
    val currentAudioTrack: AudioTrack?
        get() = audioTrack?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }

    /**
     * 已交给音频轨的帧数，null 表示当前没有可读的音频轨。
     *
     * 这是逐次写入累计的实测量（[writtenPcmBytes] 记的是音频轨实际接受的字节），不是按当前位置推算，
     * 故与时间戳的呈现帧位相减即得「已写入而未交出」的驻留量。写入量在冲刷与重建输出时归零，
     * 与音频轨同生同灭，两者基准因此一致。
     */
    val writtenOutputFrames: Long?
        get() {
            if (audioTrack == null) return null
            val frameSize = outputFrameSize
            if (frameSize <= 0) return null
            return writtenPcmBytes / frameSize
        }

    /** 实际请求的轨道缓冲字节数：欠载回调按接口约定以字节上报 */
    private var trackBufferBytes = 0

    /** 上一次未写完的整型数据：媒体3 会以同一输入缓冲再次调用，余量须暂存于此 */
    private var pendingOutput: ByteBuffer? = null

    /** 转换用缓冲，按需增长后复用，避免每次写入都分配直接缓冲 */
    private var scratch: ByteBuffer? = null

    /**
     * 已交给输出的字节数：位置上限与待播判定都按它推算，与写入量同源。
     *
     * 信息采集另按它算出「已写入音频轨的帧数」，读取发生在别的线程，故 volatile 发布。
     */
    @Volatile
    private var writtenPcmBytes = 0L

    private var handledEndOfStream = false
    private var playing = false

    /** 已上报过的欠载计数：每次写入后取一次设备侧读数，增加即上报一次 */
    private var lastReportedUnderruns = 0

    /**
     * 媒体时间锚点。
     *
     * 输出位置只按播放时长推进，与媒体时间之间隔着播放速率，只能以「媒体时刻 + 当时的写出位置」配对
     * 换算：本类在参数生效的那次写入处落下锚点，速率变化后再落一个，查询时取最后一个已生效的锚点按速率外推。
     *
     * 锚点的两端分属两个基准：媒体时刻取自 [handleBuffer] 的展示时间，即渲染器时间（含渲染器时间偏移）；
     * 写出位置则是输出侧自 0 起算的时长。换算只能在两端各自的基准内进行，跨基准取最小会把偏移削掉。
     */
    private val checkpoints = ArrayDeque<PositionCheckpoint>()

    /** 参数是否已变、需要在下次写入处重新落锚点 */
    private var checkpointPending = true

    /** 上次接纳写入的时刻：欠载回调按接口约定要给出「距上次接纳写入」的间隔 */
    private var lastAcceptedWriteMs = 0L

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun setPlayerId(playerId: androidx.media3.exoplayer.analytics.PlayerId?) = Unit

    override fun setClock(clock: Clock) {
        this.clock = clock
    }

    // 只接线性 PCM：直出变体只被选来写出打包整型，压缩格式与直通不经此处
    override fun supportsFormat(format: Format): Boolean =
        MimeTypes.AUDIO_RAW == format.sampleMimeType &&
            PcmConversion.isLinearPcm(format.pcmEncoding)

    override fun getFormatSupport(format: Format): Int =
        intPcmFormatSupport(format.pcmEncoding)

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        inputFormat = format
        outputChannelMask = format.channelMask.takeIf { it != Format.NO_VALUE }
            ?: PcmConversion.audioTrackChannelConfig(format.channelCount)
        outputFrameSize = PcmConversion.frameSize(outputEncoding, format.channelCount)
        releaseOutput()
        handledEndOfStream = false
        pendingOutput = null
        writtenPcmBytes = 0
        lastReportedUnderruns = 0
        checkpointPending = true
        checkpoints.clear()
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
        val converted = pendingOutput ?: convert(buffer, format)
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
        // 余量未清空即未写完：返回 false 让媒体3 以同一缓冲再次调用，由本层继续消费余量
        return pendingOutput == null
    }

    override fun play() {
        playing = true
        output?.play()
    }

    override fun pause() {
        output?.pause()
    }

    // 跳转后媒体时间与已写出时长不再连续，须在新缓冲处重新落锚点，位置换算才不会沿用旧锚点
    override fun handleDiscontinuity() {
        checkpointPending = true
    }

    override fun flush() {
        // 每次冲刷都重建输出：部分机型 AudioTrack 冲刷不净，重建比复用可靠
        releaseOutput()
        pendingOutput = null
        writtenPcmBytes = 0
        handledEndOfStream = false
        lastReportedUnderruns = 0
        checkpointPending = true
        checkpoints.clear()
    }

    override fun reset() {
        flush()
        playing = false
    }

    override fun release() {
        releaseOutput()
        inputFormat = null
        playing = false
    }

    override fun playToEndOfStream() {
        if (handledEndOfStream) return
        val output = output ?: run {
            handledEndOfStream = true
            return
        }
        // 先把本层余量写完；写不完就留待下次调用，此时不得置为已结束
        val remaining = pendingOutput
        if (remaining != null) {
            try {
                output.write(remaining)
            } catch (e: AudioTrackOutput.WriteException) {
                releaseOutput()
                return
            }
            if (remaining.hasRemaining()) return
            pendingOutput = null
        }
        // 尾部由音频系统播完；本层按停止位置与系统时钟模拟推进，排空判定才收敛
        output.markEndOfStream(writtenPcmBytesAsFrames())
        handledEndOfStream = true
    }

    override fun isEnded(): Boolean = output == null || (handledEndOfStream && !hasPendingData())

    /**
     * 是否仍有已写出而未播出的数据。
     *
     * 判据是「已写帧数 > 轨道已消费帧数」，消费量取设备侧播放头。此处不能沿用媒体3 的位置估计：
     * 那个估计按系统时钟外推，遇到粒度粗、滞后的播放头会越过已写量，于是被判成「已播完」。
     * 渲染器一旦认为输出已空、而上游此刻并不「就绪」（解码管线已满，样本队列无待读数据），
     * 那条 100ms 宽限便不生效——播放状态会在播放中与缓冲中之间来回翻转，界面上正表现为反复「缓冲中」。
     * 原始播放头只反映设备真实消费，只会滞后，不会超前。
     */
    override fun hasPendingData(): Boolean {
        val output = output ?: return false
        val writtenFrames = writtenPcmBytesAsFrames()
        return pendingOutput != null || writtenFrames > output.consumedFrameCount
    }

    // 轨道缓冲时长（毫秒）：欠载回调按接口约定上报该值，取不到时按接口的「未知」取值
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
        // 上限只作用于输出位置，不作用于换算后的媒体时间：轨道位置偶发报超前时按已写出时长取上限，
        // 使待播时长不越过已写出的内容。两个量不同基准——媒体时间是渲染器时间（含渲染器时间偏移，
        // 量级在 1e12 微秒），已写出时长只是输出侧的时长（量级在曲目时长内），
        // 拿后者钳前者会把渲染器时间偏移一并削掉，上报位置随之跌出时间轴
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

    // 上报渲染器用于决定休眠上界：采样率取轨道自报值而非解码格式值
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

    // 直出优先保真：静音跳过需要改动时长映射，本路径不支持，仅如实回报开关状态
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilenceEnabled

    // 变速功能已移除：playback parameters 保持默认值，不接受外部调速请求
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

    // 直出路由靠它钉定：播放器的首选设备必须一路传到 AudioTrack，否则直出流挂不上
    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) {
        preferredDevice = audioDeviceInfo
        output?.setPreferredDevice(audioDeviceInfo)
    }

    // 隧道启用是显式请求，本路径不支持，如实拒绝；关闭则按空操作处理（渲染器可能在不使用隧道时调用它）
    override fun enableTunnelingV21() = throw UnsupportedOperationException("打包整型输出不支持隧道")

    override fun disableTunneling() = Unit

    // 本类自带输出实现，不经媒体3 的输出提供者；空操作以免调用方误以为可替换
    override fun setAudioOutputProvider(audioProvider: androidx.media3.exoplayer.audio.AudioOutputProvider) = Unit

    /** 建起并按需启动输出；未建起时向上抛出初始化异常，由渲染器按致命错误处置 */
    private fun ensureOutput(): AudioTrackOutput {
        output?.let { return it }
        val format = checkNotNull(inputFormat) { "建输出前必须先 configure" }
        val sampleRate = format.sampleRate
        val minimumBufferSize =
            AudioTrack.getMinBufferSize(sampleRate, outputChannelMask, outputEncoding)
        // 平台下限取不到（该格式未被音频策略受理）时为负值，直接参与取值会把缓冲缩到建不起轨道，
        // 故按兜底帧数申请，真正的下限仍交给 AudioTrack 在建轨时判定
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
                .apply { if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) setSessionId(audioSessionId) }
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
        // 起播阈值收到缓冲的一半：平台没有 Builder 入口，只能建轨后写入；此刻尚未 play，写的是起播用的那一版。
        // 失败退回平台初值（等于缓冲容量），只影响起播等待，故记录后放过
        runCatching {
            created.setStartThresholdInFrames(
                PlaybackBufferPolicy.startThresholdFrames(bufferSize / outputFrameSize)
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
        if (playing) createdOutput.play()
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
        // 音频信息面板的写出编码取自这一事件，漏发会让面板显示未建立
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

    /**
     * 把整块输入转成所设位深的整型帧。
     *
     * 输入被消费到末位，末尾凑不满一个样本的余字节由转换一并跳过（见 [packIntPcm]）。
     */
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

    private fun writtenPcmBytesAsFrames(): Long =
        outputFrameSize.takeIf { it > 0 }?.let { writtenPcmBytes / it } ?: 0L

    private fun framesToDurationUs(bytes: Long, sampleRate: Int): Long {
        val frameSize = outputFrameSize.takeIf { it > 0 } ?: return 0
        if (sampleRate <= 0) return 0
        return PcmConversion.sampleCountToDurationUs(bytes / frameSize, sampleRate)
    }

    /**
     * 把设备侧欠载计数如实转给渲染器。
     *
     * 计数在音频轨自身；本层每次写入后取一次，增加即上报。间隔按接口约定取距上次接纳写入的时长。
     */
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

    /** 媒体时间锚点：媒体时刻与当时的写出位置配对，换算时按速率外推 */
    private class PositionCheckpoint(
        val mediaTimeUs: Long,
        val outputPositionUs: Long,
        val speed: Float,
    )

    private companion object {

        /** 渲染器尚未接管回调时的出口，事件不外泄 */
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

// 位深名：供初始化失败的异常消息使用；两种打包整型的取值与平台公开常量同值
private fun bitDepthLabel(encoding: Int): String = when (encoding) {
    C.ENCODING_PCM_24BIT -> "24 位整型"
    C.ENCODING_PCM_32BIT -> "32 位整型"
    else -> "编码$encoding"
}

/**
 * 打包整型 sink 的格式支持判定。
 *
 * 浮点格式返回 [AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY]，使 [MediaCodecAudioRenderer]
 * 把解码器配置为浮点输出；本 sink 接收浮点后自行转成所设位深，精度得以保留。
 * 非浮点编码返回 TRANSCODING：高分辨率源因此被解码器降级为 16 位整型，低 8 位在解码阶段即已丢失，
 * 与补零升位无关。
 */
@OptIn(UnstableApi::class)
internal fun intPcmFormatSupport(pcmEncoding: Int): Int {
    if (!PcmConversion.isLinearPcm(pcmEncoding)) return AudioSink.SINK_FORMAT_UNSUPPORTED
    return if (pcmEncoding == C.ENCODING_PCM_FLOAT) {
        AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    } else {
        AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
    }
}

/**
 * 把整块线性 PCM 转成 [outputEncoding] 的整型帧写入 [output]，[source] 随之被消费到末位。
 *
 * 逐样本经 [PcmConversion] 的左对齐 32 位约定中转（32 位整型为中间态），而非另立一套缩放口径：24 位目标下
 * 24 位源原样落在高三位，16 位及以下源左移补零因而逐位不失真，32 位源与浮点源会丢低位；32 位目标下
 * 32 位及以下源逐位无损，浮点源按中间态取整。
 *
 * 循环以「剩余字节够一个样本」为条件：缓冲长度不是样本整数倍时余数无法构成样本，按剩余量判断会读到
 * limit 之外并抛 BufferUnderflowException——余数在此一并跳过。
 *
 * @return 末尾无法构成样本的字节数，正常为 0。
 */
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
        // 余数消费掉，否则同一缓冲会反复从同一位置读出同样的余数
        source.position(source.limit())
    }
    return leftover
}
