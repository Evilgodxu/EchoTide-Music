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

// USB 解码器会以设备、耳机、配件三类上报，三者都是可直接播放的输出目标
private val USB_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
)

// 诊断日志的类名前缀：日志文件按「类名: 描述」成条，独占链路的决策结果都记在此名下
private const val LOG_TAG = "UsbExclusiveOutput"

/**
 * USB 独占输出：把播放钉到 USB 解码器，并向原生音频策略申请与该设备格式对齐的专用输出流。
 *
 * 独占由两个原生接口共同构成，缺一不可：
 * - [AudioManager.setPreferredMixerAttributes] 为该设备建立按所请求格式打开的专用输出流；
 * - [ExoPlayer.setPreferredAudioDevice] 把播放器的输出路由固定到同一设备，
 *   使该流成为播放的唯一出口。
 *
 * 专用流只接纳与混音器属性逐字段一致（采样率、声道、编码）的播放，混音器属性因此按当前解码格式挑选，
 * 并在换曲导致格式变化时重新下发。
 *
 * 混音器属性分两档取用，成色随之不同：
 * - 位完美：厂商在动态混音端口上声明了 AUDIO_OUTPUT_FLAG_BIT_PERFECT，音频不经混音、不受音量与音效
 *   处理，数据原样下发到 HAL；
 * - 格式独占：厂商漏标该标志时的兼容结果——仍按源格式请求该端口的输出流，播放格式对齐即不发生重采样，
 *   但音轨音量与音效按常规链路处理。
 *
 * 原生行为（已核实）：
 * - 受理条件：APM 要求 usage 为 USAGE_MEDIA、设备为已接入的 USB 输出，且存在与目标格式、采样率、
 *   声道及各行为兼容的动态输出 profile；标志由行为反推——位完美行为对应 AUDIO_OUTPUT_FLAG_BIT_PERFECT，
 *   默认行为不附加标志，故漏标位完美标志的端口仍能受理默认行为的请求。任一条件不满足即返回 BAD_VALUE，
 *   此处体现为 set 返回 false；缺少 MODIFY_AUDIO_SETTINGS 则为 PERMISSION_DENIED。
 * - 行为枚举：对每个支持该设备的动态输出 profile，恒有一条默认行为条目；只有 profile 声明了位完美标志
 *   才额外多出一条位完美条目（IOProfile::refreshMixerBehaviors）。漏标因此只影响成色，不影响可用性。
 * - 拔出：APM 在断连的同一路径内直接清除该端口的偏好且不回调，故只能经 AudioDeviceCallback 感知。
 * - 格式不符：写出格式与偏好混音器不一致时，AudioFlinger 不会失败，而是把该轨静默混音输出，
 *   因此输出格式必须与偏好对齐，才不会以「已独占」之名走混音路径。
 *
 * 独占能否成立取决于设备接入与厂商声明，判定依据只在设备现场可得，故开关状态、解码格式与每次路由重算
 * 的结论都写入诊断日志（设置页可分享），使「设备已识别而独占未生效」能在日志中定位到具体环节。
 *
 * 线程：独占配置必须在音频轨建立之前下发，而解码格式只有播放线程在音频输出重配那一刻才拿得到，
 * 故 [onTrackFormatChanged] 由播放线程调用；[setEnabled] 与 [release] 由主线程调用——两处的重算
 * 都是拿 [AudioManager] 的现场状态重新求值，落点一致，故不额外加锁。播放器自身仍只受理主线程调用，
 * 涉及它的两处（音频属性、设备钉定）分别以构造期捕获与主线程投递规避，见 [playbackAttributes]
 * 与 [pinPreferredDevice]。
 */
