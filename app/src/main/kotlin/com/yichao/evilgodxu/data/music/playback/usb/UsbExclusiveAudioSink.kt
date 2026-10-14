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
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

// 类名前缀：配置、建流、欠载与排空都记在此名下
private const val LOG_TAG = "UsbExclusiveSink"

// 欠载与传输错误的轮询间隔：原生层在另一条线程上计数，本层按秒取一次即可，不必每块缓冲都问
private const val STATS_INTERVAL_MS = 1000L

/**
 * USB 独占输出：把解码数据经自写的 usbfs 等时层直接交给解码器的等时端点。
 *
 * 与 [IntPcmAudioSink] 的关系是并列而非叠加——两者都在"接受解码数据、转成整型帧、写出"这一段，
 * 区别只在最后一公里：那个写出的是 `AudioTrack`（因而仍在平台的混音链路里），本类写出的是等时端点
 * （因而绕开了混音链路）。**绕开正是独占的来历**：[UsbExclusiveDac] 用
 * `claimInterface(intf, force = true)` 把接口收归本应用时，内核的音频驱动被解绑，该解码器随之从系统
 * 音频里消失，别的应用与通话都用不到它；代价是平台的混音、音效与音量不再作用于这条流，得自己做。
 *
 * 因此本类**只在独占路径下被选用**，与平台直出互斥：接口同一时刻只能有一个持有者，两者不可能同时成立。
 * 谁来选、选不上怎么办属于上层路由的职责，本类只管在已被选中的前提下把数据送对。
 *
 * 与平台路径的三处必然差别：
 * - **没有播放头可读**。等时端点只报告"这批送出去了"，没有设备侧的播出台账，故位置按原生层回收 URB 时
 *   累计的实际帧数估算（见 [UsbIsoOutput.playedFrames]）——提交量会整体偏前一个在飞时长，
 *   拿它报位置会让曲末判定提前；
 * - **音量与音效得自己做**。绕开混音器即绕开平台的音量与效果链，`AudioManager` 的音量不再作用于这条流；
 * - **曲末必须显式排空**。平台路径的尾巴由音频轨自己播完，等时路径的数据还在环与在飞 URB 里，
 *   不排空就切歌会把最后一整块直接吃掉。
 */
