package com.yichao.evilgodxu.data.music.playback

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import com.yichao.evilgodxu.log.CrashLogManager
import java.util.concurrent.ConcurrentHashMap

// USB 解码器会以设备、耳机、配件三类上报，三者都是可直接播放的输出目标
private val USB_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
)

// 诊断日志的类名前缀：日志文件按「类名: 描述」成条，直出链路的决策结果都记在此名下
private const val LOG_TAG = "UsbDirectOutput"

/**
 * USB 直出：把播放钉到 USB 解码器，并向原生音频策略申请与该设备格式对齐的专用输出流。
 *
 * 直出由两个原生接口共同构成，缺一不可：
 * - [AudioManager.setPreferredMixerAttributes] 为该设备建立按所请求格式打开的专用输出流；
 * - [ExoPlayer.setPreferredAudioDevice] 把播放器的输出路由固定到同一设备，
 *   使该流成为播放的唯一出口。
 *
 * 专用流只接纳与混音器属性逐字段一致（采样率、声道、编码）的播放，混音器属性因此按当前曲目的解码格式
 * 从设备声明的档位里挑，并在换曲导致格式变化时重新下发。
 *
 * 混音器属性分两档取用，成色随之不同：
 * - 位完美：端口声明了 AUDIO_OUTPUT_FLAG_BIT_PERFECT，或厂商未声明而平台受理了本应用的位完美请求
 *   ——音频不经混音、不受音量与音效处理，数据原样下发到 HAL；
 * - 源格式直出：位完美请求未被受理时的兼容结果——仍按设备声明的格式请求该端口的输出流，播放格式对齐
 *   即不发生重采样，但音轨音量与音效按常规链路处理。
 *
 * 档位只取自**设备声明的动态混音端口条目**，且本次连接只读取一次（见 [declaredFormatsFor]）；不按源位深
 * 与源采样率另拼一条去试探未声明的参数——试探只换来一次被拒或一条写不出的轨道，而设备侧真正支持的档位
 * 恰在声明里。选不出档位（声明里没有与当前解码格式逐字段相符的条目）即交回系统混音；档位建不起或写不出
 * 再由音频输出逐档降级（见 [reportUnrealizableFormat]）。
 *
 * 原生行为（已核实）：
 * - 受理条件：APM 要求 usage 为 USAGE_MEDIA、设备为已接入的 USB 输出，且存在与目标格式、采样率、
 *   声道及各行为兼容的动态输出 profile；标志由行为反推——位完美行为对应 AUDIO_OUTPUT_FLAG_BIT_PERFECT，
 *   默认行为不附加标志，故漏标位完美标志的端口仍能受理默认行为的请求。任一条件不满足即返回 BAD_VALUE，
 *   此处体现为 set 返回 false；缺少 MODIFY_AUDIO_SETTINGS 则为 PERMISSION_DENIED。
 * - 行为枚举：对每个支持该设备的动态输出 profile，恒有一条默认行为条目；只有 profile 声明了位完美标志
 *   才额外多出一条位完美条目（IOProfile::refreshMixerBehaviors）。漏标因此只影响成色，不影响可用性；
 *   而行为是**请求**——声明里没有的档位通常直接被拒（BAD_VALUE），故漏标的端口上请求位完美多半失败
 *   （应用仍先试一次，见 [applyMixerAttributes]）。
 * - 拔出：APM 在断连的同一路径内直接清除该端口的偏好且不回调，故只能经 AudioDeviceCallback 感知。
 * - 端口查询：getSupportedMixerAttributes 直查音频策略，Java 层把任何非 SUCCESS 一律折成空表，故空表
 *   既可能是厂商没声明端口，也可能是该设备此刻没有输出（平台按输出端口应答）。实测同一台设备无输出时
 *   空表、有输出时 7 条档位，故空表不作终局结论——问到非空为止（见 [declaredFormatsFor]）。
 * - 格式不符：写出格式与偏好混音器不一致时，AudioFlinger 不会失败，而是把该轨静默混音输出，
 *   因此输出格式必须与偏好对齐，才不会以「已直出」之名走混音路径。路由在曲中才成立时，写出变体必须
 *   跟着重配（见 [PerDeviceAudioSink.syncRouting]），否则状态与实际写出会各说一套。
 *
 * 直出能否成立取决于设备接入与厂商声明，判定依据只在设备现场可得，故开关状态与每次路由重算
 * 的结论都写入诊断日志（设置页可分享），使「设备已识别而直出未生效」能在日志中定位到具体环节；
 * 解码格式与设备声明的档位（采样率、编码）另由音频信息面板直接展示，日志不再重复罗列。
 *
 * 线程：直出配置必须在音频轨建立之前下发，而解码格式只有播放线程在音频输出重配那一刻才拿得到，
 * 故 [onTrackFormatChanged] 由播放线程调用；[setEnabled] 与 [release] 由主线程调用——两处的重算
 * 都是拿 [AudioManager] 的现场状态重新求值，落点一致，故不额外加锁。播放器自身仍只受理主线程调用，
 * 涉及它的两处（音频属性、设备钉定）分别以构造期捕获与主线程投递规避，见 [playbackAttributes]
 * 与 [pinPreferredDevice]。
 */
