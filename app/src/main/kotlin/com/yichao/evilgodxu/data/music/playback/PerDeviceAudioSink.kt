package com.yichao.evilgodxu.data.music.playback

import android.content.Context
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
import androidx.media3.common.util.Util
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
import java.nio.ByteOrder

// 音频输出诊断的类名前缀：变体切换与配置异常写在此名下
private const val LOG_TAG = "PerDeviceAudioSink"

/**
 * 按直出已受理的写出格式重建的音频输出。
 *
 * 写出编码是 [DefaultAudioSink] 的构造期取向，实例内不可更改：浮点变体以 32 位浮点写出，
 * 整型变体以 16 位整型写出，24 位与 32 位两个变体则由 [IntPcmAudioSink] 各自写出对应位深的
 * 打包整型——设备只声明该编码时，媒体3 的默认输出无从产出它，只能另起一个输出实现。而 USB 直出建立的
 * 专用输出流只接纳与混音器属性逐字段一致的播放，该属性由直出侧按源格式优先挑选并得平台受理，因此这里
 * 持有四个变体，在每次 [configure] 时按已受理的属性编码决策，决策变化即切换变体——即按该格式重建输出，
 * 使已受理的条目成为当前写出格式。
 *
 * 平台与 media3 都不对线性 PCM 做设备级能力探测，故非直出时一律保持浮点输出；
 * 决策只在 [configure] 处落地——切换需要重开 AudioTrack，只能发生在渲染器重配点。
 * 变体切换不需要回放历史配置：所有设置类调用同时下发到四个变体。播放与暂停不属设置类——退出使用的变体
 * 在切换时被复位，其播放状态随之清零，故切换点按登记的播放意图给进入方单独接续（见 [switchTo]）。
 *
 * 写出率是第二个维度，与编码同出于已受理的那条属性：设备只声明同族倍率（如 88.2kHz）而不声明源采样率
 * （44.1kHz）时直出挑中的是倍率档，音频轨必须开在该倍率上才挂得上专用输出流，而数据得由本层按整数倍
 * 补齐。[SameFamilyUpsampler] 承担插值，本层负责按倍率放大内层配置（见 [scaledConfig]）、在写出前插值
 * 并把余量握在手里（见 [handleBuffer]）、曲末冲出延迟段（见 [playToEndOfStream]）。倍率为 1 时整条
 * 路径不参与，行为与从前一致。
 */
