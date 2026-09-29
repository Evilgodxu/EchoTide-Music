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

/**
 * USB 独占输出：把播放钉到 USB 解码器，并向原生音频策略申请位完美混音器。
 *
 * 独占由两个原生接口共同构成，缺一不可：
 * - [AudioManager.setPreferredMixerAttributes] 为该设备建立不做混音、不做音量调节、
 *   不做音效处理的输出流，数据原样下发到 HAL；
 * - [ExoPlayer.setPreferredAudioDevice] 把播放器的输出路由固定到同一设备，
 *   使位完美流成为唯一出口。
 *
 * 位完美流只在播放格式与混音器属性逐字段一致（编码、声道掩码、采样率）时接纳播放，
 * 混音器属性因此按当前解码格式挑选，并在换曲导致格式变化时重新下发。
 *
 * 原生行为（已核实）：
 * - 受理条件：APM 要求 usage 为 USAGE_MEDIA、设备为已接入的 USB 输出，且存在与目标格式、采样率、
 *   声道及各行为兼容的动态输出 profile（BIT_PERFECT 对应 AUDIO_OUTPUT_FLAG_BIT_PERFECT），
 *   任一不满足即返回 BAD_VALUE，此处体现为 set 返回 false；缺少 MODIFY_AUDIO_SETTINGS 则为 PERMISSION_DENIED。
 * - 拔出：APM 在断连的同一路径内直接清除该端口的偏好且不回调，故只能经 AudioDeviceCallback 感知。
 * - 格式不符：写出格式与偏好混音器不一致时，AudioFlinger 不会失败，而是把该轨静默混音输出，
 *   因此输出格式必须与偏好对齐，才不会以「已独占」之名走混音路径。
 *
 * 所有方法都要求在播放器所属线程（主线程）调用。
 */
