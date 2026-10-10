package com.yichao.evilgodxu.data.music.playback

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.PcmAudioUtil
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
 *   PcmAudioUtil.rampUpVolume 取 configuration.outputConfig.encoding），声明与数据不符即越界崩溃；
 *   反过来让声明与数据一致，则音频轨的帧长记账按浮点算，与实际轨道差出一截。
 *
 * 两种位深共用本实现：它们只差每样本字节数，转换、记账、位置换算的口径完全一致，
 * 分写两套只会让同一处缺陷要修两遍。选哪一种由动态混音端口声明决定——[PerDeviceAudioSink] 按目标
 * 设备声明的条目各持一个实例。
 *
 * 本实现只做「接受解码数据 — 转成所设位深的整型帧 — 交给输出」这一段，位置、欠载、变速、音量、音效
 * 全部复用媒体3 的输出实现（[AudioTrackAudioOutput]）：先按该编码建轨，再把它交给该实现托管，
 * 因此位置与欠载判定的口径与默认输出完全一致，不必自行重造。
 *
 * 与默认输出的已知差别（均为有意取舍）：
 * - 不裁剪编码器延迟与填充：曲目间隙会比其它变体略长，但不会出现重叠或杂音；
 * - 不支持静音跳过、隧道与卸载：直出优先保真，这些能力在直出成立时不起作用；
 * - 不参与声道映射：输出声道数取解码格式本身。
 *
 * 精度：逐样本经媒体3 自身的读写约定中转。32 位整型容得下任意线性源——32 位及以下源左移补零后逐位
 * 无损，浮点源按中间态的 32 位整型取整（偏差不超过一个最低有效位）；24 位则放不下 32 位源的低八位。
 */