@OptIn(UnstableApi::class)
class UsbDirectOutput(
    private val player: ExoPlayer,
    private val audioManager: AudioManager,
) {
    private var enabled = false
    private var callbackRegistered = false
    /** 已钉定的 USB 输出设备，null 表示当前未钉定路由 */
    private var targetDevice: AudioDeviceInfo? = null
    /**
     * 已被系统受理的混音器属性，null 表示未建立专用输出流。
     *
     * 一处状态管两件事：既是输出成色的依据（取值被拒时播放仍走系统混音，据此判定才不会以「已直出」
     * 之名走混音路径），也是抑制重复下发的判据（重复下发会让框架重开输出流，故已受理的取值在变化前
     * 不再下发）。两者必须同一份——把「下发过」与「已受理」分开记，被拒的取值就会挡住下次重试。
     */
    private var acceptedMixerAttributes: AudioMixerAttributes? = null
    /**
     * 本会话内已被证实建不起音频轨的直出格式。
     *
     * 「属性被系统受理」不等于音频轨建得起来（见 [reportUnrealizableFormat]）：该格式再被选中只会再失败
     * 一次，故一经实测失败即排除在本轮候选之外。按格式而非设备记：同一台设备上不同档位的可用性互不相干。
     *
     * 读写分处播放线程（轨道回调、音频输出上报）与主线程（开关、设备插拔），故用并发集合。
     */
    private val unrealizableFormats = ConcurrentHashMap.newKeySet<AudioFormat>()

    /**
     * [unrealizableFormats] 所归属的 USB 设备编号，null 表示尚无记录。
     *
     * 与当前设备不符即整体作废——上一台做不到的档位，新设备未必做不到。以设备编号而非 [targetDevice]
     * 判归属：撤销配置会把 [targetDevice] 置空，拿它判会把「同一台设备上的失败」误当成「换了设备」。
     */
    private var unrealizableFormatsDeviceId: Int? = null
    /**
     * 本次连接问到的设备声明档位，及其所属设备编号。
     *
     * 声明是设备的固定属性，问到一次即认定本次连接的答案已定（见 [declaredFormatsFor]），故与设备编号一并记。
     */
    private var declaredFormats: List<AudioMixerAttributes> = emptyList()
    private var declaredFormatsDeviceId: Int? = null
    /**
     * 本次连接位完美请求是否已被拒。
     *
     * 行为由动态输出 profile 的 AUDIO_OUTPUT_FLAG_BIT_PERFECT 反推，厂商不声明时每个候选都要白试一条下发
     * （实测同一会话里每个候选都带一条「位完美请求被拒」）。被拒一次即认定本次连接没有可声明的位完美档位，
     * 此后不再试探；换设备即作废。
     */
    private var bitPerfectRejected = false
    /** 已对外上报的输出成色，与 [onRoutingChanged] 的出参同处一处，避免内部状态与上报值脱节 */
    private var reportedMode = AudioOutputMode.MIXER
    /** 当前曲目的解码格式，混音器属性需与之逐字段（采样率、声道、编码）匹配才能被直出流接纳 */
    private var decodedSampleRate = 0
    private var decodedChannelCount = 0
    private var decodedPcmEncoding = 0

    /**
     * 上一条诊断日志的正文。
     *
     * 设备插拔回调与轨道回调都会触发路由重算，同一结论因此会被反复求出；去重后每条结论只在发生变化时
     * 落盘，日志读到的才是「何时因何原因变化」，而不是一串同义重复行。
     */
    private var lastDiagnostic: String? = null

    /** 输出成色变更回调：[AudioOutputMode.MIXER] 表示已回到系统混音输出 */
    var onRoutingChanged: ((AudioOutputMode) -> Unit)? = null

    private val audioDeviceHandler = Handler(Looper.getMainLooper())

    /**
     * 播放的原生音频属性。
     *
     * 沿用播放器自身的实例而非另建等价属性：原生侧按属性匹配播放记录。读取它需经播放器的主线程校验，
     * 而直出配置会在播放线程上下发，故在构造期（主线程）捕获一次——播放建立后音频属性不再变化。
     */
    private val playbackAttributes = player.audioAttributes.platformAudioAttributes

    private val deviceCallback = object : AudioDeviceCallback() {
        // 插拔会同时让路由与混音器属性失效，两者一并重算
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputRouting()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputRouting()
        }
    }

    /**
     * 钉定或解除播放的输出设备。
     *
     * 播放器只在应用线程受理这一调用，而直出配置可能在播放线程上下发，故一律投递到主线程；
     * 同一 Handler 串行执行，先后两次投递的次序与下发次序一致。
     */
    private fun pinPreferredDevice(device: AudioDeviceInfo?) {
        audioDeviceHandler.post { player.setPreferredAudioDevice(device) }
    }

    /** 开启或关闭直出；关闭时撤销配置并解除路由钉定，播放回到系统默认混音输出 */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        logDiagnostic(if (value) "直出开关打开，开始尝试接管输出路由" else "直出开关关闭")
        if (value) {
            registerCallback()
            refreshOutputRouting()
        } else {
            releaseConfiguration("开关关闭")
            unregisterCallback()
        }
    }

    /**
     * 已建立专用输出流的 USB 输出设备，null 表示当前未直出；供音频输出按设备选择写出格式。
     *
     * 仅钉定路由而未取得混音器属性时不返回设备——此时播放不挂直出流，写出格式无需与任何条目对齐，
     * 按默认变体写出即可；返回设备会让音频输出按一条不存在的直出流去挑格式。
     */
    fun directTargetDevice(): AudioDeviceInfo? =
        if (acceptedMixerAttributes != null) targetDevice else null

    /**
     * 已受理的专用输出流的写出编码，null 表示当前未直出；供音频输出选择与之逐字段一致的写出变体。
     *
     * 与 [directTargetDevice] 同取一份已受理的属性：编码无法由设备声明反推——源格式候选未必在声明之列，
     * 只有「系统实际受理的那条属性」才是写出必须对齐的目标。
     */
    fun directOutputEncoding(): Int? = acceptedMixerAttributes?.format?.encoding

    /**
     * 音频输出报告：已受理的直出格式在本机不可用（建不起音频轨，或建成后写不出），记为不可用并改挂下一档。
     *
     * 受理条件只说明设备侧有一条匹配的动态输出 profile，不说明本应用真的用得上它——实测同一台设备两处都
     * 失手过：默认行为的 48000Hz/2ch/24 位被受理后建轨抛 UnsupportedOperationException；96000Hz/2ch/24 位
     * 则是轨道建成后写返回 ERROR_INVALID_OPERATION。这一步是直出链路上唯一能拦住这两种情形的环节
     * ——事前实测的结论依赖「设备当时是否已有输出」，预测不了偏好生效后的建轨与写出，故不能代替此处的
     * 实测结论。
     *
     * 先改挂、不先撤销：setPreferredMixerAttributes 是覆盖式的，改挂成功即无需撤销，设备也不会被解钉。
     * 撤销会把设备解钉并让成色落回系统混音（免打扰随之白翻一次），且解钉之后本应用在该设备上没有输出、
     * 动态混音端口查询便一直得空表，直出再回不来。失败只是换一档，并没有离开直出，不该走那条路。
     * 改挂不成才撤销：那条失败格式的偏好此刻仍挂着，必须清掉，否则 AudioFlinger 会继续静默混音。
     * 两种情形下该格式都已记入 [unrealizableFormats]，再次重算时不再入选，避免同一失败反复发生。
     */
    fun reportUnrealizableFormat() {
        val attributes = acceptedMixerAttributes ?: return
        targetDevice?.let { unrealizableFormatsDeviceId = it.id }
        unrealizableFormats += attributes.format
        logDiagnostic("直出格式不可用（已受理但建不起轨或写不出），记为不可用并改挂下一档：${describeMixer(attributes)}")
        if (!applyNextDirectCandidate()) {
            releaseConfiguration("直出格式不可用")
        }
    }

    /**
     * 按设备声明的档位重挑一档改挂，返回是否改挂成功。
     *
     * 被证伪的格式已记入 [unrealizableFormats]，故重挑取到的是设备声明的下一档（位深由
     * [selectDirectMixer] 的优先序给出）；声明本身取自本次连接的记录，不重新查询设备——查询的应答取决于
     * 本应用此刻在该设备上有没有输出（见 [declaredFormatsFor]），而此刻刚释放掉那条失败的音频轨。
     * 无档位可挑（或挑中的也没被受理）时返回 false，由调用方交回系统混音。
     */
    private fun applyNextDirectCandidate(): Boolean {
        val device = findUsbOutputDevice() ?: return false
        val candidates = directMixerCandidates(declaredFormatsFor(device))
            .filterNot { it.format in unrealizableFormats }
        if (candidates.isEmpty()) return false
        // 属性先于路由下发：播放改道到该设备时，才按已配置的属性建立专用输出流
        val accepted = applyDirectMixer(device, candidates) ?: return false
        pinPreferredDevice(device)
        updateRouting(device, accepted)
        // 成色未变时 [updateRouting] 不发声，改挂后的写出格式在此留痕——音频输出的写出编码取的就是它
        logDiagnostic("直出格式已改挂：${describeMixer(accepted)}")
        return true
    }

    /**
     * 解码格式变化（换曲、换源）后记录新格式，直出开启时据此重新挑选混音器属性。
     *
     * [decodedPcmEncoding] 必须是解码头实际输出的线性 PCM 编码：容器格式给不出它（压缩源下为 NO_VALUE），
     * 只有音频输出在重配那一刻手上的解码输出格式才是真值，故由音频输出上报而非由轨道回调传入。
     * 上报点早于音频轨建立，属性才能在轨建起前生效。非 PCM 编码不予下发，交回系统混音。
     */
    fun onTrackFormatChanged(sampleRate: Int, channelCount: Int, decodedPcmEncoding: Int) {
        if (sampleRate == decodedSampleRate && channelCount == decodedChannelCount &&
            decodedPcmEncoding == this.decodedPcmEncoding
        ) {
            return
        }
        decodedSampleRate = sampleRate
        decodedChannelCount = channelCount
        this.decodedPcmEncoding = decodedPcmEncoding
        // 解码格式改由音频信息面板的「解码输出」一行展示，日志不再重复记录
        refreshOutputRouting()
    }

    /**
     * 音频输出（音频轨）建成后重算路由。
     *
     * 平台按**输出端口**报告动态混音端口的档位（getSupportedMixerAttributes 是对音频策略的直接原生查询，
     * Java 层把任何非 SUCCESS 一律折成空表）：同一台设备在应用没有输出时返回空表、有输出时返回全部档位。
     * 实测过的现象——开关在未起播时打开，查询得空表，直出被判成「设备未声明端口」；随后起播，
     * 输出已建成却没有人再问一次，直出就再也没回来。故每次输出建成都在此重问，空表不作终局结论。
     */
    fun onOutputEstablished() {
        refreshOutputRouting()
    }

    fun release() {
        enabled = false
        releaseConfiguration("直出释放")
        unregisterCallback()
    }

    private fun refreshOutputRouting() {
        if (!enabled) return
        val device = findUsbOutputDevice()
        // 换设备即作废上一台的记录（拔出时 device 为 null 同样作废）：上一台做不到的档位，新设备未必做不到。
        // 必须先于下面的判定——否则记录会把新设备的格式一并挡掉
        if (device?.id != unrealizableFormatsDeviceId) {
            unrealizableFormats.clear()
            unrealizableFormatsDeviceId = device?.id
            bitPerfectRejected = false
        }
        // 设备声明的动态混音端口条目：直出档位一律取自这里，不再按源格式另拼候选（见 [directMixerCandidates]）
        val supported = declaredFormatsFor(device)
        val built = directMixerCandidates(supported)
        // 已被实测证伪建不出音频轨的格式不再入选：再选只会再失败一次（见 [reportUnrealizableFormat]）
        val candidates = built.filterNot { it.format in unrealizableFormats }
        // 直出无从成立：撤销配置，交回系统混音。归因与结论一次取出——撤销说明与日志结论同出此处，
        // 两处才不会各说一套。构造出的候选全被剔除时，原因落在「格式建不出音频轨」而非「设备无条目」
        if (device == null || candidates.isEmpty()) {
            val (releaseReason, conclusion) =
                noDirectOutputReason(device, supported, built.isNotEmpty())
            releaseConfiguration(releaseReason)
            logDiagnostic(conclusion)
            return
        }
        if (device != targetDevice) {
            releaseConfiguration("改用其它 USB 输出设备")
            // 属性先于路由下发：播放改道到该设备时，才按已配置的属性建立专用输出流
            val accepted = applyDirectMixer(device, candidates)
            pinPreferredDevice(device)
            updateRouting(device, accepted)
            if (accepted == null) {
                logDiagnostic(
                    "USB 输出路由已钉定，但混音器属性未被系统受理，播放仍走系统混音：" +
                        deviceLabel(device)
                )
            }
            return
        }
        // 已钉定同一设备时，只有「当前候选里已有格式被系统受理」才不再下发：未受理、专用输出流已被撤销、
        // 换了格式，三者都表现为已受理格式不在本轮候选内，据此判定才不会把「上次被拒」当成「正在直出」。
        // 按格式比较而非整体相等：受理的可能是位完美的请求变体（行为不同、格式相同），
        // 行为不参与「要不要重下发」的判定——已经拿到位完美，再下发只会白开一次输出流。
        // 以「任一候选」而非「首个候选」为准：源格式候选被拒而声明条目受理时，重下发只会白开一次输出流
        val acceptedFormat = acceptedMixerAttributes?.format
        if (acceptedFormat != null && candidates.any { it.format == acceptedFormat }) return
        updateRouting(device, applyDirectMixer(device, candidates))
    }

    /**
     * 直出无从成立的原因，以及写给日志的结论（前者进撤销说明，后者进诊断日志）。
     *
     * 归因分三侧：**设备侧**（没有设备、没取到动态混音端口、端口没有匹配当前格式的条目）、**曲目侧**
     * （解码格式尚未上报、解码输出不是线性 PCM）与**建轨侧**（候选格式已被实测证伪建不出音频轨，
     * [allCandidatesUnrealizable]）。曲目侧那两种都会随音频输出上报而自行重试，建轨侧则说明设备侧与
     * 格式侧都没问题、卡在本应用的音频轨；三者混作一句「设备无可承载条目」，会把排查引到错误方向。
     * 两份结论同出此处，同一个判定不会在两处被写成两种说法。
     */
    private fun noDirectOutputReason(
        device: AudioDeviceInfo?,
        supported: List<AudioMixerAttributes>,
        allCandidatesUnrealizable: Boolean = false,
    ): Pair<String, String> {
        val label = device?.let(::deviceLabel)
        return when {
            device == null ->
                "无 USB 输出设备" to "未找到 USB 输出设备，直出未生效"
            supported.isEmpty() ->
                "设备未取到动态混音端口" to
                    "USB 设备 $label 未取到动态混音端口（厂商未声明，或设备当前没有输出、平台便不作应答），" +
                    "解码格式 ${describeDecodedFormat()}"
            decodedSampleRate <= 0 ->
                "解码格式尚未取得" to
                    "USB 设备 $label 已声明动态混音端口，但解码格式尚未取得（尚未起播），"
            !Util.isEncodingLinearPcm(decodedPcmEncoding) ->
                "解码输出不是线性 PCM" to
                    "USB 设备 $label 已声明动态混音端口，但本曲解码输出不是线性 PCM，无位深可对齐，" +
                    "直出无从成立，解码输出 ${describeDecodedFormat()}"
            // 候选本身构造出来了，只是格式已被实测证伪「建不出音频轨」——与「设备没有可承载条目」是两回事，
            // 归到后者会把排查引向设备能力不足
            allCandidatesUnrealizable ->
                "直出候选的格式建不起音频轨" to
                    "USB 设备 $label 上构造出的直出候选格式均已实测建不起音频轨（偏好已撤销并记为不可用），" +
                    "直出未生效，本条曲目解码输出 ${describeDecodedFormat()}"
            else ->
                "设备未提供可承载当前格式的混音器条目" to
                    "USB 设备 $label 的动态混音端口无可承载当前格式的条目，直出未生效，" +
                    "本条曲目解码输出 ${describeDecodedFormat()}"
        }
    }

    /**
     * 依次下发候选，返回首个被系统受理的那条；都未受理时为 null。
     *
     * 逐档回落：源格式候选被拒后仍按设备声明的条目再试，声明里有可承载条目时直出照样成立。
     */
    private fun applyDirectMixer(
        device: AudioDeviceInfo,
        candidates: List<AudioMixerAttributes>,
    ): AudioMixerAttributes? {
        for (candidate in candidates) {
            applyMixerAttributes(device, candidate)?.let { return it }
        }
        return null
    }

    /**
     * 下发单条候选，返回已被系统受理的那一条；未受理时为 null。
     *
     * 挑出的是默认行为条目时，先按同一格式试一次位完美，被拒再下发它本身。位完美与否由平台按设备声明的
     * 行为应答（getSupportedMixerAttributes 给出的即「可用的集合」），而国产厂商鲜少在动态混音端口上声明
     * AUDIO_OUTPUT_FLAG_BIT_PERFECT——应用无从替厂商声明，只能试：试的成本是可能被拒的一次下发，被拒不建立
     * 任何东西；一旦受理就是真的位完美（数据不经混音直达 HAL），被拒则退回今天的成色（源格式直出）。
     * 两条路的写出格式相同，音频输出侧不必区分。
     *
     * 试探**本次连接只做一次**（见 [bitPerfectRejected]）：行为由 profile 的标志反推，被拒一次即说明这台
     * 设备上没有可声明的位完美档位，此后每个候选都再试一次纯属重复。日志里因此只有一条试探结论，
     * 而不是每个候选一条。
     *
     * 绝不用「视为声明了位完美」冒充成色：默认行为的流仍经混音，只是采样率与源一致；
     * 把这种流报成位完美，正是「以已直出之名走混音路径」的翻版。
     */
    private fun applyMixerAttributes(
        device: AudioDeviceInfo,
        mixerAttributes: AudioMixerAttributes,
    ): AudioMixerAttributes? {
        if (mixerAttributes.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
            return mixerAttributes.takeIf { requestMixerAttributes(device, it) }
        }
        if (!bitPerfectRejected) {
            val bitPerfect = AudioMixerAttributes.Builder(mixerAttributes.format)
                .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                .build()
            // 试探的两种结局都在此表述：被拒是预期结论而非异常，故直接下发而不走 [requestMixerAttributes]
            // 的通用拒绝留痕，免得日志里只看到「拒绝首选混音器属性：…位完美…」，读起来像连默认条目也没下发成
            if (postMixerAttributes(device, bitPerfect)) {
                logDiagnostic("位完美请求已被受理（厂商未在端口声明该行为）：${describeMixer(bitPerfect)}")
                return bitPerfect
            }
            bitPerfectRejected = true
            logDiagnostic(
                "位完美请求被拒（厂商未在端口声明该行为），本次连接不再试探：${describeMixer(bitPerfect)}"
            )
        }
        return mixerAttributes.takeIf { requestMixerAttributes(device, it) }
    }

    /**
     * 下发一条混音器属性，返回系统是否受理；未受理时不会建立专用输出流，播放走默认混音。
     *
     * 本函数不改受理状态，由调用方按返回值经 [updateRouting] 落定：未受理的取值不进
     * [acceptedMixerAttributes]。被拒可能只是当时的现场使然（设备正被别的输出占着、上一条属性留下的
     * 专用流尚未释放），记成「已下发」会让同一条属性在之后的换曲里再不被重试，直出因此再也回不来；
     * 重试的代价只是重复下发一次，重复的结论由 [logDiagnostic] 去重。
     */
    private fun requestMixerAttributes(
        device: AudioDeviceInfo,
        mixerAttributes: AudioMixerAttributes,
    ): Boolean {
        if (postMixerAttributes(device, mixerAttributes)) return true
        logDiagnostic(
            "USB 输出拒绝首选混音器属性：${deviceLabel(device)}，${describeMixer(mixerAttributes)}"
        )
        return false
    }

    // 裸下发：同一次试探的成败常要按语境合起来表述，故留痕交给调用方
    private fun postMixerAttributes(
        device: AudioDeviceInfo,
        mixerAttributes: AudioMixerAttributes,
    ): Boolean = audioManager.setPreferredMixerAttributes(playbackAttributes, device, mixerAttributes)

    /** 撤销直出配置并解除路由钉定；[reason] 是本次撤销的原因，仅用于日志留痕 */
    private fun releaseConfiguration(reason: String) {
        val device = targetDevice ?: return
        // 拔出时 APM 已在断连路径内清除该端口的偏好，此处 clear 会返回 NAME_NOT_FOUND；
        // 属性归属 uid 不符时返回 PERMISSION_DENIED。两者都无需处理
        runCatching { audioManager.clearPreferredMixerAttributes(playbackAttributes, device) }
        pinPreferredDevice(null)
        logDiagnostic("已解除 USB 直出（$reason）：${deviceLabel(device)}")
        updateRouting(null, null)
    }

    /**
     * 直出状态的唯一出口：记录钉定设备与已受理的属性，据此推出成色对外通知。
     *
     * 赋值与通知同处一处，内部状态与上报值才不会脱节；成色变化即写日志——成色是「设备是否可用、
     * 厂商是否声明位完美、格式能否对齐、属性是否被受理」共同作用的结论，变化点正是定位问题的入口。
     */
    private fun updateRouting(device: AudioDeviceInfo?, attributes: AudioMixerAttributes?) {
        targetDevice = device
        acceptedMixerAttributes = attributes
        val mode = modeOf(device, attributes)
        if (mode == reportedMode) return
        reportedMode = mode
        logDiagnostic(
            when {
                device == null || attributes == null -> "输出成色：系统混音"
                mode == AudioOutputMode.BIT_PERFECT ->
                    "输出成色：位完美直出，${deviceLabel(device)}，${describeMixer(attributes)}"
                else ->
                    "输出成色：源格式直出，" +
                        "${deviceLabel(device)}，${describeMixer(attributes)}"
            }
        )
        onRoutingChanged?.invoke(mode)
    }

    // 成色由已受理的属性行为决定：未钉定设备或属性未被受理时都退回系统混音
    private fun modeOf(device: AudioDeviceInfo?, attributes: AudioMixerAttributes?): AudioOutputMode =
        when {
            device == null || attributes == null -> AudioOutputMode.MIXER
            attributes.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT ->
                AudioOutputMode.BIT_PERFECT
            else -> AudioOutputMode.FORMAT_LOCKED
        }

    /**
     * 写一条诊断日志。
     *
     * 同一结论只落盘一次：路由重算由设备回调与轨道回调共同驱动，重复条目会把「变化点」淹没，
     * 而定位问题靠的正是变化点。结论变化时照常记录。
     */
    private fun logDiagnostic(message: String) {
        if (message == lastDiagnostic) return
        lastDiagnostic = message
        CrashLogManager.logInfo(LOG_TAG, message)
    }

    // 设备名：平台未上报名称时退回设备编号，保证同类设备的多个实例在日志中仍可区分
    private fun deviceLabel(device: AudioDeviceInfo): String =
        device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "id=${device.id}"

    // 当前解码格式：混音器属性需与之逐字段一致，三项都写出来才能看出匹配失败究竟卡在哪一项
    private fun describeDecodedFormat(): String =
        if (decodedSampleRate > 0) {
            "${decodedSampleRate}Hz/${decodedChannelCount}ch/${encodingName(decodedPcmEncoding)}"
        } else {
            "未取得（尚未起播）"
        }

    private fun describeMixer(attributes: AudioMixerAttributes): String =
        "${behaviorName(attributes.mixerBehavior)} " +
            "${attributes.format.sampleRate}Hz/${attributes.format.channelCount}ch/" +
            encodingName(attributes.format.encoding)

    private fun behaviorName(behavior: Int): String = when (behavior) {
        AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT -> "位完美"
        AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT -> "默认混音"
        else -> "行为$behavior"
    }

    private fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> "8位整型"
        AudioFormat.ENCODING_PCM_16BIT -> "16位整型"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24位整型"
        AudioFormat.ENCODING_PCM_32BIT -> "32位整型"
        AudioFormat.ENCODING_PCM_FLOAT -> "32位浮点"
        else -> "编码$encoding"
    }

    private fun findUsbOutputDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && it.type in USB_OUTPUT_TYPES }

    /**
     * 读取设备支持的混音器属性。
     *
     * 读取抛出按「无条目」处理——直出无从成立，播放退回系统混音；异常本身写入日志而不静默吞掉，
     * 否则日志里只剩「未取到动态混音端口」这一句，把读取异常误读成设备能力不足。
     * 返回空表另有一层含义：设备当前没有输出时平台不作应答，故空表只当「这次问不到」，
     * 在问到非空结果之前要一直重问（见 [declaredFormatsFor]）。
     */
    private fun supportedMixerAttributes(device: AudioDeviceInfo): List<AudioMixerAttributes> =
        runCatching { audioManager.getSupportedMixerAttributes(device) }
            .onFailure {
                CrashLogManager.logException(
                    LOG_TAG,
                    "读取设备支持的混音器属性失败：${deviceLabel(device)}",
                    it,
                )
            }
            .getOrDefault(emptyList())

    /**
     * 本次连接的设备声明档位：只问一次，问到即止。
     *
     * 声明是设备的固定属性，问到之后答案不再变化，重复查问没有意义（实测一次会话里每次路由重算都带着一次
     * 重量查询）。但**空表不是答案**——平台的应答取决于本应用此刻在该设备上有没有输出，未起播时必得空表
     * （见 [supportedMixerAttributes]），故在问到之前仍要问；问到一次非空的即认定本次连接的答案已定。
     * 换设备即作废（按设备编号判归属）。
     */
    private fun declaredFormatsFor(device: AudioDeviceInfo?): List<AudioMixerAttributes> {
        if (device == null) return emptyList()
        if (declaredFormatsDeviceId == device.id && declaredFormats.isNotEmpty()) return declaredFormats
        val queried = supportedMixerAttributes(device)
        if (queried.isEmpty()) return emptyList()
        declaredFormats = queried
        declaredFormatsDeviceId = device.id
        return queried
    }

    /**
     * 直出候选：只取设备声明的动态混音端口条目，一条。
     *
     * **不构造设备未声明的格式**——不按源位深与源采样率直接拼一条去试探。设备声明哪些档位就按哪些直出：
     * 声明的档位就是设备确实支持的那几档，未声明的参数（位深、采样率）试探一次只换来一次被拒、或一条
     * 建得起却写不出的轨道（实测：96000Hz/24 位被受理后写返回 ERROR_INVALID_OPERATION）。真正的判据
     * 始终在真实链路上（见 [reportUnrealizableFormat]），事前拼格式并不能替代它。
     *
     * 声明条目一空即返回空表：空表说明这次问不到（厂商未声明，或设备当前没有输出，见 [declaredFormatsFor]），
     * 此时没有可用档位，播放交回系统混音。
     */
    private fun directMixerCandidates(
        supported: List<AudioMixerAttributes>,
    ): List<AudioMixerAttributes> =
        listOfNotNull(selectDirectMixer(supported, decodedSampleRate, decodedChannelCount, decodedPcmEncoding))

    private fun registerCallback() {
        if (callbackRegistered) return
        callbackRegistered = true
        audioManager.registerAudioDeviceCallback(deviceCallback, audioDeviceHandler)
    }

    private fun unregisterCallback() {
        if (!callbackRegistered) return
        callbackRegistered = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }
}

