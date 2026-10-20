package com.yichao.evilgodxu.log

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import com.yichao.evilgodxu.data.music.playback.USB_OUTPUT_TYPES
import com.yichao.evilgodxu.data.music.playback.audioEncodingName
import com.yichao.evilgodxu.data.music.playback.describeMixerAttributes
import com.yichao.evilgodxu.data.music.playback.usb.UsbAudioCapabilities
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// 报告随今日日志同批存放，文件名由 CrashLogManager 的日期口径补齐
private const val REPORT_FILE_NAME = "设备音频报告.txt"

// 异常日志的类名前缀：报告只在写出失败时记一次
private const val LOG_TAG = "DeviceReport"

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

// 与播放器自身的属性同口径：混音器偏好与路由都按这条属性判定
private val PLAYBACK_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

/**
 * 设备音频报告：把定位「换台设备就不一样」所需的现场一次取全，供用户随日志一并分享。
 *
 * 要解决的问题：同一份曲目在 USB 解码器、蓝牙与内置扬声器上是三条完全不同的通路，而平台对它们的描述
 * 分散在 AudioManager 属性、AudioDeviceInfo、动态混音端口与 USB 描述符四处，用户口述不出来。缺了这份
 * 现场，「直出为什么没挂上」只能靠猜。
 *
 * 只读、不申请授权、不改动任何播放状态：USB 描述符那条路刻意不 claim 接口（见 [UsbAudioCapabilities]），
 * 故正在走的平台链路不受影响。读不到的项写「未上报」/「读不到」，不写推测值。
 *
 * 只在用户主动分享时生成并覆盖当日报告，不参与播放链路，也不常驻任何监听。
 */
internal object DeviceReport {

    /** 生成并覆盖写入今日报告，返回分享用的文件；日志目录不可用时为 null */
    fun writeShareable(context: Context): File? {
        val file = CrashLogManager.todayAttachment(REPORT_FILE_NAME) ?: return null
        return runCatching {
            file.writeText(build(context))
            file
        }.onFailure {
            CrashLogManager.logException(LOG_TAG, "写入设备音频报告失败", it)
        }.getOrNull()
    }

    private fun build(context: Context): String {
        val audioManager = context.getSystemService(AudioManager::class.java)
        return buildString {
            appendLine("======== EchoTideMusic 设备音频报告 ========")
            appendLine("生成时间: ${LocalDateTime.now().format(TIME_FORMAT)}")
            appendLine("版本: ${CrashLogManager.appVersion}")
            appendEnvironment(context)
            appendAudioProperties(audioManager)
            appendOutputDevices(context, audioManager)
            appendUsbDevices(context)
        }
    }