@OptIn(UnstableApi::class)
internal class UsbExclusiveAudioSink(
    /**
     * 独占会话：由设备接入侧在取得授权并接管后持有。
     *
     * 取值为 null 表示当前没有可用的独占设备——本类不做设备枚举与授权（那属于交互与生命周期管理），
     * 拿不到会话即在配置期判失败，由上层退回平台路径。
     */
    private val session: () -> UsbExclusiveDac?,
    /**
     * 系统媒体音量（线性增益，0..1）。
     *
     * 必须自己施加：绕开混音器即绕开 `AudioManager` 的音量，而本应用的音量由系统掌管（播放器音量恒为 1），
     * 不取过来这条流就永远停在满幅——听感上是"太响且调不动"。见 [SystemMediaVolume]。
     */
    private val systemVolume: () -> Float = { 1f },
    /**
     * 写出编码的上报出口：等时输出建立时报出设备实际收到的位深，释放时报空。
     *
     * 音频信息面板的"输出编码"一行取的正是它，而这条链路上没有 `AudioTrack` 可供平台回填，
     * 漏报会让面板显示"输出未建立"——与实际正在出声完全对不上。
     */
    private val onOutputEncodingChanged: (Int?) -> Unit = {},
) : AudioSink {

    private var listener: AudioSink.Listener = SILENT_LISTENER
    private var clock: Clock = Clock.DEFAULT
    private var audioAttributes = AudioAttributes.DEFAULT
    private var volume = 1f
    private var skipSilenceEnabled = false

    /** 解码输出格式：本类接受任意线性 PCM 编码，写出统一为设备子槽排布 */
    private var inputFormat: Format? = null

    /** 本次配置选定的设备格式；null 表示尚未选定 */
    private var plan: UsbAudioOutputFormat? = null

    /** 本次配置对应的会话：释放与就绪都落在它身上，会话本身由上层持有并归还，本类不关 */
    private var dac: UsbExclusiveDac? = null

    private var sampleRate = 0
    private var outputFrameBytes = 0
    private var subslotBytes = 0
    private var outputChannelMask = AudioFormat.CHANNEL_INVALID

    private var output: UsbIsoOutput? = null

    /** 已交给等时输出的帧数：位置上限与待播判定都以它为准 */
    private var writtenFrames = 0L

    /** 上一次未写完的整型数据：等时写入不保证一次接纳整块，余量须暂存于此 */
    private var pendingOutput: ByteBuffer? = null

    /** 转换用缓冲，按需增长后复用，避免每次写入都分配直接缓冲 */
    private var scratch: ByteBuffer? = null

    private var handledEndOfStream = false

    /** 曲末是否已排空：排空之后环与在飞 URB 都已送出，待播判定转为「无」 */
    private var drained = false

    /** 输入缓冲长度不成样本整数倍是否已留痕：一次会话只记一条 */
    private var alignmentReported = false

    /** 上次记日志的音量：按取值去重，免得每块缓冲都写一行 */
    private var lastLoggedVolume = Float.NaN

    // 诊断留痕：欠载按秒收敛记录，避免日志刷成流水账
    private var lastStatsMs = 0L
    private var lastReportedUnderruns = 0L

    /** 媒体时间锚点：媒体时刻与当时的写出位置配对，查询时按速率外推 */
    private val checkpoints = ArrayDeque<PositionCheckpoint>()

    private var checkpointPending = true

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    // 只接线性 PCM：独占变体只被选来按源格式写出，压缩格式与直通不经此处
    override fun supportsFormat(format: Format): Boolean =
        MimeTypes.AUDIO_RAW == format.sampleMimeType && Util.isEncodingLinearPcm(format.pcmEncoding)

    // 本接收器自行转换，故线性 PCM 一律按「直接支持」申报；与 [intPcmFormatSupport] 同口径
    override fun getFormatSupport(format: Format): Int =
        if (Util.isEncodingLinearPcm(format.pcmEncoding)) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_UNSUPPORTED
        }

    override fun setClock(clock: Clock) {
        this.clock = clock
    }

    /**
     * 本次格式能否由独占路径承接：会话在、且设备声明了可原样送出的格式。
     *
     * 只做判定、不产生副作用：上层路由要在 [configure] 之前据此决定走哪条路，而在错误的一侧做设备
     * 控制（改采样率、切备用设置）会把设备挪到另一条路上够不着的状态。
     */
    fun canHandle(format: Format): Boolean {
        val active = session() ?: return false
        return selectFormat(active, format) != null
    }

    /**
     * 选定设备格式并让设备就绪。
     *
     * 选择与就绪都在此处落地而非推迟到首次写入：两者都可能因设备能力而失败（未声明该采样率、
     * 子槽放不下源位深、时钟拒绝改档），而这类失败越早暴露越好——配置期失败还能由上层退回平台路径，
     * 拖到写入期就只剩一条"播到一半没声"的路。
     *
     * 抛出的是 [AudioSink.ConfigurationException] 而非 [AudioSink.InitializationException]：
     * 媒体3 的音频渲染器只在配置期捕获前者（其余一概向上抛），用错了会让本可优雅退回的失败变成崩溃。
     */
    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        inputFormat = format
        releaseOutput()
        handledEndOfStream = false
        drained = false
        pendingOutput = null
        writtenFrames = 0
        checkpointPending = true
        checkpoints.clear()
        alignmentReported = false
        lastLoggedVolume = Float.NaN
        lastReportedUnderruns = 0
        lastStatsMs = 0

        val active = session()
        if (active == null) {
            throw AudioSink.ConfigurationException("独占会话不在（设备未接入或未授权）", format)
        }
        val bitDepth = sourceBitDepthOf(format.pcmEncoding)
        val selected = selectFormat(active, format)
        if (selected == null) {
            CrashLogManager.logInfo(
                LOG_TAG,
                "设备未声明可原样送出的格式：${format.sampleRate}Hz/${format.channelCount}ch/" +
                    "${bitDepth}位；设备档位 ${describeFormats(active.formats)}",
            )
            throw AudioSink.ConfigurationException("设备未声明匹配的播放格式", format)
        }
        if (!active.prepareStream(selected, format.sampleRate)) {
            throw AudioSink.ConfigurationException("设备未接受该采样率", format)
        }
        dac = active
        plan = selected
        sampleRate = format.sampleRate
        subslotBytes = selected.subslotBytes
        outputFrameBytes = selected.frameBytes
        outputChannelMask = format.channelMask.takeIf { it != Format.NO_VALUE }
            ?: Util.getAudioTrackChannelConfig(format.channelCount)
        CrashLogManager.logInfo(
            LOG_TAG,
            "独占输出已配置：${active.deviceName} ${format.sampleRate}Hz/${format.channelCount}ch，" +
                "接口${selected.interfaceNumber}.${selected.altSetting} ${selected.subslotBytes}字节槽" +
                "（有效位深 ${selected.bitResolution} 位），输入编码 ${format.pcmEncoding}",
        )
    }

    /** 从设备声明的格式里挑出能原样送出本次解码输出的那一个；挑不出即本次不成立 */
    private fun selectFormat(active: UsbExclusiveDac, format: Format): UsbAudioOutputFormat? =
        planUsbOutput(
            sampleRate = format.sampleRate,
            channelCount = format.channelCount,
            sourceBitDepth = sourceBitDepthOf(format.pcmEncoding),
            candidates = active.formats,
        )

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
            // 流已终止（停止或设备拔出）。本层已无从继续，如实交可恢复错误：上层据此退回平台路径，
            // 而不是把整曲播放带走
            releaseOutput()
            throw AudioSink.WriteException(
                PlatformAudioTrack.ERROR_DEAD_OBJECT,
                format,
                /* isRecoverable= */ true,
            )
        }
        writtenFrames += accepted
        pendingOutput = converted.takeIf { it.hasRemaining() }
        reportUnderruns(output)
        // 余量未清空即未写完：返回 false 让媒体3 以同一缓冲再次调用，由本层继续消费余量
        return pendingOutput == null
    }

    /**
     * 播放与暂停在等时链路上是空操作：送流不随二者启停。
     *
     * 等时端点的时钟由设备自己维持，停流再起会重新锁一次时钟、听感上是一次可闻的断续；保持送流则设备
     * 始终收到连续的包，只是内容为静音——这也正是平台路径上音频轨暂停时的行为（`AudioTrack` 暂停后
     * 仍占着设备的时间轴，只是不再有数据写出）。
     */
    override fun play() = Unit

    override fun pause() = Unit

    // 跳转后媒体时间与已写出时长不再连续，须在新缓冲处重新落锚点，位置换算才不会沿用旧锚点
    override fun handleDiscontinuity() {
        checkpointPending = true
    }

    override fun flush() {
        releaseOutput()
        pendingOutput = null
        writtenFrames = 0
        handledEndOfStream = false
        drained = false
        checkpointPending = true
        checkpoints.clear()
    }

    override fun reset() {
        flush()
    }

    override fun release() {
        releaseOutput()
        // 交还零带宽：本层退出后设备不该继续占着带宽与时钟。接管本身由上层持有并归还，此处不碰
        dac?.idle()
        dac = null
        plan = null
        inputFormat = null
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
        // 排空是必需的：数据还在环与在飞 URB 里，不等它们送出去就切歌会把尾巴整段吃掉
        output.drain()
        output.stop()
        handledEndOfStream = true
        drained = true
        val stats = output.stats()
        CrashLogManager.logInfo(
            LOG_TAG,
            "曲末已排空：装入 ${stats.framesSent} 帧，送出 ${stats.framesPlayed} 帧，" +
                "欠载 ${stats.underruns} 次，传输错误 ${stats.transferErrors} 次",
        )
    }

    override fun isEnded(): Boolean = output == null || (handledEndOfStream && !hasPendingData())

    /**
     * 是否仍有已写出而未送出的数据。
     *
     * 判据取自原生层回收 URB 时累计的实际帧数，而不是本层已交给它的帧数：两者差着在飞 URB 那一整段
     * （默认 8×8 个服务间隔，高速下约 8ms），拿提交量当播出量会让曲末判定提前一个在飞时长。
     * 排空之后环与在飞 URB 都已送出，直接判为无——此时再比对只会卡在设备内部那一丁点余量上。
     */
    override fun hasPendingData(): Boolean {
        val output = output ?: return false
        if (pendingOutput != null) return true
        if (drained) return false
        return writtenFrames > output.playedFrames()
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val output = output ?: return AudioSink.CURRENT_POSITION_NOT_SET
        val format = inputFormat ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (checkpoints.isEmpty()) return AudioSink.CURRENT_POSITION_NOT_SET
        // 上限只作用于输出位置，不作用于换算后的媒体时间：两个量不同基准——媒体时间是渲染器时间
        // （含渲染器时间偏移，量级在 1e12 微秒），已写出时长只是输出侧的时长（量级在曲目时长内），
        // 拿后者钳前者会把渲染器时间偏移一并削掉，上报位置随之跌出时间轴
        val outputPositionUs = minOf(
            framesToDurationUs(output.playedFrames(), format.sampleRate),
            framesToDurationUs(writtenFrames, format.sampleRate),
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

    // 上报渲染器用于决定休眠上界：口径与平台路径一致，取设备侧缓冲的时长
    override fun getAudioTrackBufferSizeUs(): Long {
        val output = output ?: return C.TIME_UNSET
        if (output.sampleRate <= 0) return C.TIME_UNSET
        return Util.sampleCountToDurationUs(output.bufferFrames.toLong(), output.sampleRate)
    }

    /**
     * 音量改由本层施加（见 [packToSubslot] 的 volume 参数），不转发给任何平台对象。
     *
     * 真正的作用点在样本打包处，那里每次写入都读一遍本取值——绕开混音器之后，平台的音量与
     * `AudioManager` 都不再作用于这条流，软件音量是唯一还剩的手段。等于 1 时整段缩放不参与。
     */
    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    // 独占直出优先保真：静音跳过需要改动时长映射，本路径不支持，仅如实回报开关状态
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

    // 本条流不经平台的音频会话，会话号无处可用；记名接收以免调用方误以为它生效
    override fun setAudioSessionId(audioSessionId: Int) = Unit

    // 音效挂在平台的音频会上，本条流不经过它，故只记名而不施加
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit

    // 隧道启用是显式请求，本路径不支持，如实拒绝；关闭则按空操作处理
    override fun enableTunnelingV21() = throw UnsupportedOperationException("USB 独占输出不支持隧道")

    override fun disableTunneling() = Unit

    // 本类自带输出实现，不经媒体3 的输出提供者；空操作以免调用方误以为可替换
    override fun setAudioOutputProvider(audioProvider: androidx.media3.exoplayer.audio.AudioOutputProvider) = Unit

    /** 建起等时输出；未建起时向上抛出初始化异常，由渲染器按可恢复与否处置 */
    private fun ensureOutput(): UsbIsoOutput {
        output?.let { return it }
        val active = checkNotNull(dac) { "建输出前必须先 configure" }
        val selected = checkNotNull(plan) { "建输出前必须先 configure" }
        val created = UsbIsoOutput.create(active.fileDescriptor, selected, sampleRate)
            ?: throw AudioSink.InitializationException(
                "等时输出建立失败",
                PlatformAudioTrack.STATE_UNINITIALIZED,
                checkNotNull(inputFormat),
                /* isRecoverable= */ true,
                /* cause= */ null,
            )
        output = created
        listener.onAudioTrackInitialized(trackConfigOf(created))
        // 面板的"输出编码"取自这里：设备实际收到的是子槽排布，故报的是子槽宽度对应的编码
        onOutputEncodingChanged(encodingForSubslot(created.subslotBytes))
        CrashLogManager.logInfo(
            LOG_TAG,
            "等时输出已建立：${created.sampleRate}Hz/${created.channels}ch/" +
                "${created.subslotBytes}字节槽，缓冲 ${created.bufferFrames} 帧" +
                "(${bufferMillis(created)}ms)，上报渲染器休眠上界=" +
                "${getAudioTrackBufferSizeUs() / 1000}ms",
        )
        return created
    }

    /**
     * 上报音频轨配置。
     *
     * 本条链路上没有 `AudioTrack`，但音频信息面板与延迟采集都从这个回调取"当前写出的是什么"，
     * 漏发会让面板显示未建立。故按等时输出实际的位宽与容量如实上报一个等价配置。
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

    private fun releaseOutput() {
        val output = output ?: return
        // 先停后关：停会撤下在飞的 URB 并让回收线程退出，关再释放流本体
        output.stop()
        output.close()
        this.output = null
        listener.onAudioTrackReleased(trackConfigOf(output))
        onOutputEncodingChanged(null)
    }

    /** 把整块输入按设备子槽打包；输入被消费到末位，余量无法构成样本时留痕 */
    private fun convert(source: ByteBuffer, format: Format): ByteBuffer {
        val bytesPerSample = Util.getPcmFrameSize(format.pcmEncoding, 1)
        val output = scratchBuffer(source.remaining() / bytesPerSample * subslotBytes)
        val leftover = packToSubslot(source, output, format.pcmEncoding, subslotBytes, appliedVolume())
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

    /**
     * 本次写出实际施加的音量：播放器音量与系统音量的乘积。
     *
     * 两者相乘而非取其一：播放器音量平时恒为 1（本应用的音量由系统掌管），但心动模式的片段首尾靠它做
     * 淡入淡出，独占路径上那次斜坡也得照常生效。等于 1 时打包器整段缩放不参与，写出仍是逐位原样。
     */
    private fun appliedVolume(): Float {
        val gain = (volume * systemVolume()).coerceIn(0f, 1f)
        if (gain != lastLoggedVolume) {
            lastLoggedVolume = gain
            CrashLogManager.logInfo(LOG_TAG, "软件音量：${(gain * 100).toInt()}%")
        }
        return gain
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

    private fun framesToDurationUs(frames: Long, sampleRate: Int): Long {
        if (sampleRate <= 0) return 0
        return Util.sampleCountToDurationUs(frames, sampleRate)
    }

    private fun bufferMillis(output: UsbIsoOutput): Long =
        Util.sampleCountToDurationUs(output.bufferFrames.toLong(), output.sampleRate) / 1000

    /**
     * 按秒读取原生层的欠载计数并如实上报。
     *
     * 等时端点上的欠载与平台路径上的欠载是同一件事（数据没跟上设备的消费），故走同一个回调；
     * 计数只在原生层有意义——它才知道每个 URB 回收时包里到底有没有数据。
     */
    private fun reportUnderruns(output: UsbIsoOutput) {
        val nowMs = clock.elapsedRealtime()
        val elapsed = nowMs - lastStatsMs
        if (lastStatsMs != 0L && elapsed < STATS_INTERVAL_MS) return
        lastStatsMs = nowMs
        val stats = output.stats()
        if (stats.underruns != lastReportedUnderruns) {
            lastReportedUnderruns = stats.underruns
            listener.onUnderrun(
                /* bufferSize= */ output.bufferFrames * outputFrameBytes,
                /* bufferSizeMs= */ bufferMillis(output),
                /* elapsedSinceLastFeedMs= */ elapsed.coerceIn(0, STATS_INTERVAL_MS),
            )
            CrashLogManager.logInfo(
                LOG_TAG,
                "等时输出欠载：累计 ${stats.underruns} 次（装入 ${stats.framesSent} 帧，" +
                    "送出 ${stats.framesPlayed} 帧，传输错误 ${stats.transferErrors} 次），" +
                    "距上次接纳写入 ${elapsed}ms",
            )
        }
        if (stats.disconnected) {
            CrashLogManager.logInfo(LOG_TAG, "等时输出检测到设备已断开，本次输出作废")
        }
    }

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

/**
 * 解码输出的位深。
 *
 * 浮点按 24 位计而不是 32 位：解码器转浮点是拿一个精度受限的中间态承载源，取 32 会要求设备具备
 * 32 位有效位深，把大量 24 位解码器无谓地挡在门外；按 24 位判则至多拒绝 16 位的设备——那种搭配下
 * 确实会丢低位，判为不成立才是诚实的。
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

/** 设备格式的简写列表，供真机上判断"设备到底声明了什么" */
private fun describeFormats(formats: List<UsbAudioOutputFormat>): String =
    formats.joinToString(separator = " | ") { format ->
        "${format.channels}ch/${format.subslotBytes}字节槽/${format.bitResolution}位 " +
            if (format.continuousRates) "${format.minRate}..${format.maxRate}Hz"
            else format.rates.joinToString("/") + "Hz"
    }
