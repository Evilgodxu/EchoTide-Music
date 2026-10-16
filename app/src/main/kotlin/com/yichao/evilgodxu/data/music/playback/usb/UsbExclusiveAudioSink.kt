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
import com.yichao.evilgodxu.data.music.playback.OutputLatencyReading
import java.nio.ByteBuffer
import java.nio.ByteOrder

// 帧数换算毫秒用的系数：与 OutputLatency 同口径
private const val MILLIS_PER_SECOND = 1_000f

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
 *   累计的实际帧数估算（见 [UsbIsoOutput.stats]）——提交量会整体偏前一个在飞时长，
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
     * 应用自持音量（线性增益，0..1），档位由 [com.yichao.evilgodxu.data.music.playback.ExclusiveVolume] 持有。
     *
     * 必须自己施加：绕开混音器即绕开 `AudioManager` 的音量，不施加这条流就永远停在满幅。独占期间系统
     * 媒体音量已不再参与（音量键也已被会话的远端音量通道接管），故这里的取值与系统音量无关。
     */
    private val appVolume: () -> Float = { 1f },
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
    /** 播放器音量：平时恒为 1，心动模式的片段淡入淡出靠它 */
    private var volume = 1f

    /** 上一块缓冲收敛到的定点增益：本块由它斜到目标值，音量改动才不落在缓冲边界上 */
    private var appliedGainQ15 = UNITY_GAIN_Q15

    /** 下一块缓冲是否直接从目标增益起步：跳转与重配之后，旧增益属于上一段声音，不该斜过去 */
    private var rampJumpNext = false

    /** 是否处于暂停：暂停期间待播量必须保留，见 [hasPendingData] */
    private var paused = false

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

    /**
     * 等时输出句柄。
     *
     * 按易变发布：它由播放线程建立与释放，而界面线程会经 [latencyReading] 读它的容量这类不变量。
     * 句柄本身（进原生层的那几次调用）只能由播放线程碰——它就是个原生对象的指针，释放之后再进原生层
     * 即 use-after-free，故 [latencyReading] 只读本层缓存的快照，不碰句柄。
     */
    @Volatile
    private var output: UsbIsoOutput? = null

    /** 已交给等时输出的帧数：位置上限与待播判定都以它为准。界面算"缓冲驻留"时也要读，故按易变发布 */
    @Volatile
    private var writtenFrames = 0L

    /**
     * 最近一次由播放线程读到的"已从环取走帧数"（原生层装入 URB 的量）。
     *
     * 界面要的"缓冲驻留"是「已写出 − 已取走」，而后者只能问原生层。把这份读数留在播放线程上取、
     * 供界面读，界面才不会为了一个数字去碰原生句柄。
     */
    @Volatile
    private var cachedSentFrames = 0L

    /**
     * 最近一次由播放线程读到的"已送出帧数"（原生层回收 URB 时累计的实际长度）。
     *
     * 与 [cachedSentFrames] 同一次 `stats()` 取回：只有同一刻的一对才能相减出在飞量，分两次取会跨过
     * 一个取走周期。待播判定与播放位置都取本值。
     */
    @Volatile
    private var cachedPlayedFrames = 0L

    /** 上一次未写完的整型数据：等时写入不保证一次接纳整块，余量须暂存于此 */
    private var pendingOutput: ByteBuffer? = null

    /** 转换用缓冲，按需增长后复用，避免每次写入都分配直接缓冲 */
    private var scratch: ByteBuffer? = null

    private var handledEndOfStream = false

    /** 曲末是否已排空：排空之后环与在飞 URB 都已送出，待播判定转为「无」 */
    private var drained = false

    // 欠载按秒收敛上报，避免把回调刷成流水账
    private var lastStatsMs = 0L
    private var lastReportedUnderruns = 0L

    /** 上一次被接纳写入的时刻：欠载回调按接口约定要给出"距上次接纳写入"的间隔 */
    private var lastFedMs = 0L

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
        cachedSentFrames = 0
        cachedPlayedFrames = 0
        checkpointPending = true
        checkpoints.clear()
        lastStatsMs = 0
        lastFedMs = 0
        lastReportedUnderruns = 0
        rampJumpNext = true

        val active = session()
        if (active == null) {
            throw AudioSink.ConfigurationException("独占会话不在（设备未接入或未授权）", format)
        }
        val selected = selectFormat(active, format)
            ?: throw AudioSink.ConfigurationException("设备未声明匹配的播放格式", format)
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
        if (accepted > 0) lastFedMs = clock.elapsedRealtime()
        pendingOutput = converted.takeIf { it.hasRemaining() }
        reportUnderruns(output)
        // 余量未清空即未写完：返回 false 让媒体3 以同一缓冲再次调用，由本层继续消费余量
        return pendingOutput == null
    }

    /**
     * 播放与暂停。
     *
     * 暂停不是空操作：它要让本层**停止把环里的数据交给设备**（见 [UsbIsoOutput.setPaused]）。若只是
     * "不再写入"了事，环里攒下的数据仍会被逐步投出去，待播量随之归零——媒体3 的 `hasPendingData` 一旦
     * 为假便判输出已空，把播放状态从 READY 打回 BUFFERING 并停掉渲染器，表现就是"一暂停就卡在缓冲中、
     * 再按播放起不来"。
     *
     * 设备侧仍持续收到静音包，时钟因此不会被放开，恢复时无需重新锁钟。
     */
    override fun play() {
        paused = false
        output?.setPaused(false)
    }

    override fun pause() {
        paused = true
        output?.setPaused(true)
    }

    // 跳转后媒体时间与已写出时长不再连续，须在新缓冲处重新落锚点，位置换算才不会沿用旧锚点
    override fun handleDiscontinuity() {
        checkpointPending = true
        rampJumpNext = true
    }

    override fun flush() {
        releaseOutput()
        pendingOutput = null
        writtenFrames = 0
        // 下一条输出是全新的原生流，它的两个计数从零起算；旧快照留着会让新流刚起步时的驻留算出负值
        // 而被压成 0，直到播放线程取回新快照为止
        cachedSentFrames = 0
        cachedPlayedFrames = 0
        handledEndOfStream = false
        drained = false
        checkpointPending = true
        checkpoints.clear()
        rampJumpNext = true
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
        // 排空是必需的：数据还在环与在飞 URB 里，不等它们送出去就切歌会把尾巴整段吃掉。
        // 排空前先恢复送流——暂停期间环只进不出，不恢复就会一直等在那里
        paused = false
        output.setPaused(false)
        output.drain()
        output.stop()
        handledEndOfStream = true
        drained = true
    }

    override fun isEnded(): Boolean = output == null || (handledEndOfStream && !hasPendingData())

    /**
     * 是否仍有已写出而未送出的数据。
     *
     * 判据取自原生层回收 URB 时累计的**真实音频**帧数，而不是本层已交给它的帧数：两者差着在飞 URB 那一整段
     * （由 `UsbIsoOutput` 的目标时长折算，两种总线速度下都取到 64ms 及以上），拿提交量当播出量会让曲末判定
     * 提前一个在飞时长。
     * 静音补出的帧一概不计入——环空时补的零不是"已播出的内容"，计入会让位置越过已写出的量。
     *
     * 暂停期间一律判为有待播数据：本层可等的缓冲就只有环这一段（不另有一层平台音频轨兜着），暂停时环
     * 只进不出，留在环里的那一段就是全部待播量，而媒体3 的位置估计在暂停期间本就应当停住（平台路径上
     * 音频轨暂停后播放头同样不再推进）。排空之后环与在飞 URB 都已送出，直接判为无。
     */
    override fun hasPendingData(): Boolean {
        val output = output ?: return false
        if (pendingOutput != null) return true
        if (drained) return false
        // 先取一次进度再判暂停：暂停期间原生层不再从环里取数据，但暂停那一刻的最后一批取走仍会落账，
        // 这次刷新让面板读到的驻留停在环的真实存量上，而不是上一个读周期里偏低的"已取走"量
        val played = progressFrames(output)
        return paused || writtenFrames > played
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val output = output ?: return AudioSink.CURRENT_POSITION_NOT_SET
        val format = inputFormat ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (checkpoints.isEmpty()) return AudioSink.CURRENT_POSITION_NOT_SET
        // 上限只作用于输出位置，不作用于换算后的媒体时间：两个量不同基准——媒体时间是渲染器时间
        // （含渲染器时间偏移，量级在 1e12 微秒），已写出时长只是输出侧的时长（量级在曲目时长内），
        // 拿后者钳前者会把渲染器时间偏移一并削掉，上报位置随之跌出时间轴
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
            Util.getMediaDurationForPlayoutDuration(playoutDeltaUs, checkpoint.speed)
    }

    // 上报渲染器用于决定休眠上界：口径与平台路径一致，取设备侧缓冲的时长
    override fun getAudioTrackBufferSizeUs(): Long {
        val output = output ?: return C.TIME_UNSET
        if (output.sampleRate <= 0) return C.TIME_UNSET
        return Util.sampleCountToDurationUs(output.bufferFrames.toLong(), output.sampleRate)
    }

    /**
     * 音量改由本层施加（见 [targetGainQ15] 与 [packToSubslot] 的增益参数），不转发给任何平台对象。
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

    /**
     * 本层输出链路的延迟读数；未建立输出时为 null。
     *
     * 这条路上没有音频轨可读，故读数只能自己算。与平台路径同口径地切成两段，但只有一段能测：
     * - **本层缓冲驻留**——还留在环形缓冲里、尚未被取走的那一段，即 [ringResidentFrames]。它是这条路上
     *   唯一可测的一段，也正是缓冲策略能调的那一段；
     * - **缓冲之后那一段**（设备传输与解码器自身的排队）在 usbfs 上没有可查询的口子，一律不产出。
     *   不接受用别的量顶替：把「测不到」显示成「很短」比空着更糟。已取走而尚未送出的在飞 URB 段同属
     *   这一档而不计入驻留——它是主机侧的传输在途量，不是本层缓冲里的存量，计入会让驻留超过缓冲容量。
     *
     * 因此全链路同样不产出——它由两段相加得出，缺一段就不是全链路。
     */
    fun latencyReading(): OutputLatencyReading? {
        val output = output ?: return null
        val sampleRate = output.sampleRate.takeIf { it > 0 } ?: return null
        val bufferFrames = output.bufferFrames.takeIf { it > 0 } ?: return null
        return OutputLatencyReading(
            trackBufferFrames = bufferFrames,
            trackBufferMs = bufferFrames * MILLIS_PER_SECOND / sampleRate,
            afterTrackMs = null,
            // 读数取播放线程留下的快照而非直接问原生层——句柄会随播放结束被释放，界面不该去碰它
            trackResidentMs = ringResidentFrames(writtenFrames, cachedSentFrames, bufferFrames) *
                MILLIS_PER_SECOND / sampleRate,
            fullChainMs = null,
        )
    }

    /**
     * 读一次原生层的进度并把两个计数留下供界面取用，返回"已由主机侧送出"的帧数。
     *
     * 必须在播放线程上调用：它要进原生层，而句柄的释放也在播放线程上——同一条线程才不会有机会读到
     * 已释放的对象。界面侧的 [latencyReading] 因此只读 [cachedSentFrames] 与 [cachedPlayedFrames]。
     */
    private fun progressFrames(output: UsbIsoOutput): Long {
        val stats = output.stats()
        cachedSentFrames = stats.framesSent
        cachedPlayedFrames = stats.framesPlayed
        return stats.framesPlayed
    }

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
        // 建流时同步当前的启停状态：首次写入可能发生在渲染器还未下发 play 的时刻
        created.setPaused(paused)
        listener.onAudioTrackInitialized(trackConfigOf(created))
        // 面板的"输出编码"取自这里：设备实际收到的是子槽排布，故报的是子槽宽度对应的编码
        onOutputEncodingChanged(encodingForSubslot(created.subslotBytes))
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
        cachedSentFrames = 0
        cachedPlayedFrames = 0
        listener.onAudioTrackReleased(trackConfigOf(output))
        onOutputEncodingChanged(null)
    }

    /**
     * 把整块输入按设备子槽打包。
     *
     * 输入被消费到末位，末尾凑不满一个样本的余字节由打包器一并跳过（见 [packToSubslot]）。
     */
    private fun convert(source: ByteBuffer, format: Format): ByteBuffer {
        val bytesPerSample = Util.getPcmFrameSize(format.pcmEncoding, 1)
        val output = scratchBuffer(source.remaining() / bytesPerSample * subslotBytes)
        val target = targetGainQ15()
        // 跳转与重配之后从目标值直接起步：此刻的旧增益属于上一段声音，斜过去只是把一个不该存在的过渡
        // 补进新段落里
        val from = if (rampJumpNext) target else appliedGainQ15
        rampJumpNext = false
        appliedGainQ15 = target
        packToSubslot(source, output, format.pcmEncoding, subslotBytes, from, target)
        output.flip()
        return output
    }

    /**
     * 本次写出应收敛到的定点增益：播放器音量与应用音量的乘积。
     *
     * 两者相乘而非取其一：播放器音量平时恒为 1，但心动模式的片段首尾靠它做淡入淡出，独占路径上那次
     * 斜坡也得照常生效。等于满幅时打包器整段缩放不参与，写出仍是逐位原样。
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
        return Util.sampleCountToDurationUs(frames, sampleRate)
    }

    private fun bufferMillis(output: UsbIsoOutput): Long =
        Util.sampleCountToDurationUs(output.bufferFrames.toLong(), output.sampleRate) / 1000

    /**
     * 按秒读一次原生层的欠载计数并如实上报，同时刷新供界面取用的进度快照。
     *
     * 等时端点上的欠载与平台路径上的欠载是同一件事（数据没跟上设备的消费），故走同一个 `onUnderrun`
     * 回调；计数只在原生层有意义——它才知道每个 URB 回收时包里到底有没有数据。
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
 * 本层环形缓冲此刻的存量（帧）。
 *
 * 口径与平台路径的"音频轨驻留"一致：只算还留在缓冲里、尚未被取走的那一段。两个方向都要收：
 *
 * - 上界按容量收。已取走的量取自播放线程留下的快照，可能比此刻的已写出量落后一个读周期，算出来会偏向
 *   高估；而缓冲容不下更多，超过容量只可能是这份滞后，按容量封顶才是此刻的真值；
 * - 下界按零收。已写出恒不落后于已取走，负值只可能来自快照与记账错位，压成 0 而不是把负数报给界面。
 */
internal fun ringResidentFrames(writtenFrames: Long, sentFrames: Long, bufferFrames: Int): Long =
    (writtenFrames - sentFrames).coerceIn(0L, bufferFrames.toLong())

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