/**
 * 从设备支持的混音器属性中挑出可承载解码格式的直出条目，无可用条目时返回 null。
 *
 * 这是直出的**唯一**档位来源：设备声明了什么就按什么直出，不按源位深与源采样率另拼格式（见
 * [UsbDirectOutput.directMixerCandidates]）。
 *
 * 专用输出流只接纳与混音器属性逐字段一致的播放——采样率、声道与编码任一不符，播放都不会挂到该流上，
 * 因此候选严格按这三项筛定，不做「挑最接近条目」的退让：挂不上的条目只会让播放静默落回混音路径，
 * 却让调用方以为直出已经成立。编码一侧的候选取自 [writablePcmEncodings]，即播放器确实写得出的编码。
 *
 * [decodedPcmEncoding] 必须是解码头实际输出的线性 PCM 编码，调用方各自负责把手上的格式换算到这一项。
 * 压缩源在解码前无从得知它——容器格式只给采样率与声道，pcmEncoding 仍是 NO_VALUE——故此处不为未知编码
 * 兜底：以未知编码推出的可写集合里凭空多出 16 位与浮点，挑出的条目与真正写出的编码未必一致，
 * 而两处调用点一旦挑出不同条目，AudioFlinger 不报错而是静默混音输出，「已直出」名不副实。
 * 不是线性 PCM（未取得编码、直通等）即无从判定，直接交回系统混音。
 *
 * 候选按成色取用：优先厂商声明了 AUDIO_OUTPUT_FLAG_BIT_PERFECT 的条目；无位完美条目时退取同一动态
 * 端口上的默认行为条目。后者是为厂商漏标该标志准备——平台的混音行为枚举对每个动态输出端口恒有一条
 * 默认行为条目，只有声明了标志才额外多出一条位完美条目，故漏标并不等于设备做不到按该格式直出。
 * 选出默认行为条目只说明声明里没有位完美，下发时仍会按同一格式试一次位完美（见 [applyMixerAttributes]）。
 * 两档都不存在时返回 null，由调用方交回系统混音。
 *
 * 同成色内按编码排序，位深优先——见 [encodingPreference]：解码输出通常为浮点，写到 16 位档位即丢低位，
 * 故位深高的档位排在前，浮点最优先。
 *
 * 本函数产出的是直出档位；音频输出不再自行挑条目，而是直接取已受理的属性格式来选写出变体
 * （[UsbDirectOutput.directOutputEncoding]）。写出编码与已受理的属性不符时 AudioFlinger 不报错
 * 而是静默混音输出，「已直出」名不副实，故两处只以「系统实际受理的那条属性」为唯一结论。
 */