@OptIn(UnstableApi::class)
class UsbExclusiveOutput(
    private val player: ExoPlayer,
    private val audioManager: AudioManager,
) {
    private var enabled = false
    private var callbackRegistered = false
    /** 已钉定的 USB 输出设备，null 表示当前未钉定路由 */
    private var targetDevice: AudioDeviceInfo? = null
    /** 已尝试下发的混音器属性：重复下发会让框架重开输出流，故仅在取值变化时调用 */
    private var appliedMixerAttributes: AudioMixerAttributes? = null
    /**
     * 已被系统受理的混音器属性，null 表示未建立独占输出流。
     *
     * 与 [appliedMixerAttributes] 分开记录：后者含被拒的取值，仅用于抑制重复下发；本项才是独占成色的
     * 依据——取值被拒时播放仍走系统混音，据此判定才不会以「已独占」之名走混音路径。
     */
    private var acceptedMixerAttributes: AudioMixerAttributes? = null
    /** 已对外上报的独占成色，与 [onRoutingChanged] 的出参同处一处，避免内部状态与上报值脱节 */
    private var reportedMode = AudioOutputMode.MIXER
    /** 当前曲目的解码格式，混音器属性需与之逐字段（采样率、声道、编码）匹配才能被独占流接纳 */
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

    /** 独占成色变更回调：[AudioOutputMode.MIXER] 表示已回到系统混音输出 */
    var onRoutingChanged: ((AudioOutputMode) -> Unit)? = null

    private val audioDeviceHandler = Handler(Looper.getMainLooper())

    /**
     * 播放的原生音频属性。
     *
     * 沿用播放器自身的实例而非另建等价属性：原生侧按属性匹配播放记录。读取它需经播放器的主线程校验，
     * 而独占配置会在播放线程上下发，故在构造期（主线程）捕获一次——播放建立后音频属性不再变化。
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
     * 播放器只在应用线程受理这一调用，而独占配置可能在播放线程上下发，故一律投递到主线程；
     * 同一 Handler 串行执行，先后两次投递的次序与下发次序一致。
     */
    private fun pinPreferredDevice(device: AudioDeviceInfo?) {
        audioDeviceHandler.post { player.setPreferredAudioDevice(device) }
    }

    /** 开启或关闭独占；关闭时撤销配置并解除路由钉定，播放回到系统默认混音输出 */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        logDiagnostic(if (value) "独占开关打开，开始接管输出路由" else "独占开关关闭")
        if (value) {
            registerCallback()
            refreshOutputRouting()
        } else {
            releaseConfiguration("开关关闭")
            unregisterCallback()
        }
    }

    /**
     * 已建立独占输出流的 USB 输出设备，null 表示当前未独占；供音频输出按设备选择写出格式。
     *
     * 仅钉定路由而未取得混音器属性时不返回设备——此时播放不挂独占流，写出格式无需与任何条目对齐，
     * 按默认变体写出即可；返回设备会让音频输出按一条不存在的独占流去挑格式。
     */
    fun exclusiveTargetDevice(): AudioDeviceInfo? =
        if (acceptedMixerAttributes != null) targetDevice else null

    /**
     * 解码格式变化（换曲、换源）后记录新格式，独占开启时据此重新挑选混音器属性。
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
        logDiagnostic("解码格式变更：${describeDecodedFormat()}")
        refreshOutputRouting()
    }

    fun release() {
        enabled = false
        releaseConfiguration("独占输出释放")
        unregisterCallback()
    }

    private fun refreshOutputRouting() {
        if (!enabled) return
        val device = findUsbOutputDevice()
        // 设备支持的混音器属性条目本身即诊断依据：条目缺位或格式对不上时独占无从成立，原因全在这一项里
        val supported = device?.let { supportedMixerAttributes(it) }.orEmpty()
        val mixerAttributes = device?.let { pickMixerAttributes(supported) }
        // 无解码器接入，或设备未提供可承载当前格式的动态混音端口：撤销独占配置，交回系统默认混音输出
        if (device == null || mixerAttributes == null) {
            releaseConfiguration(
                if (device == null) "无 USB 输出设备" else "设备未提供可承载当前格式的混音器条目"
            )
            logDiagnostic(
                when {
                    device == null -> "未找到 USB 输出设备，独占未生效，播放走系统混音"
                    supported.isEmpty() ->
                        "USB 设备 ${deviceLabel(device)} 未声明动态混音端口，独占未生效，播放走系统混音；" +
                            "解码格式 ${describeDecodedFormat()}"
                    else ->
                        "USB 设备 ${deviceLabel(device)} 的动态混音端口无可承载当前格式的条目，独占未生效，" +
                            "播放走系统混音；本条曲目解码输出 ${describeDecodedFormat()}，" +
                            "设备支持 ${describeSupported(supported)}"
                }
            )
            return
        }
        if (device != targetDevice) {
            releaseConfiguration("改用其它 USB 输出设备")
            // 属性先于路由下发：播放改道到该设备时，才按已配置的属性建立独占输出流
            val accepted = applyMixerAttributes(device, mixerAttributes)
            pinPreferredDevice(device)
            updateRouting(device, mixerAttributes.takeIf { accepted })
            if (!accepted) {
                logDiagnostic(
                    "USB 输出路由已钉定，但混音器属性未被系统受理，播放仍走系统混音：" +
                        deviceLabel(device)
                )
            }
            return
        }
        if (mixerAttributes != appliedMixerAttributes) {
            val accepted = applyMixerAttributes(device, mixerAttributes)
            updateRouting(device, mixerAttributes.takeIf { accepted })
            if (!accepted) {
                logDiagnostic(
                    "解码格式变化，但重下发的混音器属性未被系统受理，播放仍走系统混音：" +
                        describeMixer(mixerAttributes)
                )
            }
        }
    }

    /** 下发首选混音器属性，返回系统是否受理；未受理时不会建立独占输出流，播放走默认混音 */
    private fun applyMixerAttributes(
        device: AudioDeviceInfo,
        mixerAttributes: AudioMixerAttributes,
    ): Boolean {
        val accepted = audioManager.setPreferredMixerAttributes(
            playbackAttributes,
            device,
            mixerAttributes,
        )
        if (!accepted) {
            // 未受理即属性不合法或设备/配置不受支持，不会建立独占输出流，播放走默认混音；
            // 属性本身已记录，避免每次换曲重试
            CrashLogManager.logException(
                LOG_TAG,
                "USB 输出拒绝首选混音器属性: ${deviceLabel(device)}，${describeMixer(mixerAttributes)}",
            )
        }
        appliedMixerAttributes = mixerAttributes
        return accepted
    }

    /** 撤销独占配置并解除路由钉定；[reason] 是本次撤销的原因，仅用于日志留痕 */
    private fun releaseConfiguration(reason: String) {
        val device = targetDevice ?: return
        // 拔出时 APM 已在断连路径内清除该端口的偏好，此处 clear 会返回 NAME_NOT_FOUND；
        // 属性归属 uid 不符时返回 PERMISSION_DENIED。两者都无需处理
        runCatching { audioManager.clearPreferredMixerAttributes(playbackAttributes, device) }
        pinPreferredDevice(null)
        appliedMixerAttributes = null
        logDiagnostic("已解除 USB 独占（$reason）：${deviceLabel(device)}")
        updateRouting(null, null)
    }

    /**
     * 独占状态的唯一出口：记录钉定设备与已受理的属性，据此推出成色对外通知。
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
                device == null || attributes == null -> "独占成色：系统混音（无已受理的独占输出）"
                mode == AudioOutputMode.BIT_PERFECT ->
                    "独占成色：位完美独占，${deviceLabel(device)}，${describeMixer(attributes)}"
                else ->
                    "独占成色：格式独占（厂商未在该动态端口声明位完美，改按源格式请求输出流），" +
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

    // 条目按动态输出端口逐条上报，同一格式会被多个端口重复声明；去重后只留格式差异，
    // 否则日志里同一行能力项要重复十几次，真正要看的「卡在哪一项」反而被淹没
    private fun describeSupported(supported: List<AudioMixerAttributes>): String =
        if (supported.isEmpty()) {
            "无条目"
        } else {
            supported.map(::describeMixer).distinct().joinToString("；")
        }

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
     * 读取失败按「无条目」处理——独占无从成立，播放退回系统混音；失败本身写入日志而不静默吞掉，
     * 否则日志里只会看到「未提供位完美混音器」，把读取异常误读成设备能力不足。
     */
    private fun supportedMixerAttributes(device: AudioDeviceInfo): List<AudioMixerAttributes> =
        runCatching { audioManager.getSupportedMixerAttributes(device) }
            .onFailure {
                CrashLogManager.logException(
                    LOG_TAG,
                    "读取设备支持的混音器属性失败: ${deviceLabel(device)}",
                    it,
                )
            }
            .getOrDefault(emptyList())

    /**
     * 挑出可承载当前曲目的独占混音器条目。
     * 位完美条目优先，缺失时退取同一动态端口的默认行为条目——厂商漏标位完美标志不等于设备做不到
     * 按源格式打开输出流。解码格式未知（尚未起播）或不是线性 PCM 时不下发，等音频输出上报后重新触发。
     */
    private fun pickMixerAttributes(supported: List<AudioMixerAttributes>): AudioMixerAttributes? =
        selectExclusiveMixer(
            supported,
            decodedSampleRate,
            decodedChannelCount,
            decodedPcmEncoding,
            int24OutputAvailable(),
        )

    // 24 位写出由自研输出实现提供，先确认本机在该格式下能建起 24 位整型轨道，能力不具备时不列入候选
    private fun int24OutputAvailable(): Boolean =
        decodedSampleRate > 0 && decodedChannelCount > 0 &&
            Int24OutputSupport.isSupported(decodedSampleRate, decodedChannelCount)

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
 * 从设备支持的混音器属性中挑出可承载解码格式的独占条目，无可用条目时返回 null。
 *
 * 独占输出流只接纳与混音器属性逐字段一致的播放——采样率、声道与编码任一不符，播放都不会挂到该流上，
 * 因此候选严格按这三项筛定，不做「挑最接近条目」的退让：挂不上的条目只会让播放静默落回混音路径，
 * 却让调用方以为独占已经成立。编码一侧的候选取自 [writablePcmEncodings]，即播放器确实写得出的编码。
 *
 * [decodedPcmEncoding] 必须是解码头实际输出的线性 PCM 编码，调用方各自负责把手上的格式换算到这一项。
 * 压缩源在解码前无从得知它——容器格式只给采样率与声道，pcmEncoding 仍是 NO_VALUE——故此处不为未知编码
 * 兜底：以未知编码推出的可写集合里凭空多出 16 位与 24 位，挑出的条目与真正写出的编码未必一致，
 * 而两处调用点一旦挑出不同条目，AudioFlinger 不报错而是静默混音输出，「已独占」名不副实。
 * 不是线性 PCM（未取得编码、直通等）即无从判定，直接交回系统混音。
 *
 * 候选按成色取用：优先厂商声明了 AUDIO_OUTPUT_FLAG_BIT_PERFECT 的条目；无位完美条目时退取同一动态
 * 端口上的默认行为条目。后者是为厂商漏标该标志准备——平台的混音行为枚举对每个动态输出端口恒有一条
 * 默认行为条目，只有声明了标志才额外多出一条位完美条目，故漏标并不等于设备做不到按源格式直出。
 * 两档都不存在时返回 null，由调用方交回系统混音。
 *
 * 同成色内按编码排序：浮点与 16 位整型由媒体3 的默认输出直接产出，优先取用；24 位整型要经自研输出实现
 * 写出，只在无路可走时才落到它——若排在前列，本可在原线路上直出的设备会被无谓地拉进另一套输出实现。
 *
 * 独占侧据此下发混音器属性，[PerDeviceAudioSink] 据此选择写出变体，两处共用本函数才不会各自跑偏：
 * 一旦写出编码与所下发的条目不符，AudioFlinger 不报错而是静默混音输出，「已独占」名不副实。
 */
