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

// USB 解码器会以设备、耳机、配件三类上报，三者都是可直接播放的输出目标。
// 与授权请求（UsbAccessRequest）共用同一判据：两处各列一份，迟早会漏掉其中一类
internal val USB_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
)

// 异常日志的类名前缀：直出链路只有出错时才记（正常的路由决策不写日志）
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
 * 只申请**默认行为**档：专用输出流照开、按源格式、不重采样，但不向平台索要位完美（`MIXER_BEHAVIOR_BIT_PERFECT`）。
 * 位完美那一档本应用一律不用，两条理由都在原生源码里（frameworks/av，见 docs/注意事项.md）：
 * - 拿不到：`setPreferredMixerAttributes` 把所请求的行为并进用于匹配的输出 flags，`IOProfile::getFlagsCompatibleScore`
 *   要求「请求的每个 flag 都在 profile 声明里」，厂商没在动态端口上写 `AUDIO_OUTPUT_FLAG_BIT_PERFECT` 就必然 BAD_VALUE
 *   ——应用无从替厂商声明，试也白试；
 * - 拿到了更差：`getOutputForAttrInt` 里对**别的 uid** 保留偏好条目当且仅当「偏好是位完美且我们自己有活动客户端」，
 *   也就是说位完美档会把别人的媒体轨吸到同一条输出上；而 `BitPerfectThread::getTrackToStreamBitPerfectly_l`
 *   只有在「除位完美轨外所有活动轨都被静音」时才旁路混音，别人一放就静默退回混音——位完美失效，且必然吃一次重配顿挫。
 * 默认行为档下别人的轨进不来（同一处 uid 闸门），我们这条输出保持单客户端。
 *
 * 档位只取自**设备声明的动态混音端口条目**，且本次连接只读取一次（见 [declaredFormatsFor]）；不按源位深
 * 与源采样率另拼一条去试探未声明的参数——试探只换来一次被拒或一条写不出的轨道，而设备侧真正支持的档位
 * 恰在声明里。档位按优先级排成两条：与源同率的在先、同族整数倍的在后（见 [directMixerCandidates]）；
 * 两条都挑不出即交回系统混音；档位建不起或写不出再由音频输出逐档降级（见 [reportUnrealizableFormat]）。
 *
 * 同族倍率那条是为「不声明源采样率、却声明了同族倍率」的设备留的出口（如只声明 88.2 而不声明 44.1）：
 * 交回系统混音会被重采样到设备当时那档采样率（通常 48k，跨族），而按同族倍率直出只需本应用把数据按
 * 整数倍补齐，就能挂在设备确实声明的档位上——同族时钟、不经系统重采样器（见 [SameFamilyUpsampler]）。
 * **倍率那一份数据必须由本应用补齐**：只把偏好设成倍率档而仍写源采样率的帧，三字段不符，轨挂不上
 * 专用输出流，系统照样重采样，而界面仍显示已直出。
 *
 * 原生行为（已核实）：
 * - 受理条件：APM 要求 usage 为 USAGE_MEDIA、设备为已接入的 USB 输出，且存在与目标格式、采样率、
 *   声道及各行为兼容的动态输出 profile；标志由行为反推——位完美行为对应 AUDIO_OUTPUT_FLAG_BIT_PERFECT，
 *   默认行为不附加标志。任一条件不满足即返回 BAD_VALUE，此处体现为 set 返回 false；
 *   缺少 MODIFY_AUDIO_SETTINGS 则为 PERMISSION_DENIED。
 * - 行为枚举：对每个支持该设备的动态输出 profile，恒有一条默认行为条目；只有 profile 声明了位完美标志
 *   才额外多出一条位完美条目（IOProfile::refreshMixerBehaviors）——故只取默认行为条目不会挑不出档位。
 * - 拔出：APM 在断连的同一路径内直接清除该端口的偏好且不回调，故只能经 AudioDeviceCallback 感知。
 * - 端口查询：getSupportedMixerAttributes 直查音频策略，Java 层把任何非 SUCCESS 一律折成空表。其原生实现在
 *   设备在场且是 USB 输出设备时，枚举各 HAL 模块中支持该设备、且带动态 profile 的输出档位；那些档位要到该设备上
 *   开过一次输出、由 `updateAudioProfiles` 把 HAL 实报的档位 import 进 profile 之后才是完整的一份（见 [SilentKeepAliveOutput]），
 *   故空表不作终局结论——问到非空为止（见 [declaredFormatsFor]）。
 * - 格式不符：写出格式与偏好混音器不一致时，AudioFlinger 不会失败，而是把该轨静默混音输出，
 *   因此输出格式必须与偏好对齐，才不会以「已直出」之名走混音路径。路由在曲中才成立时，写出变体必须
 *   跟着重配（见 [PerDeviceAudioSink.syncRouting]），否则状态与实际写出会各说一套。
 *
 * 直出能否成立取决于设备接入与厂商声明，判定依据只在设备现场可得，故开关状态与每次路由重算
 * 的结论由音频信息面板直接展示：设备识别、格式是否能对齐、路由是否钉定，都在那里逐项可见。
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
    // 路由重算由主线程（开关、设备插拔）与播放线程（解码格式上报、音频轨建成）两处驱动，下列字段因此
    // 被两线程读写：取值都按音频属性的现场状态重新求出，重复求值无害，但跨线程可见性必须显式给出，
    // 否则播放线程可能读到旧值——按旧值建出的音频轨与生效的偏好不符时 AudioFlinger 静默混音，
    // 界面却仍显示直出。
    @Volatile
    private var enabled = false
    private var callbackRegistered = false
    /** 已钉定的 USB 输出设备，null 表示当前未钉定路由 */
    @Volatile
    private var targetDevice: AudioDeviceInfo? = null
    /**
     * 已被系统受理的混音器属性，null 表示未建立专用输出流。
     *
     * 一处状态管两件事：既是输出成色的依据（取值被拒时播放仍走系统混音，据此判定才不会以「已直出」
     * 之名走混音路径），也是抑制重复下发的判据（重复下发会让框架重开输出流，故已受理的取值在变化前
     * 不再下发）。两者必须同一份——把「下发过」与「已受理」分开记，被拒的取值就会挡住下次重试。
     *
     * 取值以平台的读回值为准而非「set 返回过 true」：偏好会被 audioserver 重启、厂商策略变更等外部原因
     * 清除，而本地记录不会随之改变（见 [readAcceptedMixerAttributes]）。
     */
    @Volatile
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
    @Volatile
    private var unrealizableFormatsDeviceId: Int? = null
    /**
     * 本次连接问到的设备声明档位，及其所属设备编号。
     *
     * 声明是设备的固定属性，问到一次即认定本次连接的答案已定（见 [declaredFormatsFor]），故与设备编号一并记。
     */
    @Volatile
    private var declaredFormats: List<AudioMixerAttributes> = emptyList()

    @Volatile
    private var declaredFormatsDeviceId: Int? = null
    /** 已对外上报的输出成色，与 [onRoutingChanged] 的出参同处一处，避免内部状态与上报值脱节 */
    @Volatile
    private var reportedMode = AudioOutputMode.MIXER
    /** 当前曲目的解码格式，混音器属性需与之逐字段（采样率、声道、编码）匹配才能被直出流接纳 */
    @Volatile
    private var decodedSampleRate = 0

    @Volatile
    private var decodedChannelCount = 0

    @Volatile
    private var decodedPcmEncoding = 0

    /** 输出成色变更回调：[AudioOutputMode.MIXER] 表示已回到系统混音输出 */
    var onRoutingChanged: ((AudioOutputMode) -> Unit)? = null

    private val audioDeviceHandler = Handler(Looper.getMainLooper())

    /**
     * 直出期间的静音保活输出：设备上恒保有一路本应用的输出，使动态混音端口查询不至于永远得空表。
     * 只在今天会丢失直出的状态下建立（见 [refreshOutputRouting]），因而不会改变有输出时的既有行为。
     */
    private val keepAlive = SilentKeepAliveOutput(audioManager)

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
        if (value) {
            registerCallback()
            refreshOutputRouting()
        } else {
            releaseConfiguration()
            keepAlive.release()
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
     * 直出写出率相对源采样率的倍率，未直出、或按源格式直出时为 1。
     *
     * 与 [directOutputEncoding] 同取一份已受理的属性：倍率只由「已受理的采样率」与「当前解码采样率」
     * 决定，不看设备声明——改挂之后声明里那两条的次序不说明此刻生效的是哪一条，只有已受理的取值能。
     * 音频输出据此把数据插值放大并按倍率开轨，使写出率与混音器属性逐字段一致（见 [PerDeviceAudioSink]）；
     * 数据补不到该倍率，AudioFlinger 不报错而是把该轨静默混音，「已直出」名不副实——故倍率与编码
     * 必须出自同一处。
     */
    fun directOutputRateMultiple(): Int =
        acceptedMixerAttributes
            ?.let { sameFamilyMultiple(decodedSampleRate, it.format.sampleRate) }
            ?: 1

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
        if (!applyNextDirectCandidate()) {
            releaseConfiguration()
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
        // 解码格式改由音频信息面板的「解码输出」一行展示
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
        releaseConfiguration()
        keepAlive.release()
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
        }
        // 保活输出随设备走：设备换了或拔了先释放，下面的空表分支再按需建立
        if (keepAlive.heldDeviceId != device?.id) keepAlive.release()
        // 设备声明的动态混音端口条目：直出档位一律取自这里，不再按源格式另拼候选（见 [directMixerCandidates]）
        var supported = declaredFormatsFor(device)
        // 空表不是终局结论：平台的应答取决于本应用此刻在该设备上有没有输出。故空表时先保有一路静音输出
        // 再问第二次——这正是「撤销后新起的输出不再落在该设备上、查询从此永远得空表」那条死结的出口。
        // 只在这一状态下保活，有输出时的既有行为因此不被改变（保活的格式另见 [SilentKeepAliveOutput]）
        if (device != null && supported.isEmpty()) {
            keepAlive.hold(device)
            supported = declaredFormatsFor(device)
            if (supported.isNotEmpty()) {
            }
        }
        // 保活轨与设备声明的档位同名时会被平台的「编码、声道掩码、采样率三项一致即挂上偏好流」吸入直出流，
        // 而直出流多出一条音轨就不再旁路混音；此时放弃保活，宁可退回今天的行为
        if (keepAlive.format != null && supported.any { it.format == keepAlive.format }) {
            keepAlive.release()
        }
        // 本地记录只在平台侧仍持有该偏好时才算数：偏好会被 audioserver 重启、厂商策略变更等外部原因清除，
        // 而本地记录不会随之改变，此后仍会指示按直出格式建轨——该格式此刻已无专用输出流承载，
        // AudioFlinger 不报错而是静默混音，界面却仍显示直出。作废记录后，下面的判定会照常重新下发。
        if (device != null && acceptedMixerAttributes != null &&
            readAcceptedMixerAttributes(device) == null
        ) {
            acceptedMixerAttributes = null
        }
        val built = directMixerCandidates(supported)
        // 已被实测证伪建不出音频轨的格式不再入选：再选只会再失败一次（见 [reportUnrealizableFormat]）
        val candidates = built.filterNot { it.format in unrealizableFormats }
        // 直出无从成立：撤销配置，交回系统混音
        if (device == null || candidates.isEmpty()) {
            releaseConfiguration()
            return
        }
        if (device != targetDevice) {
            releaseConfiguration()
            // 属性先于路由下发：播放改道到该设备时，才按已配置的属性建立专用输出流
            val accepted = applyDirectMixer(device, candidates)
            pinPreferredDevice(device)
            updateRouting(device, accepted)
            return
        }
        // 已钉定同一设备时，只有「当前候选里已有格式被系统受理」才不再下发：未受理、专用输出流已被撤销、
        // 换了格式，三者都表现为已受理格式不在本轮候选内，据此判定才不会把「上次被拒」当成「正在直出」。
        // 按格式而非整体比较：受理的条目与本轮候选只是同一档位的两份实例，逐字段相等即视为无需重下发。
        // 以「任一候选」而非「首个候选」为准：声明里的多条可承载同一格式时，重下发只会白开一次输出流
        val acceptedFormat = acceptedMixerAttributes?.format
        if (acceptedFormat != null && candidates.any { it.format == acceptedFormat }) return
        updateRouting(device, applyDirectMixer(device, candidates))
    }

    /**
     * 依次下发候选，返回首个被系统受理的那条；都未受理时为 null。
     *
     * 逐档回落：首选条目被拒后仍按设备声明的其它条目再试，声明里有可承载条目时直出照样成立。
     *
     * 未受理的取值不进 [acceptedMixerAttributes]（落定在调用方）：被拒可能只是当时的现场使然（设备正被
     * 别的输出占着、上一条属性留下的专用流尚未释放），记成「已下发」会让同一条属性在之后的换曲里再不被
     * 重试，直出因此再也回不来；重试的代价只是重复下发一次。
     */
    private fun applyDirectMixer(
        device: AudioDeviceInfo,
        candidates: List<AudioMixerAttributes>,
    ): AudioMixerAttributes? {
        for (candidate in candidates) {
            if (postMixerAttributes(device, candidate)) return candidate
        }
        return null
    }

    /** 裸下发；成败的表述交给调用方 */
    private fun postMixerAttributes(
        device: AudioDeviceInfo,
        mixerAttributes: AudioMixerAttributes,
    ): Boolean = audioManager.setPreferredMixerAttributes(playbackAttributes, device, mixerAttributes)

    /**
     * 读回平台此刻仍持有的偏好混音器属性，null 表示系统侧已不再持有。
     *
     * 判据取读回值而非「[postMixerAttributes] 曾返回 true」：偏好由系统按其自身的生命周期管理，
     * audioserver 重启、厂商策略变更、客户端死亡处置都会把它清掉，而本地记录不会随之改变。
     * 本地记录一旦与实际不符，音频轨仍按直出格式建起，却不再有专用输出流承载——AudioFlinger 不报错
     * 而是静默混音，成色照旧显示直出。读回是一次按属性的查询，代价与一次下发相当，故可在每次路由重算时取。
     */
    private fun readAcceptedMixerAttributes(device: AudioDeviceInfo): AudioMixerAttributes? =
        runCatching { audioManager.getPreferredMixerAttributes(playbackAttributes, device) }
            .onFailure {
                CrashLogManager.logException(
                    LOG_TAG,
                    "读回偏好混音器属性失败：${deviceLabel(device)}",
                    it,
                )
            }
            .getOrNull()

    /** 撤销直出配置并解除路由钉定 */
    private fun releaseConfiguration() {
        val device = targetDevice ?: return
        // 拔出时 APM 已在断连路径内清除该端口的偏好，此处 clear 会返回 NAME_NOT_FOUND；
        // 属性归属 uid 不符时返回 PERMISSION_DENIED。两者都无需处理
        runCatching { audioManager.clearPreferredMixerAttributes(playbackAttributes, device) }
        pinPreferredDevice(null)
        updateRouting(null, null)
    }

    /**
     * 直出状态的唯一出口：记录钉定设备与已受理的属性，据此推出成色对外通知。
     *
     * 赋值与通知同处一处，内部状态与上报值才不会脱节。
     */
    private fun updateRouting(device: AudioDeviceInfo?, attributes: AudioMixerAttributes?) {
        targetDevice = device
        acceptedMixerAttributes = attributes
        val mode = modeOf(device, attributes)
        if (mode == reportedMode) return
        reportedMode = mode
        onRoutingChanged?.invoke(mode)
    }

    // 成色由已受理的属性决定：未钉定设备或属性未被受理时退回系统混音，其余即按源格式直出
    private fun modeOf(device: AudioDeviceInfo?, attributes: AudioMixerAttributes?): AudioOutputMode =
        if (device == null || attributes == null) AudioOutputMode.MIXER else AudioOutputMode.FORMAT_LOCKED

    // 设备名：平台未上报名称时退回设备编号，保证同类设备的多个实例在日志中仍可区分
    private fun deviceLabel(device: AudioDeviceInfo): String =
        device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "id=${device.id}"

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
     * 直出候选：设备声明的动态混音端口条目，按优先级排成两条，前者不成立时取后者。
     *
     * **不构造设备声明的格式之外的东西**——不按源位深与源采样率直接拼一条去试探。设备声明哪些档位就按哪些
     * 直出：声明的档位就是设备确实支持的那几档，未声明的参数（位深、采样率）试探一次只换来一次被拒、
     * 或一条建得起却写不出的轨道（实测：96000Hz/24 位被受理后写返回 ERROR_INVALID_OPERATION）。
     * 真正的判据始终在真实链路上（见 [reportUnrealizableFormat]），事前拼格式并不能替代它。
     *
     * 两条的次序与各自的取舍：
     * - 首选**与源同率**的那条：写出率与源一致、数据一字不动，这是直出的本义（见 [selectDirectMixer]）；
     * - 同率档缺席时退到**同族整数倍**那条：设备不声明 44.1 却声明 88.2 时，交回系统混音会被重采样到
     *   设备当时那档采样率（通常是 48k，**跨族**）；而按同族倍率直出只需本应用把数据按整数倍补齐
     *   （见 [SameFamilyUpsampler]）——同族时钟、不经系统重采样器，代价是多了插值样本。
     *
     * 两条都只是候选：声明里挑不出（或倍率档的编码本应用写不出，见 [canInterpolatePcm]）即返回空表，
     * 播放交回系统混音。
     */
    private fun directMixerCandidates(
        supported: List<AudioMixerAttributes>,
    ): List<AudioMixerAttributes> =
        listOfNotNull(
            selectDirectMixer(supported, decodedSampleRate, decodedChannelCount, decodedPcmEncoding),
            selectSameFamilyUpsample(
                supported,
                decodedSampleRate,
                decodedChannelCount,
                decodedPcmEncoding,
            ),
        )

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
 * 从设备支持的混音器属性中挑出**与解码格式同率**的直出条目，无可用条目时返回 null。
 *
 * 与 [selectSameFamilyUpsample] 合起来构成直出的全部档位来源，两者都只从设备**声明**的条目里挑，
 * 不按源位深与源采样率另拼格式（见 [UsbDirectOutput.directMixerCandidates]）。本函数是首选那一条：
 * 写出率与源一致，数据一字不动。
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
 * **只取默认行为条目**，不取厂商声明的位完美条目，理由见类注释：位完美档会把别的应用的媒体轨吸到同一条
 * 输出上、并把该输出的旁路混音打掉，代价大于收益。平台的混音行为枚举对每个动态输出端口恒有一条默认行为
 * 条目（只有 profile 声明了 AUDIO_OUTPUT_FLAG_BIT_PERFECT 才额外多出一条位完美条目），故只取默认条目不
 * 会挑不出档位。一条都没有时返回 null，由调用方交回系统混音。
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
            (channelCount <= 0 || it.format.channelCount == channelCount) &&
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT
    }
    return candidates.maxByOrNull {
        encodingPreference(it.format.encoding)
    }
}