@OptIn(UnstableApi::class)
class UsbExclusiveOutput(
    private val player: ExoPlayer,
    private val audioManager: AudioManager,
) {
    private var enabled = false
    private var callbackRegistered = false
    /** 已钉定的 USB 输出设备，null 表示当前未独占 */
    private var targetDevice: AudioDeviceInfo? = null
    /** 已下发的混音器属性：重复下发会让框架重开输出流，故仅在取值变化时调用 */
    private var appliedMixerAttributes: AudioMixerAttributes? = null
    /** 当前曲目的解码格式，混音器属性需与之逐字段（采样率、声道、编码）匹配才能被位完美流接纳 */
    private var decodedSampleRate = 0
    private var decodedChannelCount = 0
    private var decodedPcmEncoding = 0

    /** 独占路由变更回调：钉定或解除独占时报告当前设备，null 表示已回到系统混音输出 */
    var onRoutingChanged: ((AudioDeviceInfo?) -> Unit)? = null

    private val audioDeviceHandler = Handler(Looper.getMainLooper())

    // 复用播放器自身的音频属性：原生侧按属性匹配播放记录，另建一份等价属性会对不上
    private val playbackAttributes
        get() = player.audioAttributes.platformAudioAttributes

    private val deviceCallback = object : AudioDeviceCallback() {
        // 插拔会同时让路由与混音器属性失效，两者一并重算
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputRouting()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputRouting()
        }
    }

    /** 开启或关闭独占；关闭时撤销配置并解除路由钉定，播放回到系统默认混音输出 */
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (value) {
            registerCallback()
            refreshOutputRouting()
        } else {
            releaseConfiguration()
            unregisterCallback()
        }
    }

    /** 当前钉定的独占 USB 输出设备，null 表示未独占；供音频输出按设备选择写出格式 */
    fun exclusiveTargetDevice(): AudioDeviceInfo? = targetDevice

    /**
     * 解码格式变化（换曲、换源）后记录新格式，独占开启时据此重新挑选混音器属性。
     * [pcmEncoding] 是解码头输出的 PCM 编码，挑选混音器条目时据它决定播放器实际写出的编码。
     */
    fun onTrackFormatChanged(sampleRate: Int, channelCount: Int, pcmEncoding: Int) {
        if (sampleRate == decodedSampleRate && channelCount == decodedChannelCount &&
            pcmEncoding == decodedPcmEncoding
        ) {
            return
        }
        decodedSampleRate = sampleRate
        decodedChannelCount = channelCount
        decodedPcmEncoding = pcmEncoding
        refreshOutputRouting()
    }

    fun release() {
        enabled = false
        releaseConfiguration()
        unregisterCallback()
    }

    private fun refreshOutputRouting() {
        if (!enabled) return
        val device = findUsbOutputDevice()
        val mixerAttributes = device?.let {
            pickMixerAttributes(audioManager.getSupportedMixerAttributes(it))
        }
        // 无解码器接入，或设备未实现位完美混音：撤销独占配置，交回系统默认混音输出
        if (device == null || mixerAttributes == null) {
            releaseConfiguration()
            return
        }
        if (device != targetDevice) {
            releaseConfiguration()
            // 属性先于路由下发：播放改道到该设备时，才按已配置的属性建立位完美输出流
            applyMixerAttributes(device, mixerAttributes)
            player.setPreferredAudioDevice(device)
            updateTargetDevice(device)
            return
        }
        if (mixerAttributes != appliedMixerAttributes) {
            applyMixerAttributes(device, mixerAttributes)
        }
    }

    private fun applyMixerAttributes(
        device: AudioDeviceInfo,
        mixerAttributes: AudioMixerAttributes,
    ) {
        val accepted = audioManager.setPreferredMixerAttributes(
            playbackAttributes,
            device,
            mixerAttributes,
        )
        if (!accepted) {
            // 未受理即属性不合法或设备/配置不受支持，不会建立位完美流，播放走默认混音；
            // 属性本身已记录，避免每次换曲重试
            CrashLogManager.logException(
                "UsbExclusiveOutput",
                "USB 输出拒绝首选混音器属性: ${device.productName}",
            )
        }
        appliedMixerAttributes = mixerAttributes
    }

    private fun releaseConfiguration() {
        val device = targetDevice ?: return
        // 拔出时 APM 已在断连路径内清除该端口的偏好，此处 clear 会返回 NAME_NOT_FOUND；
        // 属性归属 uid 不符时返回 PERMISSION_DENIED。两者都无需处理
        runCatching { audioManager.clearPreferredMixerAttributes(playbackAttributes, device) }
        player.setPreferredAudioDevice(null)
        appliedMixerAttributes = null
        updateTargetDevice(null)
    }

    // 独占设备变更的唯一出口：赋值与对外通知同处一处，避免内部状态与上报值脱节
    private fun updateTargetDevice(device: AudioDeviceInfo?) {
        if (device == targetDevice) return
        targetDevice = device
        onRoutingChanged?.invoke(device)
    }

    private fun findUsbOutputDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && it.type in USB_OUTPUT_TYPES }

    /**
     * 挑出可承载当前曲目的位完美混音器。
     * 只取位完美行为的条目：厂商未实现时宁可退回默认混音，也不以「已独占」的名义继续走混音路径。
     * 解码格式未知（尚未起播）时不下发，等轨道信息就绪后由 onTrackFormatChanged 触发。
     */
    private fun pickMixerAttributes(supported: List<AudioMixerAttributes>): AudioMixerAttributes? =
        selectBitPerfectMixer(supported, decodedSampleRate, decodedChannelCount, decodedPcmEncoding)

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
 * 从设备支持的混音器属性中挑出可承载解码格式的位完美条目，无可用条目时返回 null。
 *
 * 位完美流只接纳与混音器属性逐字段一致的播放，故候选先按采样率与声道数筛定——声道不符的条目挂不上，
 * 选中它只会让播放静默落回混音路径；无声道一致的条目时退回全量候选，交由编码挑出最接近者。
 * 再按播放器实际写出的 PCM 编码排序：高分辨率源首选浮点条目（浮点变体写浮点），无浮点条目时退选
 * 16 位整型（整型变体写 16 位）；16 位及以下源两种变体都写 16 位整型，故首选 16 位整型条目。
 *
 * 独占侧据此下发混音器属性，[PerDeviceAudioSink] 据此选择写出变体，两处共用本函数才不会各自跑偏：
 * 一旦写出编码与所下发的条目不符，AudioFlinger 不报错而是静默混音输出，「已独占」名不副实。
 */
internal fun selectBitPerfectMixer(
    supported: List<AudioMixerAttributes>,
    sampleRate: Int,
    channelCount: Int,
    inputPcmEncoding: Int,
): AudioMixerAttributes? {
    if (sampleRate <= 0) return null
    val bitPerfectAtRate = supported.filter {
        it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
            it.format.sampleRate == sampleRate
    }
    val channelMatched = if (channelCount > 0) {
        bitPerfectAtRate.filter { it.format.channelCount == channelCount }
    } else {
        emptyList()
    }
    val candidates = channelMatched.ifEmpty { bitPerfectAtRate }
    if (candidates.isEmpty()) return null
    val preferFloat = Util.isEncodingHighResolutionPcm(inputPcmEncoding)
    return candidates.maxByOrNull { attributes ->
        when (attributes.format.encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> if (preferFloat) 1 else 0
            AudioFormat.ENCODING_PCM_16BIT -> if (preferFloat) 0 else 1
            else -> 0
        }
    }
}