@OptIn(UnstableApi::class)
internal fun selectExclusiveMixer(
    supported: List<AudioMixerAttributes>,
    sampleRate: Int,
    channelCount: Int,
    decodedPcmEncoding: Int,
    int24Available: Boolean,
): AudioMixerAttributes? {
    if (sampleRate <= 0) return null
    if (!Util.isEncodingLinearPcm(decodedPcmEncoding)) return null
    val writable = writablePcmEncodings(decodedPcmEncoding, int24Available)
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
    return pool.maxByOrNull { encodingPreference(it.format.encoding) }
}

/**
 * 播放器能写出的 PCM 编码。
 *
 * 浮点与 16 位整型由媒体3 的默认输出产出：高分辨率源在浮点变体下写浮点、在整型变体下写 16 位整型，
 * 16 位及以下源两种变体都写 16 位整型，故浮点只对高分辨率源可选。
 * 24 位整型由 [Int24PcmAudioSink] 写出，需先确认本机在该格式下能建起 24 位整型轨道；
 * 该实现逐样本转换，24 位及以下源不失真，32 位源会丢低位因而不列入。
 *
 * [decodedPcmEncoding] 取解码头实际输出的线性 PCM 编码：未取得编码时的取值会让「高分辨率」与
 * 「32 位」两项判定都失去依据，凭空放宽可写集合，故调用方须先换算到真实 PCM。
 */
@OptIn(UnstableApi::class)
internal fun writablePcmEncodings(decodedPcmEncoding: Int, int24Available: Boolean): Set<Int> {
    val writable = mutableSetOf(AudioFormat.ENCODING_PCM_16BIT)
    if (Util.isEncodingHighResolutionPcm(decodedPcmEncoding)) {
        writable += AudioFormat.ENCODING_PCM_FLOAT
    }
    if (int24Available && decodedPcmEncoding != AudioFormat.ENCODING_PCM_32BIT) {
        writable += AudioFormat.ENCODING_PCM_24BIT_PACKED
    }
    return writable
}

// 排序取值：浮点优先于 16 位整型，两者都优先于须经自研输出实现写出的 24 位整型
private fun encodingPreference(encoding: Int): Int = when (encoding) {
    AudioFormat.ENCODING_PCM_FLOAT -> 3
    AudioFormat.ENCODING_PCM_16BIT -> 2
    else -> 1
}