/**
 * 从设备支持的混音器属性中挑出**同族整数倍**的直出条目，无可用条目时返回 null。
 *
 * 用途：设备不声明源采样率（44.1kHz 落不到任何档位上）却声明了同族倍率（88.2kHz）时，本应用把数据
 * 按整数倍补齐后仍能直出，避免交回系统混音被重采样到跨族的档位（见 [SameFamilyUpsampler] 与
 * [UsbDirectOutput.directMixerCandidates]）。
 *
 * 筛法与 [selectDirectMixer] 逐条一致——声道与编码同筛，只把采样率一项由「相等」放宽为「同族整数倍」。
 * 同率条目不会被本函数挑中（倍率恒 ≥ 2），故两条候选天然互斥、次序不会打架。
 *
 * 倍率取**最小**那一档：多出一倍的样本已是能守住的最近一档，再往上是白添数据与延迟；同倍率内仍按
 * [encodingPreference] 取位深最高的编码。
 *
 * 编码一侧多一道门槛：倍率档的数据要由本应用插值写出，故 [decodedPcmEncoding] 必须是本应用插得动的
 * 线性 PCM（见 [canInterpolatePcm]）。插不出的编码宁可不出现在候选里——挂上了也补不出数据，而写出率
 * 与混音器属性不符时 AudioFlinger 只静默混音，成色却照旧显示已直出。
 */
@OptIn(UnstableApi::class)
internal fun selectSameFamilyUpsample(
    supported: List<AudioMixerAttributes>,
    sampleRate: Int,
    channelCount: Int,
    decodedPcmEncoding: Int,
): AudioMixerAttributes? {
    if (sampleRate <= 0) return null
    if (!Util.isEncodingLinearPcm(decodedPcmEncoding)) return null
    if (!canInterpolatePcm(decodedPcmEncoding)) return null
    val writable = writablePcmEncodings(decodedPcmEncoding)
    return supported
        .filter {
            it.format.encoding in writable &&
                (channelCount <= 0 || it.format.channelCount == channelCount) &&
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT
        }
        .mapNotNull { attributes ->
            sameFamilyMultiple(sampleRate, attributes.format.sampleRate)?.let { it to attributes }
        }
        // 倍率小者优先，同倍率内位深高者优先
        .minWithOrNull(
            compareBy({ it.first }, { -encodingPreference(it.second.format.encoding) })
        )
        ?.second
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
