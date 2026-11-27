package com.yichao.evilgodxu.report

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.yichao.evilgodxu.data.music.playback.USB_OUTPUT_TYPES
import com.yichao.evilgodxu.data.music.playback.audioEncodingName
import com.yichao.evilgodxu.data.music.playback.describeMixerAttributes
import com.yichao.evilgodxu.data.music.playback.usb.UsbAudioCapabilities
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// 落缓存目录，随时可被系统清理
private const val REPORT_DIR_NAME = "reports"

private const val REPORT_FILE_NAME = "设备音频报告.png"

// 写出失败只记系统日志
private const val TAG = "AudioReport"

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

// 与播放器属性同口径
private val PLAYBACK_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

/** 设备音频报告：汇总属性、设备与 USB 声明；只读不改状态 */
internal object AudioReport {

    /** 生成并覆盖报告文件；失败为 null */
    fun writeShareable(context: Context): File? {
        val dir = File(context.cacheDir, REPORT_DIR_NAME)
        val file = File(dir, REPORT_FILE_NAME)
        return runCatching {
            dir.mkdirs()
            AudioReportImage.write(build(context), file)
            file
        }.onFailure {
            Log.e(TAG, "写入设备音频报告失败", it)
        }.getOrNull()
    }

    private fun build(context: Context): String {
        val audioManager = context.getSystemService(AudioManager::class.java)
        return buildString {
            appendLine("======== EchoTideMusic 设备音频报告 ========")
            appendLine("生成时间: ${LocalDateTime.now().format(TIME_FORMAT)}")
            appendLine("版本: ${appVersion(context)}")
            appendEnvironment(context)
            appendAudioProperties(audioManager)
            appendOutputDevices(context, audioManager)
            appendUsbDevices(context)
        }
    }

    // 当场读版本，覆盖安装后会变
    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("unknown")

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

    // 系统特性：判断设备能力的第一道依据
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

    // 读不到按实际情况写出
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

    /** 动态混音端口与已受理偏好，直出是否生效的证据 */
    private fun mixerAttributeLines(audioManager: AudioManager?, device: AudioDeviceInfo): List<String> {
        val supported = runCatching { audioManager?.getSupportedMixerAttributes(device) }
            .getOrNull()
            .orEmpty()
        val preferred = runCatching { audioManager?.getPreferredMixerAttributes(PLAYBACK_ATTRIBUTES, device) }
            .getOrNull()
        // 同名条目去重，原始条数另记
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

    /** USB 设备描述符声明与结构摘要；非 USB 通路跳过 */
    private fun StringBuilder.appendUsbDeclared(context: Context, device: AudioDeviceInfo) {
        if (device.type !in USB_OUTPUT_TYPES) return
        val name = device.productName?.toString()?.takeIf { it.isNotBlank() }
        // 优先独占读到的声明，只读路径可能无应答
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

    // 常见布局给名字，其余给掩码
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

    // "0" 表示无地址
    private const val ZERO_ADDRESS = "0"
}