@OptIn(UnstableApi::class)
internal fun selectDirectMixer(
    supported: List<AudioMixerAttributes>,
    sampleRate: Int,
    channelCount: Int,
    decodedPcmEncoding: Int,
): AudioMixerAttributes? {
    if (sampleRate <= 0) return null
    if (!Util.isEncodingLinearPcm(decodedPcmEncoding)) return null
    val writable = writablePcmEncodings(decodedPcmEncoding)
    val candidates = supported.filter {
        it.format.sampleRate == sampleRate &&
            it.format.encoding in writable &&
            (channelCount <= 0 || it.format.channelCount == channelCount)
    }
    val bitPerfect = candidates.filter {
        it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
    }
    // 位完美条目缺失即退取默认行为条目：厂商可能只在动态混音端口上漏标了位完美标志，
    // 该端口仍会按所请求的格式打开输出流，播放格式对齐即不发生重采样
    val pool = bitPerfect.ifEmpty {
        candidates.filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT }
    }
    return pool.maxByOrNull {
        encodingPreference(it.format.encoding)
    }
}

/**
 * 音频输出能写出的 PCM 编码。
 *
 * 浮点与 16 位整型由媒体3 的默认输出产出：浮点变体下写浮点，非浮点变体上经 `ToInt16PcmAudioProcessor`
 * 转成 16 位整型。浮点因此不由源位深决定——渲染器配置解码器时按接收器的能力基准索取浮点输出，
 * [decodedPcmEncoding] 通常就是浮点；保留 [Util.isEncodingHighResolutionPcm] 这一关，是为解码器未照做、
 * 仍按 16 位整型输出时不再放宽可写集合。
 * 打包整型由 [IntPcmAudioSink] 写出，其中 32 位容得下任意线性源——32 位及以下源左移补零后逐位无损，
 * 浮点源按中间态取整；24 位则丢 32 位源的低八位，故源本就是 32 位时不列入。
 *
 * 这里只列「本应用写得出的编码」，不掺「本机是否建得起轨」：后者不再事前实测（见 docs/注意事项.md），
 * 由音频输出在真实建轨失败时降级兜底。
 *
 * [decodedPcmEncoding] 取解码头实际输出的线性 PCM 编码：未取得编码时的取值会让「高分辨率」与
 * 「32 位」两项判定都失去依据，凭空放宽可写集合，故调用方须先换算到真实 PCM。
 */
