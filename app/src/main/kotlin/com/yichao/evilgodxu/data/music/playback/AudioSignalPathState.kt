package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import android.media.AudioTrack
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yichao.evilgodxu.data.music.analysis.TrackAudioInfoReader
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 当前播放链路的可观测状态：源格式、解码器、输出编码与 USB 输出成色。
 *
 * 这些值只服务音频信息面板与输出路由判定，不参与播放推进；它们的写入方来自播放服务、
 * 轨道回调与输出装配，读取方来自界面。状态与「按当前曲目源格式做一次探测」的编排放在一起，
 * 避免归属判定散落到各个写入点。
 */
internal class AudioSignalPathState(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
    private val currentTrack: () -> MusicTrack?,
) {

    var audioSignalPathFormat by mutableStateOf<AudioSignalPathFormat?>(null)
    // 音频信息所属曲目：保证格式信息始终与当前曲目对应，后台切歌后再回前台不会错配
    var audioSignalPathTrackId by mutableStateOf<Long?>(null)
    // 音频信息所属的音频源 URI：无损升级会就地替换同一曲目的文件，曲目 ID 不变而格式已变，
    // 仅以 ID 判定"已是最新"会漏刷新（信息条停留在旧格式），故与 ID 一并作为归属标识
    var audioSignalPathSourceUri by mutableStateOf<String?>(null)

    // 当前音频渲染器实际使用的平台解码器名（如 c2.android.flac.decoder）。
    // 由播放服务的解码器初始化回调写入。解码器可跨曲复用、复用时不重复回调，故该值不随切歌清空；
    // 它只保证「上一次初始化时是哪个解码器」，切到本机没有解码器的格式后不会再更新（见 audioTrackDecodable），
    // 展示端须以归属判定为准，不能把这里的残留值当作当前曲目的解码器
    var audioDecoderName by mutableStateOf<String?>(null)

    // 当前音频轨的编码 mime（如 audio/alac）：由轨道回调按当前曲目的音频组写入，
    // 与是否被选中无关——选中与否正是「本机有没有该格式解码器」的判据。
    // 播放停止后保持末值，切歌时随之刷新
    var audioTrackMimeType by mutableStateOf<String?>(null)
    // 当前音频轨是否被播放器选中：媒体3 在设备无该格式解码器时把整条音频轨判为不支持而不选中，
    // 表现为静音播放且不报错，是音频信息面板与提示判断「能不能解码」的唯一依据。
    // null 表示尚未装载曲目（无从判定）
    var audioTrackDecodable by mutableStateOf<Boolean?>(null)
    // 音频输出链路当前是否以浮点 PCM 写出：null 表示输出尚未建立（未起播或已停止）
    var audioSinkFloatOutput by mutableStateOf<Boolean?>(null)

    // 音频输出链路实际写出的 PCM 编码（AudioFormat 编码值）：null 表示输出尚未建立（未起播或已停止）
    var audioSinkOutputEncoding by mutableStateOf<Int?>(null)

    /**
     * 音频轨实际建起的采样率：null 表示输出尚未建立。
     *
     * 不按源格式推算——同族倍率档下轨开在倍率速率上（44.1 的源、88.2 的轨），推算值会与真正写出速率的
     * 那一个差一个倍率。取轨道自报值，面板的「输出采样率」才不会把 44.1 的源说成 44.1 的输出。
     */
    var audioSinkOutputSampleRate by mutableStateOf<Int?>(null)

    /**
     * 解码头实际输出的格式，null 表示尚未取得。
     *
     * 由音频输出在重配那一刻上报——容器格式对压缩源只给采样率与声道，编码要等解码头出格式才知道，
     * 故这一项是全链路最早拿到的解码输出真值（直出据此挑选混音器属性，音频信息面板据此展示）。
     */
    var audioSinkDecodedFormat by mutableStateOf<DecodedOutputFormat?>(null)

    /**
     * 输出链路当前使用的音频轨，null 表示输出尚未建立。
     *
     * 延迟只能从该轨本身取：平台既没有按设备查询延迟的公开接口，AudioManager 给的输出帧数也只对应 HAL
     * 单次突发、与当前走哪一路无关。它由音频输出在建轨与释放时上报，生命周期与写出编码一致，
     * 取值的信息采集跑在后台线程，故以 volatile 跨线程发布。
     */
    @Volatile
    var audioTrack: AudioTrack? = null

    /**
     * 已写入当前音频轨的帧数读取入口，外层 null 表示当前链路不提供这一读数、内层 null 表示此刻取不到。
     *
     * 写作入口而非取值：写入量随播放持续变化，按轨道建立那一刻的取值发布只会得到一个过期数。
     * 入口随音频输出装配一次（服务创建），取值发生在界面组合，两者同在主线因此无需额外同步；
     * 入口内部读到的是播放线程写的量，那几项各自按 volatile 发布。
     */
    var audioSinkWrittenFrames: (() -> Long?)? = null

    /**
     * 独占路径的延迟读数入口；未建立等时输出时返回 null。
     *
     * 与 [audioTrack] 并列而非二选一：那条路上没有音频轨可读（数据直接交给等时端点），故读数只能由
     * 写出侧按自己的缓冲算出，取值为 null 即表示当前不在独占路径上。
     */
    var usbExclusiveLatency: (() -> OutputLatencyReading?)? = null
    // 直出的当前成色：未启用、无设备接入或设备未提供动态混音端口时保持系统混音
    var directOutputMode by mutableStateOf(AudioOutputMode.MIXER)

    /**
     * 独占路径当前是否在承接写出。
     *
     * 不复用 [directOutputMode] 是因为两者的判定方不同：直出的成色由 UsbDirectOutput 按平台是否受理那条
     * 专用输出流判定，独占的有无则是输出路由本次实际选中了哪条路。两个写入方共用一个字段会互相覆盖，
     * 而这两件事都可能各自变化（开关打开但设备声明不了本次格式时，独占并不成立）。
     */
    var usbExclusiveRoute by mutableStateOf(false)

    /**
     * 独占路径接管到的解码器；未接管时为 null。
     *
     * 独占期间它已从系统音频里消失（内核驱动被解绑），平台侧一次也查不到，故设备信息只能由接管方给出。
     */
    var usbExclusiveDevice by mutableStateOf<UsbExclusiveDeviceInfo?>(null)

    /**
     * 独占模式是否已接管设备（开关开启且设备接入并授权）。
     *
     * 与 [usbExclusiveRoute] 的区别在时机：路由要到独占真正承接写出时才成立，而接管在设备接入那一刻
     * 就已发生——设备已被内核解绑，系统媒体音量此后不再作用于它。因此音量归属（应用自持档位还是
     * 系统媒体流）以本标志为准，不能等路由成立。
     */
    var usbExclusiveClaimed by mutableStateOf(false)

    /**
     * 独占音量的读写入口，由播放服务装配；服务不在时为空。
     *
     * 界面侧的音量手势直接落在这里，而不是经媒体会话的远端通道转一手：档位的事实由播放服务持有，
     * 会话只是它对系统音量键与音量面板的出口。会话尚未连上时界面同样能读写，接管与起播之间的
     * 那段时间因此不会落到系统媒体音量上。
     */
    var exclusiveVolumeStep: (() -> Int)? = null
    var setExclusiveVolumeStep: ((Int) -> Unit)? = null

    /**
     * 两个 USB 输出开关的启用状态，由播放服务从设置订阅后写入。
     *
     * 心动模式的准入直接看这两个开关，而不是看输出成色：两条路的成色都要等设备接入、属性和格式被受理
     * 之后才成立，而用户的取向在拨动开关那一刻就定了——等到成色成立才挡，中间那段时间里仍能进入片段
     * 播放，取向自相矛盾。
     */
    var usbDirectOutputEnabled by mutableStateOf(false)
    var usbExclusiveEnabled by mutableStateOf(false)

    /** 格式信息是否对应当前曲目的当前音频源（供信息条判定是否展示，避免换源后短暂错配残留）。 */
    val isCurrentForCurrentTrack: Boolean
        get() = currentTrack()?.let(::isSourceCurrent) == true

    /**
     * 起播前落定目标曲目的源位深与声道，供音频信息条在解码前即展示源格式。
     *
     * 源格式是异步读来的，而信息条在切歌后随即读取；先在把新曲目交给播放器之前按容器头读一次，展示即
     * 不必等待解码回填，播放线程上的轨道回调也因记录已归属本曲目而省去一次同步的容器头读取。
     *
     * 只读容器头（定长窗口，含前置元数据块的偏移重定位），不读采样率与比特率——那两项由轨道回调补齐。
     * 容器头读不出的源（有损格式、在线源）保持原样不动，后续由轨道回调或缓存完成后的补读取齐。
     *
     * 归属已属于该曲目时直接跳过：此时记录只会更完整（可能已由轨道回调填了采样率与比特率），
     * 重读不会带来更多信息。
     */
    fun recordSourceBitDepthBeforePlayback(track: MusicTrack) {
        val context = context() ?: return
        if (isSourceCurrent(track)) return
        val container = TrackAudioInfoReader.readContainerFormat(context, track) ?: return
        audioSignalPathFormat = AudioSignalPathFormat(
            format = null,
            sampleRate = null,
            outputRate = null,
            bitDepth = container.bitDepth,
            channels = container.channels,
            bitrate = null,
        )
        audioSignalPathTrackId = track.id
        audioSignalPathSourceUri = track.audioUri
    }

    // 冷启动未播放时预读当前曲目格式信息，供音频信息条展示；开始播放后由解码头覆盖
    fun refreshIdleTrackFormatInfo(context: Context) {
        val track = currentTrack() ?: return
        if (isCompleteFor(track)) return
        scope().launch(Dispatchers.IO) {
            val info = TrackAudioInfoReader.readIdleFormat(context, track) ?: return@launch
            // 落盘前的两道闸：读盘期间已切歌的不覆盖，已被解码回调补全的记录也不覆盖；
            // 归属为空但曲目未变（reconcile 清空后重读）时照常写入
            if (currentTrack()?.id != track.id || isCompleteFor(track)) return@launch
            audioSignalPathFormat = info
            audioSignalPathTrackId = track.id
            audioSignalPathSourceUri = track.audioUri
        }
    }

    // 缓存完成后以本地缓存文件为源强制补齐当前曲目格式信息，避免在线播放期间信息条空白
    fun refreshTrackFormatInfoFromLocal(context: Context) {
        val track = currentTrack() ?: return
        if (!track.isLocalAudioSource) return
        scope().launch(Dispatchers.IO) {
            val info = TrackAudioInfoReader.readIdleFormat(context, track) ?: return@launch
            if (currentTrack()?.id == track.id) {
                audioSignalPathFormat = info
                audioSignalPathTrackId = track.id
                audioSignalPathSourceUri = track.audioUri
            }
        }
    }

    // 回到前台时校正音频信息：与当前曲目错配时清掉旧值并重新读取当前曲目源格式，
    // 保证信息条始终对应当前曲目而不依赖解码回调回填
    fun reconcileTrackFormatInfo(context: Context) {
        val track = currentTrack() ?: return
        if (isSourceCurrent(track)) return
        audioSignalPathFormat = null
        audioSignalPathTrackId = null
        audioSignalPathSourceUri = null
        refreshIdleTrackFormatInfo(context)
    }

    // 已展示的格式信息是否就属于该曲目的当前音频源：曲目或音频源任一变化都需重算
    private fun isSourceCurrent(track: MusicTrack): Boolean =
        audioSignalPathTrackId == track.id && audioSignalPathSourceUri == track.audioUri

    // 可用的最新记录：归属正确之外，格式与采样率还须已就位。起播前只预置位深与声道
    // （见 recordSourceBitDepthBeforePlayback），格式与采样率尚缺；仅按归属就把它当最新，
    // 会挡住后来的补齐，信息条与音频参数面板便停在这份缺项记录上不再回填
    private fun isCompleteFor(track: MusicTrack): Boolean =
        isSourceCurrent(track) &&
            audioSignalPathFormat?.let { it.format != null && it.sampleRate != null } == true
}
