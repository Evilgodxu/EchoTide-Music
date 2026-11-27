package com.yichao.evilgodxu.data.music.playback.usb

import android.media.AudioFormat
import android.media.AudioTrack as PlatformAudioTrack
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
import com.yichao.evilgodxu.data.music.playback.OutputLatencyReading
import com.yichao.evilgodxu.data.music.playback.PcmConversion
import java.nio.ByteBuffer
import java.nio.ByteOrder

// 帧转毫秒系数，与 OutputLatency 同口径
private const val MILLIS_PER_SECOND = 1_000f

// 统计轮询间隔 1s，不必每块缓冲都问原生层
private const val STATS_INTERVAL_MS = 1000L

/**
 * USB 独占输出：经自写 usbfs 等时层直送。
 *
 * 与 PcmAudioSink 并列，只区别在最后一公里。
 * claimInterface 收归接口后内核驱动被解绑。
 */
@OptIn(UnstableApi::class)
internal class UsbExclusiveAudioSink(
    /**
     * 独占会话，null 表示无可用设备。
     */
    private val session: () -> UsbExclusiveDac?,
    /**
     * 应用自持线性音量 0..1。
     * 绕开混音器后须自己施加。
     */
    private val appVolume: () -> Float = { 1f },
    /** 当前曲目源位深，未知时退回解码输出位深 */
    private val sourceBitDepth: () -> Int? = { null },
    /**
     * 按曲目 id 取源位深。
     * 界面侧当前曲目会滞后，须按配置的 trackId 取。
     */
    private val trackBitDepth: (Long?) -> Int? = { null },
    /**
     * 写出编码上报：建流时报设备实际位深，释放时报空。
     * 面板"输出编码"一行取它，漏报会显示"输出未建立"。
     */
    private val onOutputEncodingChanged: (Int?) -> Unit = {},
) : AudioSink {

    private var listener: AudioSink.Listener = SILENT_LISTENER
    private var clock: Clock = Clock.DEFAULT
    private var audioAttributes = AudioAttributes.DEFAULT
    /** 播放器音量，平时恒为 1，心动模式淡入淡出用 */
    private var volume = 1f

    /** 上一块末的定点增益，本块由它斜到目标 */
    private var appliedGainQ15 = UNITY_GAIN_Q15

    /** 下一块直接从目标增益起步，跳转重配后为真 */
    private var rampJumpNext = false

    /**
     * 重建流后要渐入的样本数，配置时算定。
     */
    private var rebuildFadeSamples = 0

    /** 渐入剩余样本数，大于零即按包络渐入 */
    private var fadeInSamplesLeft = 0

    /** 渐入总样本数，决定包络斜率 */
    private var fadeInSamplesTotal = 0

    /** 是否暂停，暂停期间待播量必须保留 */
    private var paused = false

    /**
     * 曲末排空后是否转静音填充。
     * 与暂停分开，合并会误判用户暂停。
     */
    private var silentHold = false

    private var skipSilenceEnabled = false

    /** 解码输出格式，写出统一为设备子槽排布 */
    private var inputFormat: Format? = null

    /** 选定的设备格式，null 表示尚未选定 */
    private var plan: UsbAudioOutputFormat? = null

    /** 本次会话，由上层持有并归还，本类不关 */
    private var dac: UsbExclusiveDac? = null

    private var sampleRate = 0
    private var outputFrameBytes = 0
    private var subslotBytes = 0
    private var outputChannelMask = AudioFormat.CHANNEL_INVALID

    /**
     * 等时输出句柄，按易变发布。
     * 只由播放线程碰，释放后再进即 use-after-free。
     */
    @Volatile
    private var output: UsbIsoOutput? = null

    /** 已交给等时输出的帧数，位置上限与待播判定以它为准 */
    @Volatile
    private var writtenFrames = 0L

    /**
     * 播放线程读到的已从环取走帧数。
     */
    @Volatile
    private var cachedSentFrames = 0L

    /**
     * 播放线程读到的已送出帧数。
     * 与 cachedSentFrames 同一次取回
     */
    @Volatile
    private var cachedPlayedFrames = 0L

    /** 未写完的整型余量，等时写入可能只接纳一部分 */
    private var pendingOutput: ByteBuffer? = null

    /** 转换缓冲，按需增长复用，避免每次分配直接缓冲 */
    private var scratch: ByteBuffer? = null

    private var handledEndOfStream = false

    /** 曲末已排空，待播判定转为无 */
    private var drained = false

    // 欠载按秒收敛上报，避免把回调刷成流水账
    private var lastStatsMs = 0L
    private var lastReportedUnderruns = 0L

    /** 上次接纳写入时刻，欠载回调需给出间隔 */
    private var lastFedMs = 0L

    /** 媒体时间锚点，查询时按速率外推 */
    private val checkpoints = ArrayDeque<PositionCheckpoint>()

    private var checkpointPending = true

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    // 只接线性 PCM，压缩与直通不经此处
    override fun supportsFormat(format: Format): Boolean =
        MimeTypes.AUDIO_RAW == format.sampleMimeType && isDeliverableEncoding(format.pcmEncoding)

    /**
     * 能力申报与 supportsFormat 同判据。
     * 报直连会跳过转码，须与实际建流一致。
     */
    override fun getFormatSupport(format: Format): Int =
        if (isDeliverableEncoding(format.pcmEncoding)) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_UNSUPPORTED
        }

    /** 线性 PCM 且位深可判定，缺一挑不出子槽 */
    private fun isDeliverableEncoding(pcmEncoding: Int): Boolean =
        PcmConversion.isLinearPcm(pcmEncoding) && sourceBitDepthOf(pcmEncoding) != 0

    override fun setClock(clock: Clock) {
        this.clock = clock
    }

    /**
     * 本次格式能否由独占承接，只判定不产生副作用。
     */
    fun canHandle(format: Format, trackId: Long?): Boolean {
        val active = session() ?: return false
        return selectFormat(active, format, trackId) != null
    }

    /**
     * 选定设备格式并让设备就绪。
     * 失败抛 ConfigurationException
     * 档位没变就沿用现有流，避免切歌顿挫。
     * 接口入口不知曲目，位深退回当前曲目。
     */
    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) = configure(audioSinkConfig, null)

    fun configure(audioSinkConfig: AudioSink.AudioSinkConfig, trackId: Long?) {
        val format = audioSinkConfig.format
        val active = session()
            ?: throw AudioSink.ConfigurationException("独占会话不在（设备未接入或未授权）", format)
        val selected = selectFormat(active, format, trackId)
            ?: throw AudioSink.ConfigurationException("设备未声明匹配的播放格式", format)
        val channelMask = channelMaskOf(format)

        inputFormat = format
        rampJumpNext = true
        // 新的一段声音，上次配置遗留的渐入到此为止
        clearFadeIn()
        rebuildFadeSamples = fadeInSampleCount(format.sampleRate, format.channelCount)
        handledEndOfStream = false
        drained = false
        // 新数据即将写入，曲末静音到此为止，环回到正常取数
        silentHold = false
        checkpointPending = true
        // 新曲重新起算，旧锚点会推偏
        checkpoints.clear()

        if (canReuseStream(active, selected, format, channelMask)) {
            // 流与端点保留，只更新记账
            dac = active
            plan = selected
            sampleRate = format.sampleRate
            subslotBytes = selected.subslotBytes
            outputFrameBytes = selected.frameBytes
            outputChannelMask = channelMask
            return
        }

        // 换档或首配，先送完再停
        dropStream(graceful = writtenFrames > 0)
        pendingOutput = null
        writtenFrames = 0
        cachedSentFrames = 0
        cachedPlayedFrames = 0
        lastStatsMs = 0
        lastFedMs = 0
        lastReportedUnderruns = 0

        if (!active.prepareStream(selected, format.sampleRate)) {
            throw AudioSink.ConfigurationException("设备未接受该采样率", format)
        }
        dac = active
        plan = selected
        sampleRate = format.sampleRate
        subslotBytes = selected.subslotBytes
        outputFrameBytes = selected.frameBytes
        outputChannelMask = channelMask
    }

    /**
     * 现有等时流能否直接承接本次配置。
     * 流在、同设备、档位不变、采样率不变。
     */
    private fun canReuseStream(
        active: UsbExclusiveDac,
        selected: UsbAudioOutputFormat,
        format: Format,
        channelMask: Int,
    ): Boolean =
        output != null &&
            dac === active &&
            plan == selected &&
            sampleRate == format.sampleRate &&
            outputChannelMask == channelMask

    /** 源格式给了声道掩码就用，否则按声道数折算 */
    private fun channelMaskOf(format: Format): Int =
        format.channelMask.takeIf { it != Format.NO_VALUE }
            ?: PcmConversion.audioTrackChannelConfig(format.channelCount)

    /**
     * 按源位深挑设备格式，挑不出即不成立。
     * 位深优先按 trackId 取，避开界面滞后。
     */
    private fun selectFormat(
        active: UsbExclusiveDac,
        format: Format,
        trackId: Long?,
    ): UsbAudioOutputFormat? =
        planUsbOutput(
            sampleRate = format.sampleRate,
            channelCount = format.channelCount,
            sourceBitDepth = trackBitDepth(trackId)
                ?: sourceBitDepth()
                ?: sourceBitDepthOf(format.pcmEncoding),
            candidates = active.formats,
        )

    /**
     * 档位是否与本次曲目的源位深不符。
     * 正常不该为真，由 syncRouting 补一次重建。
     */
    fun planDiffers(format: Format, trackId: Long?): Boolean {
        val selected = plan ?: return false
        val active = session() ?: return false
        val next = selectFormat(active, format, trackId) ?: return false
        return next != selected
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
                    outputPositionUs = framesToDurationUs(writtenFrames, format.sampleRate),
                    speed = PlaybackParameters.DEFAULT.speed,
                )
            )
        }
        val output = ensureOutput()
        val converted = pendingOutput ?: convert(buffer, format)
        val frames = converted.remaining() / outputFrameBytes
        val accepted = output.write(converted, frames)
        if (accepted < 0) {
            // 流已终止，交可恢复错误退回平台
            releaseOutput()
            throw AudioSink.WriteException(
                PlatformAudioTrack.ERROR_DEAD_OBJECT,
                format,
                /* isRecoverable= */ true,
            )
        }
        writtenFrames += accepted
        if (accepted > 0) lastFedMs = clock.elapsedRealtime()
        pendingOutput = converted.takeIf { it.hasRemaining() }
        reportUnderruns(output)
        // 未写完即 false，让媒体3 带余量再来
        return pendingOutput == null
    }

    /**
     * 播放与暂停。
     * 暂停要让本层停止把环数据交给设备。
     * 否则环被投空，媒体3 卡在缓冲中。
     */
    override fun play() {
        paused = false
        silentHold = false
        output?.setPaused(false)
    }

    override fun pause() {
        paused = true
        output?.setPaused(true)
    }

    // 跳转后时间不连续，须重新落锚点
    override fun handleDiscontinuity() {
        checkpointPending = true
        rampJumpNext = true
        // 跳转后属另一段内容，硬起不加淡入
        clearFadeIn()
    }

    /**
     * 丢弃尚未送出的数据。
     * 换曲与跳转都走这里，但不拆流。
     * 在飞 URB 撤不回，记账须同时清零。
     */
    override fun flush() {
        output?.flushRing()
        // 冲刷后环为空，须退回取环状态
        silentHold = false
        output?.setPaused(paused)
        pendingOutput = null
        writtenFrames = 0
        cachedSentFrames = 0
        cachedPlayedFrames = 0
        handledEndOfStream = false
        drained = false
        checkpointPending = true
        checkpoints.clear()
        rampJumpNext = true
        clearFadeIn()
    }

    /**
     * 资源作废，改道与重建都走这里。
     * flush 只丢数据，reset 才拆流。
     */
    override fun reset() {
        flush()
        closeStream()
    }

    override fun release() {
        closeStream()
        dac = null
        plan = null
        inputFormat = null
    }

    /** 拆流并交还零带宽，接管由上层持有，此处不碰 */
    private fun closeStream() {
        releaseOutput()
        dac?.idle()
    }

    override fun playToEndOfStream() {
        if (handledEndOfStream) return
        val output = output ?: run {
            handledEndOfStream = true
            drained = true
            return
        }
        val remaining = pendingOutput
        if (remaining != null) {
            val frames = remaining.remaining() / outputFrameBytes
            val accepted = output.write(remaining, frames)
            if (accepted < 0) {
                handledEndOfStream = true
                drained = true
                return
            }
            writtenFrames += accepted
            if (remaining.hasRemaining()) return
            pendingOutput = null
        }
        // 排空必需，不等送完切歌会吃掉尾巴
        // 排空前先恢复送流——暂停期间环只进不出
        output.setPaused(false)
        output.drain()
        // 排空后转静音，不关流，免得切歌顿挫
        silentHold = true
        output.setPaused(true)
        handledEndOfStream = true
        drained = true
    }

    override fun isEnded(): Boolean = output == null || (handledEndOfStream && !hasPendingData())

    /**
     * 是否仍有已写出而未送出的数据。
     * 取原生层真实帧数，不取提交量。
     */
    override fun hasPendingData(): Boolean {
        val output = output ?: return false
        if (pendingOutput != null) return true
        if (drained) return false
        // 先刷进度再判暂停，读真实存量
        val played = progressFrames(output)
        return paused || writtenFrames > played
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val output = output ?: return AudioSink.CURRENT_POSITION_NOT_SET
        val format = inputFormat ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (checkpoints.isEmpty()) return AudioSink.CURRENT_POSITION_NOT_SET
        // 上限只钳输出位置，媒体时间含渲染器偏移
        val outputPositionUs = minOf(
            framesToDurationUs(progressFrames(output), format.sampleRate),
            framesToDurationUs(writtenFrames, format.sampleRate),
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

    // 休眠上界取设备侧缓冲时长，与平台路径同口径
    override fun getAudioTrackBufferSizeUs(): Long {
        val output = output ?: return C.TIME_UNSET
        if (output.sampleRate <= 0) return C.TIME_UNSET
        return PcmConversion.sampleCountToDurationUs(output.bufferFrames.toLong(), output.sampleRate)
    }

    /**
     * 音量在本层打包处施加。
     * 绕开混音器后软件音量是唯一手段。
     */
    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    // 不支持静音跳过，仅记录开关
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilenceEnabled

    // 变速已移除，保持默认参数，不接受外部调速
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    // 本流不经平台音频会话，会话号无处可用，只记名接收
    override fun setAudioSessionId(audioSessionId: Int) = Unit

    // 音效挂平台音频会话，本流不经过，只记名不施加
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit

    // 隧道本路径不支持，启用即拒绝；关闭按空操作
    override fun enableTunnelingV21() = throw UnsupportedOperationException("USB 独占输出不支持隧道")

    override fun disableTunneling() = Unit

    // 本类自带输出实现，不经媒体3 输出提供者，空操作
    override fun setAudioOutputProvider(audioProvider: androidx.media3.exoplayer.audio.AudioOutputProvider) = Unit

    /**
     * 本层输出链路的延迟读数。
     * 只测环形缓冲驻留，设备传输无口可查。
     */
    fun latencyReading(): OutputLatencyReading? {
        val output = output ?: return null
        val sampleRate = output.sampleRate.takeIf { it > 0 } ?: return null
        val bufferFrames = output.bufferFrames.takeIf { it > 0 } ?: return null
        return OutputLatencyReading(
            trackBufferFrames = bufferFrames,
            trackBufferMs = bufferFrames * MILLIS_PER_SECOND / sampleRate,
            afterTrackMs = null,
            // 读播放线程快照，界面不该碰句柄
            trackResidentMs = ringResidentFrames(writtenFrames, cachedSentFrames, bufferFrames) *
                MILLIS_PER_SECOND / sampleRate,
        )
    }

    /**
     * 读一次原生进度，留下计数供界面取用。
     * 须在播放线程调用，句柄也在该线程释放。
     */
    private fun progressFrames(output: UsbIsoOutput): Long {
        val stats = output.stats()
        cachedSentFrames = stats.framesSent
        cachedPlayedFrames = stats.framesPlayed
        return stats.framesPlayed
    }

    /**
     * 建起等时输出，失败抛可恢复异常。
     * 一次失败就重设备用设置再建一次。
     */
    private fun ensureOutput(): UsbIsoOutput {
        output?.let { return it }
        val active = checkNotNull(dac) { "建输出前必须先 configure" }
        val selected = checkNotNull(plan) { "建输出前必须先 configure" }
        val created = openOutput(active, selected)
            ?: rebuildOutput(active, selected)
            ?: throw AudioSink.InitializationException(
                "等时输出建立失败",
                PlatformAudioTrack.STATE_UNINITIALIZED,
                checkNotNull(inputFormat),
                /* isRecoverable= */ true,
                /* cause= */ null,
            )
        output = created
        // 首次写入可能早于渲染器下发 play，故同步启停
        created.setPaused(paused || silentHold)
        // 新流首个样本是电平阶跃，起播渐入数毫秒
        armFadeIn()
        listener.onAudioTrackInitialized(trackConfigOf(created))
        // 面板的输出编码取自这里，报子槽宽度对应的编码
        onOutputEncodingChanged(encodingForSubslot(created.subslotBytes))
        return created
    }

    private fun openOutput(active: UsbExclusiveDac, selected: UsbAudioOutputFormat): UsbIsoOutput? =
        UsbIsoOutput.create(active.fileDescriptor, selected, sampleRate)

    /** 重设备用设置再建一次；就绪都做不成就不再建 */
    private fun rebuildOutput(active: UsbExclusiveDac, selected: UsbAudioOutputFormat): UsbIsoOutput? =
        if (active.prepareStream(selected, sampleRate)) openOutput(active, selected) else null

    /**
     * 上报音频轨配置，面板与延迟采集靠它。
     */
    private fun trackConfigOf(output: UsbIsoOutput): AudioSink.AudioTrackConfig =
        AudioSink.AudioTrackConfig(
            encodingForSubslot(output.subslotBytes),
            output.sampleRate,
            outputChannelMask,
            /* tunneling= */ false,
            /* offload= */ false,
            output.bufferFrames * outputFrameBytes,
        )

    private fun releaseOutput() = dropStream(graceful = false)

    /**
     * 拆掉当前等时流。
     * graceful 为真时先送完环再停。
     * 只在换档或失效时走，换曲走 flush。
     */
    private fun dropStream(graceful: Boolean) {
        val current = output ?: return
        if (graceful) {
            current.setPaused(false)
            current.drain()
        }
        // 先停后关，停会撤下在飞 URB
        current.stop()
        current.close()
        output = null
        cachedSentFrames = 0
        cachedPlayedFrames = 0
        listener.onAudioTrackReleased(trackConfigOf(current))
        onOutputEncodingChanged(null)
    }

    /**
     * 按设备子槽打包整块，末尾余字节跳过。
     * 增益优先级：重建渐入、硬起、音量斜坡。
     */
    private fun convert(source: ByteBuffer, format: Format): ByteBuffer {
        val bytesPerSample = PcmConversion.frameSize(format.pcmEncoding, 1)
        val output = scratchBuffer(source.remaining() / bytesPerSample * subslotBytes)
        val target = targetGainQ15()
        val samples = source.remaining() / bytesPerSample
        val from: Int
        val to: Int
        var rampSamples = RAMP_WHOLE_BLOCK
        if (fadeInSamplesLeft > 0 && samples > 0) {
            // 起播渐入只有几毫秒，通常一块内走完
            val span = minOf(samples, fadeInSamplesLeft)
            val consumed = fadeInSamplesTotal - fadeInSamplesLeft
            from = fadeGainQ15(consumed, fadeInSamplesTotal, target)
            to = fadeGainQ15(consumed + span, fadeInSamplesTotal, target)
            fadeInSamplesLeft -= span
            rampSamples = span
            appliedGainQ15 = to
            rampJumpNext = false
        } else if (rampJumpNext) {
            // 跳转重配后从目标值硬起，旧增益属于上一段声音
            from = target
            to = target
            rampJumpNext = false
            appliedGainQ15 = target
        } else {
            from = appliedGainQ15
            to = target
            appliedGainQ15 = target
        }
        packToSubslot(source, output, format.pcmEncoding, subslotBytes, from, to, rampSamples)
        output.flip()
        return output
    }

    /** 挂上渐入，样本数按本次配置算定 */
    private fun armFadeIn() {
        fadeInSamplesTotal = rebuildFadeSamples
        fadeInSamplesLeft = rebuildFadeSamples
    }

    private fun clearFadeIn() {
        fadeInSamplesLeft = 0
        fadeInSamplesTotal = 0
    }

    /**
     * 定点增益 = 播放器音量 × 应用音量。
     * 满幅时打包器不缩放。
     */
    private fun targetGainQ15(): Int = volumeToGainQ15((volume * appVolume()).coerceIn(0f, 1f))

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

    private fun framesToDurationUs(frames: Long, sampleRate: Int): Long {
        if (sampleRate <= 0) return 0
        return PcmConversion.sampleCountToDurationUs(frames, sampleRate)
    }

    private fun bufferMillis(output: UsbIsoOutput): Long =
        PcmConversion.sampleCountToDurationUs(output.bufferFrames.toLong(), output.sampleRate) / 1000

    /**
     * 按秒读欠载计数并上报，同时刷新进度快照。
     * 与平台路径走同一个 onUnderrun。
     */
    private fun reportUnderruns(output: UsbIsoOutput) {
        val nowMs = clock.elapsedRealtime()
        if (lastStatsMs != 0L && nowMs - lastStatsMs < STATS_INTERVAL_MS) return
        lastStatsMs = nowMs
        val stats = output.stats()
        cachedSentFrames = stats.framesSent
        cachedPlayedFrames = stats.framesPlayed

        if (stats.underruns != lastReportedUnderruns) {
            lastReportedUnderruns = stats.underruns
            listener.onUnderrun(
                /* bufferSize= */ output.bufferFrames * outputFrameBytes,
                /* bufferSizeMs= */ bufferMillis(output),
                /* elapsedSinceLastFeedMs= */ (nowMs - lastFedMs).coerceAtLeast(0),
            )
        }
    }

    /** 媒体时间锚点，换算时按速率外推 */
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

/**
 * 本层环形缓冲此刻的存量（帧）。
 * 上下界按容量与零封顶封底。
 */
internal fun ringResidentFrames(writtenFrames: Long, sentFrames: Long, bufferFrames: Int): Long =
    (writtenFrames - sentFrames).coerceIn(0L, bufferFrames.toLong())

/**
 * 重建流后起播渐入 5ms，掩蔽换档电平阶跃。
 * 再长会削掉曲首瞬态，跳转不渐入。
 */
private const val REBUILD_FADE_IN_MS = 5

/** 渐入样本数 = 帧数 × 声道数，参数不成立为 0 */
internal fun fadeInSampleCount(sampleRate: Int, channels: Int): Int {
    if (sampleRate <= 0 || channels <= 0) return 0
    return (sampleRate.toLong() * REBUILD_FADE_IN_MS / 1000L * channels).toInt().coerceAtLeast(0)
}

/**
 * 渐入包络走过 consumedSamples 时的定点增益。
 * 按已消耗量取值，跨块不累积取整误差。
 */
internal fun fadeGainQ15(consumedSamples: Int, totalSamples: Int, targetQ15: Int): Int {
    if (totalSamples <= 0) return targetQ15
    val consumed = consumedSamples.coerceIn(0, totalSamples)
    return (targetQ15.toLong() * consumed / totalSamples).toInt()
}

/**
 * 解码输出位深，仅在源位深未知时兜底。
 * 浮点按 24 位计：取 32 会挡掉大量 24 位解码器。
 */
internal fun sourceBitDepthOf(pcmEncoding: Int): Int = when (pcmEncoding) {
    C.ENCODING_PCM_8BIT -> 8
    C.ENCODING_PCM_16BIT -> 16
    C.ENCODING_PCM_24BIT -> 24
    C.ENCODING_PCM_32BIT -> 32
    C.ENCODING_PCM_FLOAT -> 24
    else -> 0
}

/** 子槽宽度对应的平台编码，供音频信息面板展示 */
private fun encodingForSubslot(subslotBytes: Int): Int = when (subslotBytes) {
    1 -> C.ENCODING_PCM_8BIT
    2 -> C.ENCODING_PCM_16BIT
    3 -> C.ENCODING_PCM_24BIT
    else -> C.ENCODING_PCM_32BIT
}