@OptIn(UnstableApi::class)
internal fun writablePcmEncodings(decodedPcmEncoding: Int): Set<Int> {
    val writable = mutableSetOf(
        AudioFormat.ENCODING_PCM_16BIT,
        AudioFormat.ENCODING_PCM_24BIT_PACKED,
        AudioFormat.ENCODING_PCM_32BIT,
    )
    if (Util.isEncodingHighResolutionPcm(decodedPcmEncoding)) {
        writable += AudioFormat.ENCODING_PCM_FLOAT
    }
    if (decodedPcmEncoding == AudioFormat.ENCODING_PCM_32BIT) {
        writable -= AudioFormat.ENCODING_PCM_24BIT_PACKED
    }
    return writable
}

/**
 * 同为可写编码时的优先序：取容量够的那一档，浮点最优先。
 *
 * 解码输出按接收器的能力基准索取，通常就是浮点，故位深直接决定精度：不能像从前那样让 16 位整型无条件
 * 排在最前——那是「源格式候选被拒后只剩回落档位」时代的取舍。现在档位一律取自设备声明，16 位档位就是
 * 写出位深本身，写到它即丢低位。
 * 打包整型内 32 位高于 24 位：32 位容得下 32 位及以下的一切源，24 位放不下 32 位源的低八位。
 */
private fun encodingPreference(encoding: Int): Int = when (encoding) {
    AudioFormat.ENCODING_PCM_FLOAT -> 4
    AudioFormat.ENCODING_PCM_32BIT -> 3
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 2
    else -> 1
}