    private fun StringBuilder.appendEnvironment(context: Context) {
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}（product=${Build.PRODUCT} device=${Build.DEVICE}）")
        appendLine(
            "硬件: 平台=${Build.HARDWARE} SoC=${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} " +
                "ABI=${Build.SUPPORTED_ABIS.joinToString()}"
        )
        appendLine("系统: Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}，补丁 ${Build.VERSION.SECURITY_PATCH}）")
        appendLine("特性: ${featureSummary(context)}")
        appendLine()
    }

    // 音频相关的系统特性：缺哪个就少哪条能力，是判断「这台机器本来就不支持」的第一道依据
    private fun featureSummary(context: Context): String {
        val features = context.packageManager
        fun mark(feature: String): String = if (features.hasSystemFeature(feature)) "是" else "否"
        return "PRO=${mark(PackageManager.FEATURE_AUDIO_PRO)} " +
            "低延迟=${mark(PackageManager.FEATURE_AUDIO_LOW_LATENCY)} " +
            "音频输出=${mark(PackageManager.FEATURE_AUDIO_OUTPUT)} " +
            "USB主机=${mark(PackageManager.FEATURE_USB_HOST)} " +
            "USB配件=${mark(PackageManager.FEATURE_USB_ACCESSORY)} " +
            "BLE=${mark(PackageManager.FEATURE_BLUETOOTH_LE)}"
    }

    private fun StringBuilder.appendAudioProperties(audioManager: AudioManager?) {
        appendLine("---- 平台音频属性 ----")
        appendLine("输出采样率: ${audioManager.property(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)}")
        appendLine("每突发帧数: ${audioManager.property(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)}")
        appendLine("支持未处理采集: ${audioManager.property(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)}")
        appendLine()
    }

    // 平台属性读不到是常态（厂商未实现），按「读不到」如实写出
    private fun AudioManager?.property(name: String): String =
        runCatching { this?.getProperty(name) }.getOrNull()?.takeIf { it.isNotBlank() } ?: "读不到"

    private fun StringBuilder.appendOutputDevices(context: Context, audioManager: AudioManager?) {
        val devices = runCatching { audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrNull()
            .orEmpty()
        appendLine("---- 输出设备（${devices.size} 台）----")
        if (devices.isEmpty()) appendLine("未上报")
        devices.forEach { appendOutputDevice(context, audioManager, it) }
        appendLine()
    }

    private fun StringBuilder.appendOutputDevice(
        context: Context,
        audioManager: AudioManager?,
        device: AudioDeviceInfo,
    ) {
        appendLine("==== 设备 #${device.id} ------------")
        appendLine("产品名: ${device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "未上报"}")
        appendLine("类型: ${audioDeviceTypeName(device.type)}")
        device.address.takeIf { it.isNotBlank() && it != ZERO_ADDRESS }
            ?.let { appendLine("地址: $it") }
        appendLine("方向: 输出${if (device.isSource) "（兼输入）" else ""}")
        appendLine("声道数: ${device.channelCounts.joinToString(" ").ifEmpty { "未上报" }}")
        appendLine("声道掩码: ${device.channelMasks.joinToString(" ") { channelMaskName(it) }.ifEmpty { "未上报" }}")
        appendLine("索引掩码: ${device.channelIndexMasks.joinToString(" ") { hex(it) }.ifEmpty { "未上报" }}")
        appendLine("编码: ${device.encodings.joinToString(" ") { audioEncodingName(it) }.ifEmpty { "未上报" }}")
        appendLine("采样率（平台上报）: ${device.sampleRates.sorted().joinToString(" ").ifEmpty { "未上报" }}")
        mixerAttributeLines(audioManager, device).forEach { appendLine(it) }
        appendUsbDeclared(context, device)
        appendLine()
    }

    /**
     * 动态混音端口的档位与已受理的偏好。
     *
     * 这两行是「直出到底挂上没挂上」的直接证据：支持列表为空说明该设备此刻没有可用端口（厂商未声明，
     * 或设备上没有输出），已受理偏好说明本应用当前钉定了哪一档。
     */
    private fun mixerAttributeLines(audioManager: AudioManager?, device: AudioDeviceInfo): List<String> {
        val supported = runCatching { audioManager?.getSupportedMixerAttributes(device) }
            .getOrNull()
            .orEmpty()
        val preferred = runCatching { audioManager?.getPreferredMixerAttributes(PLAYBACK_ATTRIBUTES, device) }
            .getOrNull()
        // 平台会为同一档位返回多条同名条目（实测 KT02H20 每档两条），逐条照抄只是噪音；
        // 原始条数另记，重复本身可能对应设备侧的多个 profile，不该静默吞掉
        val descriptions = supported.map(::describeMixerAttributes).distinct()
        return buildList {
            add(
                "动态混音端口: " + when {
                    descriptions.isEmpty() -> "无（厂商未声明，或该设备此刻没有输出）"
                    descriptions.size == supported.size -> descriptions.joinToString("、")
                    else -> descriptions.joinToString("、") + "（原始 ${supported.size} 条，含重名条目）"
                }
            )
            preferred?.let { add("已受理偏好: ${describeMixerAttributes(it)}") }
        }
    }

    /**
     * 设备描述符自己声明的播放能力，及其结构摘要；非 USB 通路不产出这段。
     *
     * 平台 API 报不全这一份（`getSupportedMixerAttributes` 只给动态端口声明过的条目），故 USB 通路另读
     * 描述符——这正是直出能否成立的原始依据。读不到时如实写出，不按平台值替代。
     *
     * 摘要只在读到设备时才追加：档位为空时，它是唯一能说明「断在哪一层」的东西。
     */
    private fun StringBuilder.appendUsbDeclared(context: Context, device: AudioDeviceInfo) {
        if (device.type !in USB_OUTPUT_TYPES) return
        val name = device.productName?.toString()?.takeIf { it.isNotBlank() }
        // 独占路径读到的声明优先：只读路径下设备可能不应答类请求（实测 KT02H20），那时档位只剩平台值
        val remembered = UsbAudioCapabilities.remembered(name)
        val declared = remembered ?: UsbAudioCapabilities.read(context, name)
        if (declared == null) {
            appendLine("描述符声明: 读不到（无授权、非音频设备或读取失败）")
            return
        }
        val rates = declared.supportedSampleRates.joinToString(" ")
            .ifEmpty { "未声明（只读路径未获应答，见结构行）" }
        val origin = if (remembered != null) "（独占期间读到）" else ""
        appendLine(
            "描述符声明: UAC${declared.uacVersion?.takeIf { it > 0 } ?: "?"}" +
                "、采样率 $rates$origin" +
                "、编码 ${declared.supportedEncodings.joinToString(" ")}" +
                "、声道 ${declared.channelCount ?: "未声明"}"
        )
        UsbAudioCapabilities.describeStructure(context, name)?.forEach { appendLine("  $it") }
    }

    private fun StringBuilder.appendUsbDevices(context: Context) {
        val manager = context.getSystemService(UsbManager::class.java)
        val devices = runCatching { manager?.deviceList?.values?.toList() }.getOrNull().orEmpty()
        appendLine("---- USB 设备（${devices.size} 个）----")
        if (devices.isEmpty()) {
            appendLine("无")
            return
        }
        devices.forEach { appendUsbDevice(manager, it) }
    }

    private fun StringBuilder.appendUsbDevice(manager: UsbManager?, device: UsbDevice) {
        appendLine("==== ${hex4(device.vendorId)}:${hex4(device.productId)} ------------")
        appendLine("名称: ${device.productName ?: "未上报"} 厂商: ${device.manufacturerName ?: "未上报"}")
        appendLine(
            "类: ${device.deviceClass}/${device.deviceSubclass}/${device.deviceProtocol} " +
                "版本: ${device.version} 接口数: ${device.interfaceCount}"
        )
        appendLine("已授权: ${if (manager?.hasPermission(device) == true) "是" else "否"} 节点: ${device.deviceName}")
    }

    private fun audioDeviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "BUILTIN_SPEAKER_SAFE"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "LINE_ANALOG"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "LINE_DIGITAL"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI_ARC"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI_EARC"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB_ACCESSORY"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_DOCK -> "DOCK"
        AudioDeviceInfo.TYPE_FM -> "FM"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX_LINE"
        AudioDeviceInfo.TYPE_IP -> "IP"
        AudioDeviceInfo.TYPE_BUS -> "BUS"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE_SUBMIX"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
        AudioDeviceInfo.TYPE_UNKNOWN -> "UNKNOWN"
        else -> "类型$type"
    }

    // 只认常见布局：其余按位掩码原样给出，开发者可直接对照平台常量
    private fun channelMaskName(mask: Int): String = when (mask) {
        AudioFormat.CHANNEL_OUT_MONO -> "MONO"
        AudioFormat.CHANNEL_OUT_STEREO -> "STEREO"
        AudioFormat.CHANNEL_OUT_QUAD -> "QUAD"
        AudioFormat.CHANNEL_OUT_5POINT1 -> "5POINT1"
        AudioFormat.CHANNEL_OUT_7POINT1_SURROUND -> "7POINT1_SURROUND"
        AudioFormat.CHANNEL_OUT_SURROUND -> "SURROUND"
        else -> hex(mask)
    }

    private fun hex(value: Int): String = String.format(Locale.US, "0x%X", value)

    private fun hex4(value: Int): String = String.format(Locale.US, "%04X", value)

    // 平台对无地址的设备以上报字符串 "0" 表示，不是真实地址
    private const val ZERO_ADDRESS = "0"
}