@OptIn(UnstableApi::class)
internal class IntPcmAudioSink(
    /** 写出编码：仅 [C.ENCODING_PCM_24BIT] 与 [C.ENCODING_PCM_32BIT] 两种打包整型，取值由动态混音端口声明 */
    private val outputEncoding: Int,
) : AudioSink {

    /** 写出每样本字节数：转换时按它申请缓冲，与位深同源 */
    private val bytesPerOutputSample = Util.getByteDepth(outputEncoding)

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

    private var output: AudioOutput? = null
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

    /** 原始播放头读数与累计基准：读数回退时以旧读数补齐基准，消费量因此保持连续 */
    private var lastRawHeadFrames = 0L
    private var rawHeadBase = 0L

    /** 重建后的首次读数即零点：轨道重建后播放头未必从 0 起算 */
    private var headNeedsBaseline = true

    /** 单调不减的已消费帧数：播放头偶发抖动时不至于让排空判定来回翻转 */
    private var consumedFrames = 0L

    /** 上一次未写完的整型数据：媒体3 会以同一输入缓冲再次调用，余量须暂存于此 */
    private var pendingOutput: ByteBuffer? = null

    /** 转换用缓冲，按需增长后复用，避免每次写入都分配直接缓冲 */
    private var scratch: ByteBuffer? = null

    /**
     * 已交给输出的字节数：媒体3 的默认输出也按写入量推算位置，此处同口径用于位置上限与待播判定。
     *
     * 信息采集另按它算出「已写入音频轨的帧数」，读取发生在别的线程，故 volatile 发布。
     */
    @Volatile
    private var writtenPcmBytes = 0L

    private var handledEndOfStream = false
    private var playing = false

    /**
     * 媒体时间锚点。
     *
     * 输出位置只按播放时长推进，与媒体时间之间隔着播放速率，故媒体3 以「媒体时刻 + 当时的写出位置」配对
     * 换算（见其 applyMediaPositionParameters）：本类同样在参数生效的那次写入处落下锚点，速率变化后再落一个，
     * 查询时取最后一个已生效的锚点按速率外推。
     *
     * 锚点的两端分属两个基准：媒体时刻取自 [handleBuffer] 的展示时间，即渲染器时间（含渲染器时间偏移）；
     * 写出位置则是输出侧自 0 起算的时长。换算只能在两端各自的基准内进行，跨基准取最小会把偏移削掉。
     */
    private val checkpoints = ArrayDeque<PositionCheckpoint>()

    /** 参数是否已变、需要在下次写入处重新落锚点 */
    private var checkpointPending = true

    /** 输入缓冲长度不成样本整数倍是否已留痕：一次会话只记一条 */
    private var alignmentReported = false

    /** 诊断留痕：欠载事件按秒收敛记录，避免日志刷成流水账 */
    private var lastAcceptedWriteMs = 0L
    private var lastUnderrunLoggedMs = 0L
    private var underrunCount = 0

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun setPlayerId(playerId: androidx.media3.exoplayer.analytics.PlayerId?) = Unit

    override fun setClock(clock: Clock) {
        this.clock = clock
    }

    // 只接线性 PCM：直出变体只被选来写出打包整型，压缩格式与直通不经此处
    override fun supportsFormat(format: Format): Boolean =
        MimeTypes.AUDIO_RAW == format.sampleMimeType && Util.isEncodingLinearPcm(format.pcmEncoding)

    override fun getFormatSupport(format: Format): Int =
        intPcmFormatSupport(format.pcmEncoding)

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        inputFormat = format
        // 可观测性：本类只拿得到解码输出格式，无从分辨「源本就是 16 位」与「高分辨率源被上游降级为
        // 16 位」——两者到这里都是 16 位输入。补零写出逐位无损，故日志只陈述这一事实，再按源分列结论，
        // 不把正常的 16 位源断言成丢精度。
        if (format.pcmEncoding == C.ENCODING_PCM_16BIT) {
            CrashLogManager.logInfo(
                LOG_TAG,
                "${bitDepthLabel(outputEncoding)}输出收到 16 位输入：左移补零写出，逐位无损；" +
                    "源为 16 位及以下即完整精度，源为高分辨率则低位系上游解码器所丢；" +
                    "sampleRate=${format.sampleRate}Hz channelCount=${format.channelCount}",
            )
        }
        outputChannelMask = format.channelMask.takeIf { it != Format.NO_VALUE }
            ?: Util.getAudioTrackChannelConfig(format.channelCount)
        outputFrameSize = Util.getPcmFrameSize(outputEncoding, format.channelCount)
        releaseOutput()
        handledEndOfStream = false
        pendingOutput = null
        writtenPcmBytes = 0
        checkpointPending = true
        checkpoints.clear()
        alignmentReported = false
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
        val before = converted.position()
        val handled = try {
            output.write(converted, encodedAccessUnitCount, presentationTimeUs)
        } catch (e: AudioOutput.WriteException) {
            // 写入失败后该输出不可复用，交回渲染器按可恢复与否处置
            releaseOutput()
            throw createWriteException(e)
        }
        val accepted = (converted.position() - before).toLong()
        if (accepted > 0) {
            writtenPcmBytes += accepted
            lastAcceptedWriteMs = clock.elapsedRealtime()
        }
        pendingOutput = converted.takeIf { it.hasRemaining() }
        // 余量未清空即未写完：返回 false 让媒体3 以同一缓冲再次调用，由本层继续消费余量
        return handled && pendingOutput == null
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
        // 与媒体3 的默认输出一致：每次冲刷都重建输出，规避部分机型 AudioTrack 冲刷不净的问题
        releaseOutput()
        pendingOutput = null
        writtenPcmBytes = 0
        handledEndOfStream = false
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
                output.write(remaining, 1, C.TIME_END_OF_SOURCE)
            } catch (e: AudioOutput.WriteException) {
                releaseOutput()
                return
            }
            if (remaining.hasRemaining()) return
            pendingOutput = null
        }
        output.stop()
        handledEndOfStream = true
    }

    override fun isEnded(): Boolean = output == null || (handledEndOfStream && !hasPendingData())

    /**
     * 是否仍有已写出而未播出的数据。
     *
     * 判据是「已写帧数 > 轨道已消费帧数」，消费量由 [consumedFrameCount] 给出。此处不能沿用媒体3 的位置
     * 估计：那个估计按系统时钟（或设备时间戳）外推，遇到粒度粗、滞后的播放头会越过已写量，于是被判成
     * 「已播完」。渲染器一旦认为输出已空、而上游此刻并不「就绪」（解码管线已满，样本队列无待读数据），
     * 那条 100ms 宽限便不生效——播放状态会在播放中与缓冲中之间来回翻转，界面上正表现为反复「缓冲中」。
     * 原始播放头只反映设备真实消费，只会滞后，不会超前。
     */
    override fun hasPendingData(): Boolean {
        val output = output ?: return false
        val writtenFrames = writtenPcmBytesAsFrames()
        val consumed = consumedFrameCount(output)
        return pendingOutput != null || writtenFrames > consumed
    }

    /**
     * 轨道已消费的帧数，取自 AudioTrack 的原始播放头。
     *
     * 播放头是设备侧消费的直接读数，粒度各机型不同（数十毫秒内），但不会像按系统时钟外推的估计值那样
     * 越过已写量，故「数据是否播尽」只能以它为准。
     *
     * 播出结束后播放头不再推进，而媒体3 在停止那一刻已被告知已写帧数、并按系统时钟模拟尾音的推进
     * （[AudioTrackAudioOutput.stop]），此时改用它的位置口径，曲末的排空判定才能正常收敛。
     */
    private fun consumedFrameCount(output: AudioOutput): Long {
        val track = audioTrack ?: return outputPositionFrames(output)
        if (handledEndOfStream) return outputPositionFrames(output)
        val raw = track.playbackHeadPosition.toLong() and UNSIGNED_INT_MASK
        if (headNeedsBaseline) {
            headNeedsBaseline = false
            lastRawHeadFrames = raw
            rawHeadBase = -raw
            consumedFrames = 0L
            return 0L
        }
        // 读数回退只可能来自回绕或平台重置：以旧读数补齐基准，消费量因此保持连续
        if (raw < lastRawHeadFrames) rawHeadBase += lastRawHeadFrames - raw
        lastRawHeadFrames = raw
        consumedFrames = maxOf(consumedFrames, raw + rawHeadBase)
        return consumedFrames
    }

    // 轨道缓冲时长（毫秒）：欠载回调按接口约定上报该值，取不到时按接口的「未知」取值
    private fun trackBufferSizeMs(): Long {
        val output = output ?: return C.TIME_UNSET
        val sampleRate = outputSampleRate(output) ?: return C.TIME_UNSET
        if (outputFrameSize <= 0) return C.TIME_UNSET
        return Util.usToMs(
            Util.sampleCountToDurationUs((trackBufferBytes / outputFrameSize).toLong(), sampleRate)
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
            output.getPositionUs(),
            framesToDurationUs(writtenPcmBytes, checkNotNull(inputFormat).sampleRate),
        )
        var checkpoint = checkpoints.first()
        while (checkpoints.size > 1 && outputPositionUs >= checkpoints[1].outputPositionUs) {
            checkpoints.removeFirst()
            checkpoint = checkpoints.first()
        }
        val playoutDeltaUs = outputPositionUs - checkpoint.outputPositionUs
        return checkpoint.mediaTimeUs +
            Util.getMediaDurationForPlayoutDuration(playoutDeltaUs, checkpoint.speed)
    }

    // 上报渲染器用于决定休眠上界：口径与媒体3 一致，采样率取轨道自报值而非解码格式值
    override fun getAudioTrackBufferSizeUs(): Long {
        val output = output ?: return C.TIME_UNSET
        return framesToDurationUs(
            output.getBufferSizeInFrames() * outputFrameSize,
            outputSampleRate(output) ?: return C.TIME_UNSET,
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
    private fun ensureOutput(): AudioOutput {
        output?.let { return it }
        val format = checkNotNull(inputFormat) { "建输出前必须先 configure" }
        val sampleRate = format.sampleRate
        val minimumBufferSize =
            AudioTrack.getMinBufferSize(sampleRate, outputChannelMask, outputEncoding)
        // 平台下限取不到（该格式未被音频策略受理）时为负值，直接参与取值会把缓冲缩到建不起轨道，
        // 故按兜底帧数申请，真正的下限仍交给 AudioTrack 在建轨时判定
        val bufferSize = if (minimumBufferSize > 0) {
            PlaybackBufferPolicy.trackBufferBytes(minimumBufferSize, outputFrameSize)
        } else {
            outputFrameSize * FALLBACK_BUFFER_FRAMES
        }
        val config = androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(outputChannelMask)
            .setEncoding(outputEncoding)
            .setBufferSize(bufferSize)
            .setAudioAttributes(audioAttributes)
            .setAudioSessionId(audioSessionId)
            .build()
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
            CrashLogManager.logException(
                LOG_TAG,
                "建立 ${bitDepthLabel(outputEncoding)}轨道失败：${sampleRate}Hz",
                e,
            )
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
            CrashLogManager.logInfo(
                LOG_TAG,
                "${bitDepthLabel(outputEncoding)}轨道未初始化成功：" +
                    "${sampleRate}Hz/${format.channelCount}ch ${bufferSize}字节",
            )
            throw AudioSink.InitializationException(
                "${bitDepthLabel(outputEncoding)}轨道未初始化",
                trackState,
                format,
                /* isRecoverable= */ false,
                /* cause= */ null,
            )
        }
        val created1 = AudioTrackAudioOutput(
            created,
            config,
            null,
            /* maxAllowedPlaybackSpeed= */ 1.0f,
            clock,
        )
        created1.addListener(outputListener)
        created1.setVolume(volume)
        preferredDevice?.let(created1::setPreferredDevice)
        if (auxEffectInfo.effectId != AuxEffectInfo.NO_AUX_EFFECT_ID) {
            created1.attachAuxEffect(auxEffectInfo.effectId)
            created1.setAuxEffectSendLevel(auxEffectInfo.sendLevel)
        }
        if (playing) created1.play()
        output = created1
        audioTrack = created
        trackBufferBytes = bufferSize
        // 轨道新建：播放头未必从 0 起算，故下一次读取重新取零点
        headNeedsBaseline = true
        consumedFrames = 0L
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
        val actualFrames = created1.getBufferSizeInFrames()
        CrashLogManager.logInfo(
            LOG_TAG,
            "${bitDepthLabel(outputEncoding)}输出已建立：${sampleRate}Hz/${format.channelCount}ch " +
                "请求缓冲=${bufferSize}字节，实际容量=${actualFrames}帧" +
                "(${actualFrames * 1000 / sampleRate}ms)，" +
                "上报渲染器休眠上界=${getAudioTrackBufferSizeUs() / 1000}ms",
        )
        return created1
    }

    private fun releaseOutput() {
        val output = output ?: return
        output.release()
        this.output = null
        audioTrack = null
        lastRawHeadFrames = 0L
        rawHeadBase = 0L
        consumedFrames = 0L
        headNeedsBaseline = true
        audioTrackConfig?.let(listener::onAudioTrackReleased)
        audioTrackConfig = null
    }

    /** 把整块输入转成所设位深的整型帧；输入被消费到末位，余量无法构成样本时留痕 */
    private fun convert(source: ByteBuffer, format: Format): ByteBuffer {
        val bytesPerSample = Util.getPcmFrameSize(format.pcmEncoding, 1)
        val output = scratchBuffer(source.remaining() / bytesPerSample * bytesPerOutputSample)
        val leftover = packIntPcm(source, output, format.pcmEncoding, outputEncoding)
        if (leftover > 0 && !alignmentReported) {
            alignmentReported = true
            CrashLogManager.logInfo(
                LOG_TAG,
                "解码缓冲长度不是样本整数倍，末尾余字节已跳过：编码=${format.pcmEncoding}",
            )
        }
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

    private fun outputPositionFrames(output: AudioOutput): Long {
        val sampleRate = outputSampleRate(output) ?: return 0
        return Util.durationUsToSampleCount(output.getPositionUs(), sampleRate)
    }

    /** 轨道自报采样率：取不到时退回解码格式的采样率 */
    private fun outputSampleRate(output: AudioOutput): Int? =
        output.getSampleRate().takeIf { it > 0 } ?: inputFormat?.sampleRate?.takeIf { it > 0 }

    private fun framesToDurationUs(bytes: Long, sampleRate: Int): Long {
        val frameSize = outputFrameSize.takeIf { it > 0 } ?: return 0
        if (sampleRate <= 0) return 0
        return Util.sampleCountToDurationUs(bytes / frameSize, sampleRate)
    }

    private fun createWriteException(e: AudioOutput.WriteException): AudioSink.WriteException =
        AudioSink.WriteException(e.errorCode, checkNotNull(inputFormat), e.isRecoverable)

    /** 把输出的欠载事件如实转给渲染器并留痕：是否欠载由媒体3 的输出实现判定，本层不另作推断 */
    private val outputListener = object : AudioOutput.Listener {
        override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
            listener.onPositionAdvancing(playoutStartSystemTimeMs)
        }

        override fun onOffloadDataRequest() = Unit

        override fun onOffloadPresentationEnded() = Unit

        override fun onUnderrun() {
            underrunCount++
            val track = audioTrack
            val nowMs = clock.elapsedRealtime()
            if (track != null && nowMs - lastUnderrunLoggedMs >= TRACE_INTERVAL_MS) {
                lastUnderrunLoggedMs = nowMs
                val writtenFrames = writtenPcmBytesAsFrames()
                CrashLogManager.logInfo(
                    LOG_TAG,
                    "输出欠载：第 ${underrunCount} 次，已写=${writtenFrames}帧 " +
                        "播放头=${track.playbackHeadPosition}帧 缓冲=${track.bufferSizeInFrames}帧，" +
                        "距上次接纳写入 ${nowMs - lastAcceptedWriteMs}ms",
                )
            }
            listener.onUnderrun(
                /* bufferSize= */ trackBufferBytes,
                /* bufferSizeMs= */ trackBufferSizeMs(),
                /* elapsedSinceLastFeedMs= */ nowMs - lastAcceptedWriteMs,
            )
        }

        override fun onReleased() = Unit
    }

    /** 媒体时间锚点：媒体时刻与当时的写出位置配对，换算时按速率外推 */
    private class PositionCheckpoint(
        val mediaTimeUs: Long,
        val outputPositionUs: Long,
        val speed: Float,
    )

    private companion object {
        /** 诊断留痕的最小间隔：按秒记录，避免把日志刷成流水账 */
        const val TRACE_INTERVAL_MS = 1000L

        /** 播放头按无符号 32 位解释：它是按帧计数的回绕值 */
        const val UNSIGNED_INT_MASK = 0xFFFFFFFFL

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

// 位深名：仅用于诊断日志；两种打包整型的取值与平台公开常量同值
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
    if (!Util.isEncodingLinearPcm(pcmEncoding)) return AudioSink.SINK_FORMAT_UNSUPPORTED
    return if (pcmEncoding == C.ENCODING_PCM_FLOAT) {
        AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    } else {
        AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
    }
}

/**
 * 把整块线性 PCM 转成 [outputEncoding] 的整型帧写入 [output]，[source] 随之被消费到末位。
 *
 * 逐样本经媒体3 自身的读写约定中转（32 位整型为中间态），而非另立一套缩放口径：24 位目标下 24 位源
 * 原样落在高三位，16 位及以下源左移补零因而逐位不失真，32 位源与浮点源会丢低位；32 位目标下
 * 32 位及以下源逐位无损，浮点源按中间态取整。
 *
 * 循环以「剩余字节够一个样本」为条件：缓冲长度不是样本整数倍时余数无法构成样本，按剩余量判断会读到
 * limit 之外并抛 BufferUnderflowException——余数在此一并跳过，由调用方留痕。
 *
 * @return 末尾无法构成样本的字节数，正常为 0。
 */
@OptIn(UnstableApi::class)
internal fun packIntPcm(
    source: ByteBuffer,
    output: ByteBuffer,
    inputEncoding: Int,
    outputEncoding: Int,
): Int {
    val bytesPerSample = Util.getPcmFrameSize(inputEncoding, 1)
    while (source.remaining() >= bytesPerSample) {
        PcmAudioUtil.write32BitIntPcm(
            output,
            PcmAudioUtil.readAs32BitIntPcm(source, inputEncoding),
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

/**
 * 打包整型输出的可用性探测。
 *
 * 平台是否受理某一位深的打包整型随机型与音频策略而变，且没有对应的能力查询接口。最小缓冲查询不能作
 * 判据——它只按编码名换算字节数，不校验该编码能否真正建起轨道；故此处实际建一次轨道再释放，以建轨
 * 结果为准。探测轨道不播放、建后即释放，不影响播放，也不改变路由。
 *
 * 成功的结论按「采样率 + 声道数 + 编码」长期缓存：判定发生在每次配置音频输出之前，每次都建轨会把
 * 这一步拖成可感的停顿。调用方都在同一条播放线程上，加锁是为免于依赖这一前提。
 */
internal object IntPcmOutputSupport {

    /** 已确认能建起轨道的档位：成功即长期成立，可长期复用 */
    private val writable = mutableSetOf<Int>()

    /** 已留痕过的失败档位：失败按需重试，但同一条结论只写一次日志 */
    private val reportedFailures = mutableSetOf<Int>()

    /**
     * 该档位能否建起轨道。
     *
     * 只长期记成功的结论，失败每次重问一次：失败可能只是当时的现场使然——设备正被别的输出占着、
     * 上一次试探留下的输出尚未释放——一次失败若被长期记住，直出就再也回不来（曲目源采样率不受支持后、
     * 受支持采样率也拿不回直出的成因）。而建不起来时不会留下任何东西，重问没有副作用，代价只是重试时
     * 多建一次轨。
     */
    @OptIn(UnstableApi::class)
    @Synchronized
    fun isSupported(sampleRate: Int, channelCount: Int, encoding: Int): Boolean {
        if (sampleRate <= 0 || channelCount <= 0) return false
        val channelMask = Util.getAudioTrackChannelConfig(channelCount)
        if (channelMask == AudioFormat.CHANNEL_INVALID) return false
        val key = (sampleRate * CHANNEL_KEY_SCALE + channelCount) * ENCODING_KEY_SCALE + encoding
        if (key in writable) return true
        if (canCreateTrack(sampleRate, channelMask, channelCount, encoding)) {
            writable += key
            return true
        }
        if (reportedFailures.add(key)) {
            // 建不起来即该编码在本机不可用：直出退回系统混音，此处留下依据，不必再靠试听排查
            CrashLogManager.logInfo(
                LOG_TAG,
                "本机无法建立 ${bitDepthLabel(encoding)}轨道，该编码写出不可用：" +
                    "${sampleRate}Hz/${channelCount}ch",
            )
        }
        return false
    }

    @OptIn(UnstableApi::class)
    private fun canCreateTrack(
        sampleRate: Int,
        channelMask: Int,
        channelCount: Int,
        encoding: Int,
    ): Boolean {
        val minimum = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        val bufferSize = maxOf(
            minimum,
            Util.getPcmFrameSize(encoding, channelCount) * PROBE_FRAME_COUNT,
        )
        return runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(encoding)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
            val initialized = track.state == AudioTrack.STATE_INITIALIZED
            track.release()
            initialized
        }.onFailure {
            CrashLogManager.logException(
                LOG_TAG,
                "建立 ${bitDepthLabel(encoding)}探测轨道抛出异常：${sampleRate}Hz/${channelCount}ch",
                it,
            )
        }.getOrDefault(false)
    }

    // 探测轨道的时长取足量即可：建轨是否成立与缓冲时长无关，取小值会被平台下限抬回下限
    private const val PROBE_FRAME_COUNT = 1024

    // 声道数的实际取值远小于 32，用它错开采样率，避免两者混成同一个键
    private const val CHANNEL_KEY_SCALE = 32

    // 再把编码错开：打包整型的取值是个位数地址段的编码号，取 64 足以容纳，不与声道数位重叠
    private const val ENCODING_KEY_SCALE = 64
}