@OptIn(UnstableApi::class)
class PerDeviceAudioSink(
    private val context: Context,
    /** 已建立专用输出流的 USB 输出设备，null 表示当前未直出 */
    private val directTarget: () -> AudioDeviceInfo?,
    /** 已受理的直出写出编码，null 表示当前未直出；写出变体据此与已受理的混音器属性逐字段对齐 */
    private val directOutputEncoding: () -> Int?,
    /** 直出写出率相对源采样率的倍率（未直出或与源同率时为 1）：内层按它开轨，本层按它插值 */
    private val directOutputRateMultiple: () -> Int = { 1 },
    /** 生效变体建不起音频轨时的报告出口：直出侧据此撤销该格式的偏好并记为不可用 */
    private val onDirectOutputUnrealizable: () -> Unit = {},
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
     * 直出据此下发混音器属性，且必须在音频轨建立前生效，故在 [configure] 的开头上报——
     * 这里是全链路最早拿到解码输出格式的地方：容器格式（轨道回调）只给采样率与声道，
     * 压缩源的 PCM 编码要等解码头出格式才知道。
     */
    private val onDecodedFormatChanged: (Int, Int, Int) -> Unit = { _, _, _ -> },
) : AudioSink {

    /** 默认变体：以 32 位浮点写出，保留解码精度 */
    private val floatSink: AudioSink = buildSink(OutputVariant.FLOAT, enableFloatOutput = true)

    /** 降级变体：一律以 16 位整型写出，供直出流只提供整型格式的设备使用 */
    private val intSink: AudioSink = buildSink(OutputVariant.INT16, enableFloatOutput = false)

    /** 24 位变体：自行写出直出流唯一声明的 24 位整型，供前两个变体都挂不上时使用 */
    private val int24Sink: IntPcmAudioSink = IntPcmAudioSink(C.ENCODING_PCM_24BIT)

    /** 32 位变体：同理，供动态混音端口只声明 32 位整型的设备（部分 USB 耳放/解码器）使用 */
    private val int32Sink: IntPcmAudioSink = IntPcmAudioSink(C.ENCODING_PCM_32BIT)

    /**
     * 音频轨的接收回调：先经 [OutputEncodingListener] 截取写出编码，再透传给渲染器。
     *
     * 四个变体各持一份，编码按变体归属上报；渲染器尚未接管时出口仍为静默实现，事件不外泄但照常截取，
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

    private val int32Listener: AudioSink.Listener = OutputEncodingListener(
        delegate = { delegateFor(OutputVariant.INT32) },
        onOutputEncodingChanged = { reportOutputEncoding(OutputVariant.INT32, it) },
        onOutputReleased = { reportOutputReleased(OutputVariant.INT32) },
    )

    /** 渲染器交给本接收器的回调出口：未接管时为静默实现 */
    private var rendererListener: AudioSink.Listener = SILENT_LISTENER

    /**
     * 渲染器最近一次下发的配置。
     *
     * 直出路由可能在曲中才成立（开关打开、设备接入、平台重新报告端口），此时必须重跑一次配置才能
     * 切到对应的写出变体，故留一份备用；变体决策只看它带的解码格式，与配置的其余内容无关。
     */
    private var lastConfig: AudioSink.AudioSinkConfig? = null

    /**
     * 交给内层 sink 的配置：倍率档下采样率与延迟计数已按倍率放大，其余与渲染器下发的一致。
     *
     * 单独存一份而不是每次由 [lastConfig] 现算：降级换变体时要按同一份配置重建音频轨
     * （见 [writeWithFallback]），现算会把这段时间里倍率的任何变化掺进来，写出率随之与已受理的属性脱节。
     */
    private var innerConfig: AudioSink.AudioSinkConfig? = null

    /** 本次配置的插值器，null 表示按源采样率写出（未直出，或直出档位与源同率） */
    private var upsampler: SameFamilyUpsampler? = null

    /**
     * 已插值而未写完的输出，null 表示本层不持余量。
     *
     * 非空即说明渲染器那一次缓冲尚未全写完：源缓冲已由本层接过（位置推到末位），余量留着续写，
     * 返回 false 让媒体3 以同一缓冲再次调用（见 [handleBuffer]）。
     */
    private var pendingUpsampled: ByteBuffer? = null

    /**
     * 插值落点：按需增长后复用。
     *
     * 同一时刻只有一块：余量未清空时不会再插值，而曲末冲段只发生在余量已清空之后
     * （见 [handleBuffer] 与 [playToEndOfStream]）。
     */
    private var upsampleScratch: ByteBuffer? = null

    /** 曲末延迟段是否已冲出：冲出只做一次，此后若被再次调用，写的只是尚未接纳的余量 */
    private var tailFlushed = false

    /**
     * 音频线程的 Handler：直出路由从别处变更时，重配只能投回音频线程执行。
     *
     * 在音频线程上首次 [configure] 时捕获——构造发生在服务创建期（主线程），此处拿不到音频线程的 Looper。
     */
    private var playbackHandler: Handler? = null

    /** 是否已整体释放：释放后投递进来的重配不得再执行，此时音频轨与输出都已作废 */
    private var released = false

    /** 当前生效的变体；初始按浮点输出，与无直出设备时的决策一致 */
    private var activeVariant = OutputVariant.FLOAT

    /**
     * 渲染器最后一次下发的播放意图：为真表示正在播放，为假表示已暂停。
     *
     * 渲染器只在「停止 → 启动」的转换点上调用 [play]，换曲重配（[configure]）发生在播放中时不会再有
     * 第二次下发；而变体切换会复位退出使用的变体，其播放状态随之清零，新生效的变体因此无从得知当前
     * 是否该播。故意图在此登记，切换时按它接续。
     *
     * 只由 [play] / [pause] 改写：[reset] 是资源操作，不代表渲染器停了播（见 [reset]）。
     */
    private var playRequested = false

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
        int32Sink.setListener(int32Listener)
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
     * 两个打包整型变体自建轨道因而直接可读，两个默认变体取输出提供者登记的那一个。
     */
    private fun reportAudioTrack() {
        onAudioTrackChanged(
            when (activeVariant) {
                OutputVariant.INT24 -> int24Sink.currentAudioTrack
                OutputVariant.INT32 -> int32Sink.currentAudioTrack
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
                        // 直出（源格式档）追求按源格式输出，不应被重采样破坏，
                        // 故直出成立时不设性能模式，退回平台默认路径。
                        .setAudioTrackBuilderModifier { builder, _ ->
                            if (directTarget() == null) {
                                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                            }
                        }
                        .build(),
                    onAudioTrackCreated = { track ->
                        capturedTracks[variant] = track
                        applyStartThreshold(track)
                    },
                )
            )
            .build()

    /**
     * 当前生效链路已写入音频轨的帧数，null 表示该变体给不出这一读数。
     *
     * 只有自研的打包整型输出逐次记账音频轨实际接受的字节；两个默认变体的写入量在媒体3 的输出实现内部，
     * 外部取不到，且它可能重采样，拿转发字节数推算并不成立。故这一读数只在打包整型变体生效时有值——
     * 供信息采集算出「写入音频轨 → 发声」的全链路延迟。
     */
    fun writtenOutputFrames(): Long? = when (activeVariant) {
        OutputVariant.INT24 -> int24Sink.writtenOutputFrames
        OutputVariant.INT32 -> int32Sink.writtenOutputFrames
        else -> null
    }

    /**
     * 把起播阈值收到缓冲的一半。
     *
     * 平台没有在 `AudioTrack.Builder` 上给出入口，只能建轨后写入；轨道建起即回调，早于任何 [play]，
     * 故写入的是「起播用」的那一版阈值。失败不致命：退回平台初值（等于缓冲容量），只是起播多等一段，
     * 故按日志记录后放过，不影响播放。
     */
    private fun applyStartThreshold(track: AudioTrack) {
        runCatching {
            track.setStartThresholdInFrames(PlaybackBufferPolicy.startThresholdFrames(track.bufferSizeInFrames))
        }.onFailure {
            CrashLogManager.logException(LOG_TAG, "设置起播阈值失败，沿用平台初值", it)
        }
    }

    /** 当前生效的变体：流数据与位置查询都经它；能力查询另走能力基准，见 [supportsFormat] */
    private fun active(): AudioSink = when (activeVariant) {
        OutputVariant.FLOAT -> floatSink
        OutputVariant.INT16 -> intSink
        OutputVariant.INT24 -> int24Sink
        OutputVariant.INT32 -> int32Sink
    }

    /**
     * 该曲目应当用哪个变体写出。
     *
     * 唯一依据是直出已受理的混音器属性编码（[directOutputEncoding]）：写出编码须与之逐字段一致——
     * 已核实，格式与偏好不符时 AudioFlinger 不报错，而是把该轨静默混音输出，「已直出」名不副实。
     * 故不在此另行挑选：直出侧按源格式优先试出的条目未必在设备声明之列，按声明重挑只会挑到别的编码。
     *
     * 线性 PCM 的设备级能力无从探测（AudioTrack 经混音输出普遍接受浮点与整型，media3 也只按 API 级别
     * 判定支持），按格式分化的只有专用输出流的匹配，故仅在直出已建立时决策，其余情况保持浮点。
     */
    private fun variantFor(): OutputVariant {
        if (directTarget() == null) return OutputVariant.FLOAT
        return when (directOutputEncoding()) {
            AudioFormat.ENCODING_PCM_16BIT -> OutputVariant.INT16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> OutputVariant.INT24
            AudioFormat.ENCODING_PCM_32BIT -> OutputVariant.INT32
            // 浮点变体本就以浮点写出；未受理编码不在此时取值（directTarget 非空即编码非空）
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
        // 进入方此前也是在退出使用的那一刻被复位的，此刻两个变体都不在播，而渲染器只在停止 → 启动的
        // 转换点上下发 [play]，换曲重配恰好落在播放中时不会有第二次——故按登记的播放意图补上一次。
        // 少了这一次，进入方的音频轨建起后不会被启动，位置随之停滞：渲染器此时「已就绪且正在播放」
        // 而位置不再推进，媒体3 的停滞检测满 10 秒即以超时错误终止播放。
        if (playRequested) active().play()
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        val format = audioSinkConfig.format
        // 留一份最近配置：直出路由在曲中变化时要重跑一次它才能真正生效（见 [syncRouting]）
        lastConfig = audioSinkConfig
        if (playbackHandler == null) playbackHandler = Looper.myLooper()?.let(::Handler)
        // 先上报再挑变体：直出侧据此下发混音器属性，属性生效后 directTarget 才给出设备，
        // 变体才能按与属性同一个条目来选；音频轨在下一次数据写入时才建立，属性来得及生效。
        // 倍率同取这一份已受理的属性，故也在这一步之后才有取值
        onDecodedFormatChanged(format.sampleRate, format.channelCount, format.pcmEncoding)
        switchTo(variantFor())
        onOutputVariantChanged(activeVariant == OutputVariant.FLOAT)
        // 换了流（或换了档位）：上一轮的余量与冲段都属旧流，插值器也随新倍率重建，一律作废
        discardUpsampled()
        upsampler = buildUpsampler(format)
        innerConfig = scaledConfig(audioSinkConfig, upsampler?.multiple ?: 1)
        active().configure(checkNotNull(innerConfig))
    }

    /**
     * 本次配置的插值器：直出给出的倍率大于 1、且解码编码插得动时才有。
     *
     * 编码插不动却仍按倍率开轨，落到「写出率与混音器属性不符 + AudioFlinger 静默混音」的组合上，
     * 故退回按源采样率写出。这种情况正常不会出现：直出挑倍率档时已按同一个解码编码筛过
     * （两处的取值同出 [onDecodedFormatChanged] 那一次上报），此处是兜底。
     */
    private fun buildUpsampler(format: Format): SameFamilyUpsampler? {
        val multiple = directOutputRateMultiple()
        if (multiple <= 1) return null
        if (!canInterpolatePcm(format.pcmEncoding)) return null
        return SameFamilyUpsampler(multiple)
    }

    /**
     * 内层 sink 的配置：倍率档下把采样率与编码器延迟/填充按倍率放大，其余原样带回。
     *
     * 采样率决定内层按什么速率建音频轨——轨道必须开在倍率档的采样率上，才与已受理的混音器属性逐字段
     * 一致、挂得上专用输出流。延迟与填充按帧计，而数据在进入内层之前已被本层插值放大，计数不跟着放大
     * 就会曲首尾各裁错一截（gapless 接缝与首尾样本都会错位）。
     *
     * 声道映射、时间轴与媒体期编号原样带回：它们与写出格式无关，丢一个就可能让内层按另一条时间轴记账。
     * 已废弃的 preferredBufferSizeOverride 不带走：本应用走的两条渲染器路径都不设它
     * （MediaCodecAudioRenderer 与 DecoderAudioRenderer 各自只设声道映射、时间轴与媒体期编号），
     * 重建即默认 0，与原来同值，故不必为它碰废弃接口。
     * 倍率为 1 时整份配置原样交回，非直出与按源格式直出两条路径因此与从前逐字段一致。
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

    /**
     * 丢弃插值余量与冲段状态。
     *
     * 余量是「已插值而未写出」的数据，其基准是那条流的延迟线；流一换（重配、冲刷、复位）便无从续起，
     * 留着只会把旧流的数据写进新流。插值器的延迟线随之清空，新流从零历史起算。
     */
    private fun discardUpsampled() {
        pendingUpsampled = null
        tailFlushed = false
        upsampler?.reset()
    }

    /**
     * 直出路由变化后让生效变体跟上。
     *
     * 写出编码是构造期取向，切换必须重开音频轨，而 [configure] 是变体决策的唯一落点，故重跑一次配置。
     * 变体未变则什么都不做：换曲重配点本就会挑对变体，在此重配只会白开一次音频轨。
     *
     * 一律投回音频线程：本方法由直出的成色回调驱动，那处可能来自主线程（开关、设备插拔），
     * 而配置只能发生在音频线程上。投递同时保证不会在渲染器调用音频输出的栈内重入。
     */
    fun syncRouting() {
        val handler = playbackHandler ?: return
        handler.post {
            val config = lastConfig ?: return@post
            if (released) return@post
            if (variantFor() == activeVariant) return@post
            // 重配由本类自发起，不经渲染器的错误处置，故失败自己吞掉：下次换曲的重配点会照常重来
            runCatching { configure(config) }.onFailure {
                CrashLogManager.logException(LOG_TAG, "直出路由变化后的重配失败", it)
            }
        }
    }

    override fun setListener(listener: AudioSink.Listener) {
        rendererListener = listener
    }

    /**
     * 能力查询按浮点变体（能力基准）回答，不随当前生效变体变化。
     *
     * 三个变体都接受线性 PCM——整型一侧的转换由媒体3 的 `ToInt16PcmAudioProcessor` 承担，非浮点变体上
     * 必定挂它——故「能否接受这一格式」是接收器的整体能力，与「本曲由哪个变体写出」是两件事。
     *
     * 按生效变体回答会让取值随上一曲遗留的变体漂移：渲染器配置解码器时以浮点格式探一次，命中才向
     * 解码器索取浮点输出；上一曲是 16 位源时变体已落到整型，探针失手，下一曲便拿不到浮点解码输出，
     * 且此后无缘再回到设备声明的浮点条目。
     *
     * 不按源位深收窄浮点申报：媒体3 只在恰好收到 [AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY] 时才向解码器
     * 索取浮点输出，而直出追求的是按源格式写出，解码统一落在浮点、由写出端按目标位深取整，精度只多不少；
     * 16 位及以下的源被截到 16 位解码换不来任何收益，故申报恒报浮点。
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

    // 变速功能已移除：playback parameters 保持默认值，不接受外部调速请求
    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit

    override fun isEnded(): Boolean = active().isEnded

    // 倍率档下本层还持着已插值而未写出的余量，内层未必知道它——不并进来，媒体3 会在余量写出之前
    // 就认为输出已空（渲染器据此判定播放是否推进）
    override fun hasPendingData(): Boolean =
        pendingUpsampled != null || active().hasPendingData()

    /**
     * 写出数据；生效变体建不起音频轨、或建起后写不出，都逐档降级，绝不把该失败传成致命错误。
     *
     * 两种失败都要拦，且都在真实链路上、别无更早的判据：
     * - **建不起轨**：「混音器属性被系统受理」并不保证音频轨建得起来（实测：默认行为的
     *   48000Hz/2ch/24 位被受理后，建轨抛 UnsupportedOperationException 并让整曲播放以
     *   ERROR_CODE_AUDIO_TRACK_INIT_FAILED 终止）；
     * - **写不出**：建得起轨也不保证写得出去（实测同一台设备：24 位轨建成后写返回
     *   `AudioTrack.ERROR_INVALID_OPERATION`，播放以 ERROR_CODE_AUDIO_TRACK_WRITE_FAILED 终止）。
     *   该错误码不在媒体3 的可恢复集（只有 ERROR_DEAD_OBJECT 算可恢复），故它会直接终止整曲播放。
     *
     * 直出是尽力而为：任一变体失败都换下一个续写，全部用尽才交可恢复错误——把决定权还给渲染器，
     * 而不是以不可恢复错误终止播放。**下一档由直出重挑得出，不是一张固定次序表**：撤销失败的格式后
     * [onDirectOutputUnrealizable] 会把该格式记为不可用并重新挑选候选，于是降级次序正是设计的那条——
     * 先与源同率的档位（数据一字不动），被证伪后退到同族倍率档（数据由本层补齐），两者都用尽才回系统混音
     * （[variantFor] 在其后无已受理编码时给出浮点变体）。固定次序表做不到这一点：它不知道设备声明了哪些位深。
     *
     * 降级前先撤销直出的该格式偏好：写出编码必须与已受理的混音器属性一致，换了编码就不能再挂着那条
     * 专用流，否则 AudioFlinger 静默混音、「已直出」名不副实。记入不可用则保证同一失败不会反复发生。
     *
     * 两种失败对缓冲的影响不同：建轨失败发生在对本缓冲的任何读写之前，可直接改交下一个变体；写出失败时
     * 缓冲可能已被消费（媒体3 的输出实现会就地消费输入缓冲）。故一则统一退回本轮的缓冲起点再重试：
     * 失败的一方已随切换被释放，它接纳而未播出的数据不会再播出，整块重写才是连续的。
     *
     * 倍率档下写出的是插值后的余量：源缓冲由本层接下（见 [handleBuffer]），降级重试因此打在余量上，
     * 与源缓冲无关。余量写完之前不得让渲染器推进——这是本层对媒体3 的契约（它承诺以同一缓冲再调用）。
     */
    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        val upsampler = upsampler
            ?: return writeWithFallback(buffer, presentationTimeUs, encodedAccessUnitCount)
        // 余量优先：插值只做一次，失败重试与接着写都从余量继续（见 [upsampleInto]）
        val pending = pendingUpsampled ?: upsampleInto(buffer, upsampler).also { pendingUpsampled = it }
        val handled = writeWithFallback(pending, presentationTimeUs, encodedAccessUnitCount)
        // 降级重挑出的档位换了倍率：余量已是旧倍率下的数据，整体重配已把余量作废。源缓冲未被消费，
        // 原样交回渲染器，它会以同一缓冲再送一次，由新倍率重新插值（见 [writeWithFallback]）
        if (pendingUpsampled !== pending) return false
        // 整块输入已被插值接过，无论余量写没写完，源缓冲都算消费完——余量由本层持有，不再回看源缓冲
        buffer.position(buffer.limit())
        if (pending.hasRemaining()) return false
        pendingUpsampled = null
        return handled
    }

    /**
     * 把 [buffer] 交给生效变体写出，失败即按直出重挑出的下一档降级续写；返回是否写完。
     *
     * 未写完时 [buffer] 的位置停在已消费处，由调用方决定继续持有还是交回渲染器（见 [handleBuffer]）。
     * 倍率档多一种情形：重挑出的档位**倍率不同**（从 ×2 退到 ×4，或档位全部用尽而交回系统混音）。
     * 手里的余量是旧倍率下的数据，写进新档就是错速率的音频，比丢掉一段余量严重得多，故一律整体重配
     * （[configure] 会重建插值器与内层配置）并把余量置空交回调用方——源缓冲未被消费，渲染器原样再送，
     * 数据不丢。
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
                CrashLogManager.logException(LOG_TAG, "变体 $activeVariant 建不起音频轨，降级续写", e)
            } catch (e: AudioSink.WriteException) {
                // 可恢复的写出错误交回渲染器冲刷后重试，不在本层吞掉
                if (e.isRecoverable) throw e
                cause = e
                CrashLogManager.logException(LOG_TAG, "变体 $activeVariant 写出失败，降级续写", e)
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
            // 重挑之后才知道下一档：仍能直出即取新受理编码对应的变体，无档位可退则为浮点（系统混音）
            val next = variantFor()
            if (next in tried) throw recoverable(checkNotNull(cause))
            // 直接切到下一档，不经 [configure]：那会按已受理的编码重挑变体，降级就白做了
            switchTo(next)
            onOutputVariantChanged(next == OutputVariant.FLOAT)
            runCatching { active().configure(checkNotNull(innerConfig)) }.onFailure {
                CrashLogManager.logException(LOG_TAG, "直出降级后的重配失败", it)
            }
        }
    }

    /**
     * 把 [source] 整块插值到复用落点，返回已 flip 的缓冲。
     *
     * 输入的位置在此借还（[ByteBuffer.mark] / [ByteBuffer.reset]）：插值本身要把输入读到末位，但源缓冲
     * 「算不算被消费」由调用方决定——降级中倍率变了时，这一块要原样交回渲染器按新倍率重来。
     */
    private fun upsampleInto(source: ByteBuffer, upsampler: SameFamilyUpsampler): ByteBuffer {
        val format = checkNotNull(lastConfig).format
        val frames = source.remaining() /
            Util.getPcmFrameSize(format.pcmEncoding, format.channelCount)
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
     * 把插值器的尾段冲出来，返回已 flip 的缓冲。
     *
     * 半带插值有固有延迟，曲末的原样本还留在延迟线里，不冲出来就是丢样本（见 [SameFamilyUpsampler]）。
     * 冲段按静音补齐输入——曲末之后本就是无声。
     */
    private fun drainInto(upsampler: SameFamilyUpsampler): ByteBuffer {
        val format = checkNotNull(lastConfig).format
        val frameSize = Util.getPcmFrameSize(format.pcmEncoding, format.channelCount)
        val output = scratchFor(upsampler.drainFrames * frameSize)
        upsampler.drain(format.channelCount, format.pcmEncoding, output)
        output.flip()
        return output
    }

    /**
     * 把插值尾段写进生效变体，返回是否已冲完。
     *
     * 先写完手上的余量再冲段，次序不能反——余量在时间上先于尾段。未冲完时留待下次调用，此时不得让
     * 内层收尾（见 [playToEndOfStream]）。冲段写出失败即放弃它：尾段不过一毫秒上下，
     * 让渲染器一直等下去才是真损失。
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

    /**
     * 插值落点：按需增长后复用。
     *
     * 用直接缓冲与平台字节序，与 [IntPcmAudioSink] 的转换缓冲同一口径，内层按同一套约定读它。
     */
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

    // 全部变体都用尽：以可恢复错误交回渲染器重试，不用不可恢复错误终止整曲播放
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

    // 音频轨的实际释放只发生在 media3 的 flush 内（释放异步延后），故释放请求在此登记；
    // 登记的时点早于释放事件，后续建轨会撤销它，据此把迟到的释放事件判为不属于当前链路
    override fun flush() {
        outputReleaseRequested = true
        // 余量与冲段都属被冲刷掉的那条流：留着只会把旧流的数据写进新流
        discardUpsampled()
        active().flush()
    }

    /**
     * 收尾：先把插值器的延迟段冲出来，再让内层收尾。
     *
     * 冲出未完成时不得让内层收尾，也不得报已结束——媒体3 会反复调用本方法直到写出结束
     * （内层对自家余量取的也是这个口径）。
     */
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
        // 只作废资源，不动起停意图：媒体3 的 reset 是资源操作，**不代表渲染器停了播**——实测（播放中切轨）
        // reset 之后渲染器仍处于已启动状态，此后不会再下发 play。把意图一并清掉，退出使用的变体被复位后
        // 新生效的变体就不会被启动，位置随之冻结，媒体3 的停滞检测满 10 秒即以 ERROR_CODE_TIMEOUT 终止播放。
        // 起停只由 play/pause 决定——媒体3 的渲染器起停也只经这两个调用下发。
        outputReleaseRequested = true
        // 余量与冲段同属被复位掉的那条流（切轨即换流），留着会被写进新流
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

// 写出变体：四者的写出编码互不相同，且都是构造期取向，故以变体身份而非布尔标记区分当前生效者
private enum class OutputVariant { FLOAT, INT16, INT24, INT32 }

/**
 * 音频接收回调的转接器：透传渲染器的回调，并截取音频轨被创建与被释放时的写出编码。
 *
 * 写出编码只在音频轨被创建的那一刻可知，且不经监听器无从取得，故在此截取而非按变体与源格式推测
 * ——写出编码由生效变体与音频轨的实际能力共同决定，推测值未必等于真正写出的编码。
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
