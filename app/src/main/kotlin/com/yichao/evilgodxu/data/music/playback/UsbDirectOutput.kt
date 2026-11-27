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
import com.yichao.evilgodxu.data.music.playback.engine.PlaybackEngine
import com.yichao.evilgodxu.log.CrashLogManager
import java.util.concurrent.ConcurrentHashMap

// USB 输出：设备、耳机、配件三类
internal val USB_OUTPUT_TYPES = setOf(
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
)

// 日志前缀，仅出错时记录
private const val LOG_TAG = "UsbDirectOutput"

/**
 * USB 直出：钉定输出并申请对齐格式的专用流
 * 档位只取设备声明的混音端口，同率优先、倍率其次
 * 格式变更走播放线程，启停与释放走主线程
 */
@OptIn(UnstableApi::class)
internal class UsbDirectOutput(
    private val engine: PlaybackEngine,
    private val audioManager: AudioManager,
    /** 当前曲目源位深，未知时退回解码编码 */
    private val sourceBitDepth: () -> Int? = { null },
) {
    // 主线程与播放线程共读写，须 volatile
    @Volatile
    private var enabled = false
    private var callbackRegistered = false
    /** 已钉定的 USB 设备，null 为未钉定 */
    @Volatile
    private var targetDevice: AudioDeviceInfo? = null
    /** 已受理混音器属性，null 为未直出；兼作去重 */
    @Volatile
    private var acceptedMixerAttributes: AudioMixerAttributes? = null
    /** 本会话实测建不起轨的格式，并发集合因两线程读写 */
    private val unrealizableFormats = ConcurrentHashMap.newKeySet<AudioFormat>()

    /** 不可用格式所属设备号，换设备即作废 */
    @Volatile
    private var unrealizableFormatsDeviceId: Int? = null
    /** 本次连接问到的设备声明档位及设备号 */
    @Volatile
    private var declaredFormats: List<AudioMixerAttributes> = emptyList()

    @Volatile
    private var declaredFormatsDeviceId: Int? = null
    /** 已上报的输出成色，与回调出参一致 */
    @Volatile
    private var reportedMode = AudioOutputMode.MIXER
    /** 当前解码采样率，须与混音器属性一致 */
    @Volatile
    private var decodedSampleRate = 0

    @Volatile
    private var decodedChannelCount = 0

    @Volatile
    private var decodedPcmEncoding = 0

    /** 直出对齐的源位深，null 时按解码编码挑 */
    @Volatile
    private var targetSourceBitDepth: Int? = null

    /** 输出成色变更回调 */
    var onRoutingChanged: ((AudioOutputMode) -> Unit)? = null

    private val audioDeviceHandler = Handler(Looper.getMainLooper())

    /** 静音保活输出，避免动态端口查询始终为空表 */
    private val keepAlive = SilentKeepAliveOutput(audioManager)

    /** 播放器音频属性，构造期捕获因读取需主线程 */
    private val playbackAttributes = engine.platformAudioAttributes

    private val deviceCallback = object : AudioDeviceCallback() {
        // 插拔使路由与混音器属性失效，一并重算
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputRouting()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshOutputRouting()
        }
    }

    /** 钉定/解钉输出设备，须投递主线程 */
    private fun pinPreferredDevice(device: AudioDeviceInfo?) {
        audioDeviceHandler.post { engine.setPreferredAudioDevice(device) }
    }

    /** 开关直出，关闭时撤销配置并解钉 */
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

    /** 已建专用流的 USB 设备，null 为未直出 */
    fun directTargetDevice(): AudioDeviceInfo? =
        if (acceptedMixerAttributes != null) targetDevice else null

    /** 已受理专用流的写出编码，null 为未直出 */
    fun directOutputEncoding(): Int? = acceptedMixerAttributes?.format?.encoding

    /** 写出率相对源的倍率，未直出或同率时为 1 */
    fun directOutputRateMultiple(): Int =
        acceptedMixerAttributes
            ?.let { sameFamilyMultiple(decodedSampleRate, it.format.sampleRate) }
            ?: 1

    /** 报告该格式建不起轨：记为不可用并改挂下一档，改挂不成才撤销 */
    fun reportUnrealizableFormat() {
        val attributes = acceptedMixerAttributes ?: return
        targetDevice?.let { unrealizableFormatsDeviceId = it.id }
        unrealizableFormats += attributes.format
        if (!applyNextDirectCandidate()) {
            releaseConfiguration()
        }
    }

    /** 按设备声明重挑一档改挂，返回是否成功 */
    private fun applyNextDirectCandidate(): Boolean {
        val device = findUsbOutputDevice() ?: return false
        val candidates = directMixerCandidates(declaredFormatsFor(device))
            .filterNot { it.format in unrealizableFormats }
        if (candidates.isEmpty()) return false
        // 属性先于路由下发，建流时才生效
        val accepted = applyDirectMixer(device, candidates) ?: return false
        pinPreferredDevice(device)
        updateRouting(device, accepted)
        return true
    }

    /** 解码格式变化后记录，非 PCM 不直出 */
    fun onTrackFormatChanged(sampleRate: Int, channelCount: Int, decodedPcmEncoding: Int) {
        val sourceBitDepth = sourceBitDepth()
        if (sampleRate == decodedSampleRate && channelCount == decodedChannelCount &&
            decodedPcmEncoding == this.decodedPcmEncoding && sourceBitDepth == targetSourceBitDepth
        ) {
            return
        }
        decodedSampleRate = sampleRate
        decodedChannelCount = channelCount
        this.decodedPcmEncoding = decodedPcmEncoding
        targetSourceBitDepth = sourceBitDepth
        // 解码格式由信息面板展示
        refreshOutputRouting()
    }

    /** 源位深落定后重挑档位，避免落后一曲 */
    fun onSourceBitDepthSettled() {
        val sourceBitDepth = sourceBitDepth()
        if (sourceBitDepth == targetSourceBitDepth) return
        targetSourceBitDepth = sourceBitDepth
        refreshOutputRouting()
    }

    /** 输出建成后重算路由：空表不作终局 */
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
        // 换设备即作废旧记录，须先于档位判定
        if (device?.id != unrealizableFormatsDeviceId) {
            unrealizableFormats.clear()
            unrealizableFormatsDeviceId = device?.id
        }
        // 保活输出随设备走，换/拔先释放
        if (keepAlive.heldDeviceId != device?.id) keepAlive.release()
        // 直出档位一律取自设备声明，不另拼候选
        var supported = declaredFormatsFor(device)
        // 空表非终局：先保活静音输出再问一次
        if (device != null && supported.isEmpty()) {
            keepAlive.hold(device)
            supported = declaredFormatsFor(device)
            if (supported.isNotEmpty()) {
            }
        }
        // 保活格式被声明覆盖时放弃保活，避免多出一条音轨
        if (keepAlive.format != null && supported.any { it.format == keepAlive.format }) {
            keepAlive.release()
        }
        // 读回校验：平台已清除偏好则作废本地记录
        if (device != null && acceptedMixerAttributes != null &&
            readAcceptedMixerAttributes(device) == null
        ) {
            acceptedMixerAttributes = null
        }
        val built = directMixerCandidates(supported)
        // 已证伪格式不再入选
        val candidates = built.filterNot { it.format in unrealizableFormats }
        // 直出无从成立：撤销配置，交回系统混音
        if (device == null || candidates.isEmpty()) {
            releaseConfiguration()
            return
        }
        if (device != targetDevice) {
            releaseConfiguration()
            // 属性先于路由下发，建流时才生效
            val accepted = applyDirectMixer(device, candidates)
            pinPreferredDevice(device)
            updateRouting(device, accepted)
            return
        }
        // 当前候选已含已受理格式时不再重下发
        val acceptedFormat = acceptedMixerAttributes?.format
        if (acceptedFormat != null && candidates.any { it.format == acceptedFormat }) return
        updateRouting(device, applyDirectMixer(device, candidates))
    }

    /** 依次下发候选，返回首个被受理的；未受理不记 */
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

    /** 读回平台现持有的偏好属性，null 为已清除 */
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
        // 拔出或 uid 不符的失败均忽略
        runCatching { audioManager.clearPreferredMixerAttributes(playbackAttributes, device) }
        pinPreferredDevice(null)
        updateRouting(null, null)
    }

    /** 直出状态唯一出口：记录并推出成色通知 */
    private fun updateRouting(device: AudioDeviceInfo?, attributes: AudioMixerAttributes?) {
        targetDevice = device
        acceptedMixerAttributes = attributes
        val mode = modeOf(device, attributes)
        if (mode == reportedMode) return
        reportedMode = mode
        onRoutingChanged?.invoke(mode)
    }

    // 未钉定设备或无受理属性即系统混音
    private fun modeOf(device: AudioDeviceInfo?, attributes: AudioMixerAttributes?): AudioOutputMode =
        if (device == null || attributes == null) AudioOutputMode.MIXER else AudioOutputMode.FORMAT_LOCKED

    // 设备名缺失时退回编号
    private fun deviceLabel(device: AudioDeviceInfo): String =
        device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "id=${device.id}"

    private fun findUsbOutputDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && it.type in USB_OUTPUT_TYPES }

    /** 读设备支持的混音器属性，异常记日志并按空表处理 */
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

    /** 设备声明档位只问一次；空表不算答案，换设备作废 */
    private fun declaredFormatsFor(device: AudioDeviceInfo?): List<AudioMixerAttributes> {
        if (device == null) return emptyList()
        if (declaredFormatsDeviceId == device.id && declaredFormats.isNotEmpty()) return declaredFormats
        val queried = supportedMixerAttributes(device)
        if (queried.isEmpty()) return emptyList()
        declaredFormats = queried
        declaredFormatsDeviceId = device.id
        return queried
    }

    /** 直出候选：同率优先，其次同族倍率；不构造声明外格式 */
    private fun directMixerCandidates(
        supported: List<AudioMixerAttributes>,
    ): List<AudioMixerAttributes> =
        listOfNotNull(
            selectDirectMixer(
                supported,
                decodedSampleRate,
                decodedChannelCount,
                decodedPcmEncoding,
                targetSourceBitDepth,
            ),
            selectSameFamilyUpsample(
                supported,
                decodedSampleRate,
                decodedChannelCount,
                decodedPcmEncoding,
                targetSourceBitDepth,
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

/** 挑同率直出条目，严格匹配速率/声道/编码 */
@OptIn(UnstableApi::class)
internal fun selectDirectMixer(
    supported: List<AudioMixerAttributes>,
    sampleRate: Int,
    channelCount: Int,
    decodedPcmEncoding: Int,
    sourceBitDepth: Int?,
): AudioMixerAttributes? {
    if (sampleRate <= 0) return null
    if (!PcmConversion.isLinearPcm(decodedPcmEncoding)) return null
    val writable = writablePcmEncodings(decodedPcmEncoding)
    val target = sourceBitDepth
    val candidates = supported.filter {
        it.format.sampleRate == sampleRate &&
            (channelCount <= 0 || it.format.channelCount == channelCount) &&
            it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT &&
            (
                if (target != null) (integerBitDepthOf(it.format.encoding) ?: 0) >= target
                else it.format.encoding in writable
            )
    }
    return if (target != null) {
        candidates.minByOrNull { integerBitDepthOf(it.format.encoding) ?: Int.MAX_VALUE }
    } else {
        candidates.maxByOrNull {
            encodingPreference(it.format.encoding, PcmConversion.isHighResolutionPcm(decodedPcmEncoding))
        }
    }
}

/** 挑同族整数倍的直出条目，倍率取最小；编码须可插值 */
@OptIn(UnstableApi::class)
internal fun selectSameFamilyUpsample(
    supported: List<AudioMixerAttributes>,
    sampleRate: Int,
    channelCount: Int,
    decodedPcmEncoding: Int,
    sourceBitDepth: Int?,
): AudioMixerAttributes? {
    if (sampleRate <= 0) return null
    if (!PcmConversion.isLinearPcm(decodedPcmEncoding)) return null
    if (!canInterpolatePcm(decodedPcmEncoding)) return null
    val writable = writablePcmEncodings(decodedPcmEncoding)
    val target = sourceBitDepth
    val candidates = supported
        .filter {
            (channelCount <= 0 || it.format.channelCount == channelCount) &&
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT &&
                (
                    if (target != null) (integerBitDepthOf(it.format.encoding) ?: 0) >= target
                    else it.format.encoding in writable
                )
        }
        .mapNotNull { attributes ->
            sameFamilyMultiple(sampleRate, attributes.format.sampleRate)?.let { it to attributes }
        }
    // 倍率小者优先；同倍率取最窄整型档
    return if (target != null) {
        candidates.minWithOrNull(
            compareBy({ it.first }, { integerBitDepthOf(it.second.format.encoding) ?: Int.MAX_VALUE })
        )?.second
    } else {
        candidates.minWithOrNull(
            compareBy(
                { it.first },
                {
                    -encodingPreference(
                        it.second.format.encoding,
                        PcmConversion.isHighResolutionPcm(decodedPcmEncoding),
                    )
                },
            )
        )?.second
    }
}

/** 整型有效位深，浮点等返回 null */
private fun integerBitDepthOf(encoding: Int): Int? = when (encoding) {
    AudioFormat.ENCODING_PCM_16BIT -> 16
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
    AudioFormat.ENCODING_PCM_32BIT -> 32
    else -> null
}

/** 本应用写得出的 PCM 编码，不含建轨可行性 */
@OptIn(UnstableApi::class)
internal fun writablePcmEncodings(decodedPcmEncoding: Int): Set<Int> {
    val writable = mutableSetOf(
        AudioFormat.ENCODING_PCM_16BIT,
        AudioFormat.ENCODING_PCM_24BIT_PACKED,
        AudioFormat.ENCODING_PCM_32BIT,
    )
    if (PcmConversion.isHighResolutionPcm(decodedPcmEncoding)) {
        writable += AudioFormat.ENCODING_PCM_FLOAT
    }
    if (decodedPcmEncoding == AudioFormat.ENCODING_PCM_32BIT) {
        writable -= AudioFormat.ENCODING_PCM_24BIT_PACKED
    }
    return writable
}

/** 源位深未知时的编码优先序：浮点最高 */
private fun encodingPreference(encoding: Int, highResolutionSource: Boolean): Int = when {
    encoding == AudioFormat.ENCODING_PCM_FLOAT -> 4
    highResolutionSource -> when (encoding) {
        AudioFormat.ENCODING_PCM_32BIT -> 3
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 2
        else -> 1
    }

    else -> when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> 3
        AudioFormat.ENCODING_PCM_32BIT -> 2
        else -> 1
    }
}
