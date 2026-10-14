package com.yichao.evilgodxu.data.music.playback.usb

import android.content.Context
import android.hardware.usb.UsbManager
import com.yichao.evilgodxu.data.music.playback.UsbExclusiveDeviceInfo
import com.yichao.evilgodxu.data.music.playback.outputEncodingOfBitDepth
import com.yichao.evilgodxu.log.CrashLogManager

// 异常日志的类名前缀：只读描述符只有读出问题时才记
private const val LOG_TAG = "UsbAudioCapabilities"

/**
 * 由已解析的描述符与控制通道拼出设备信息，是"设备到底声明了什么"的唯一口径。
 *
 * 独占与只读两条路都汇到这里：前者已持有连接、可以顺手问时钟；后者只是打开设备读描述符。
 * 两处各写一套必然漂移，而漂移的后果是同一个设备在两处显示成不同的能力。
 *
 * 采样率同理不只是描述符里声明的那些：**UAC2 的档位不在描述符里**，得问时钟源，故一并在此补齐，
 * 问过即记进 [clockRateCache]，同一时钟源不会被反复问。查询失败按"读不到"处理——界面少一行，
 * 总好过整块面板打不开。
 */
internal fun usbAudioDeviceInfo(
    name: String,
    parsed: UsbAudioDevice,
    control: UsbAudioControl,
    clockRateCache: MutableMap<Int, List<Int>>,
): UsbExclusiveDeviceInfo {
    val declared = parsed.outputs.flatMap { it.rates }.filter { it > 0 }
    val fromClocks = if (parsed.uacVersion != 2) {
        emptyList()
    } else {
        runCatching {
            parsed.outputs.mapNotNull { format ->
                val source = parsed.resolveClockSource(format.clockId) { control.selectedClockPin(it) }
                if (source == 0) null else clockRateCache.getOrPut(source) { control.readClockRates(source) }
            }.flatten()
        }.getOrDefault(emptyList())
    }
    return UsbExclusiveDeviceInfo(
        name = name,
        supportedSampleRates = (declared + fromClocks).filter { it > 0 }.distinct().sorted(),
        supportedEncodings = parsed.outputs
            .mapNotNull { outputEncodingOfBitDepth(it.bitResolution) }
            .distinct()
            .sortedBy { it.ordinal },
        channelCount = parsed.outputs.map { it.channels }.firstOrNull { it > 0 },
    )
}

/**
 * 只读设备声明的播放能力：打开设备读原始描述符，随即关闭。
 *
 * **刻意不 claim 接口**：接管会把内核的音频驱动解绑、该设备随之从系统音频里消失，而这里只是想把
 * "设备到底声明了什么"读出来，平台链路不该因此受影响。`UsbManager.openDevice` 本身只校验授权并打开
 * usbfs 节点（见 AOSP `UsbHostManager.openDevice`），不碰接口归属，故这条路对正在走的平台直出无害。
 *
 * 存在的理由：平台 API 报不全设备的能力——`getSupportedMixerAttributes` 只给得出动态混音端口声明的
 * 条目，实测某解码器在描述符里声明 16/24/32 三档，那里只报最高一档的 32 位。要按设备真实声明的口径
 * 展示，就只能读描述符。
 *
 * 无设备、无授权或读取失败一律返回 null，由调用方退回平台 API 的取值——读不到是常态而非异常。
 */
internal object UsbAudioCapabilities {

    /**
     * @param preferProductName 优先读取的产品名：多台解码器同接时用它挑出正在发声的那一台
     */
    fun read(context: Context, preferProductName: String? = null): UsbExclusiveDeviceInfo? {
        val manager = context.getSystemService(UsbManager::class.java) ?: return null
        val candidates = UsbExclusiveDac.attachedDacs(manager)
        val device = candidates.firstOrNull { it.productName == preferProductName }
            ?: candidates.singleOrNull()
            ?: return null
        if (!manager.hasPermission(device)) return null
        val connection = manager.openDevice(device) ?: return null
        return try {
            val raw = connection.rawDescriptors
            val parsed = raw?.let { UsbAudioDescriptorParser.parse(it) }
            if (parsed == null || parsed.outputs.isEmpty()) {
                null
            } else {
                usbAudioDeviceInfo(
                    name = device.productName?.takeIf { it.isNotBlank() } ?: device.deviceName,
                    parsed = parsed,
                    control = UsbAudioControl(connection, parsed),
                    clockRateCache = HashMap(),
                )
            }
        } catch (t: Throwable) {
            CrashLogManager.logException(LOG_TAG, "读取 ${device.deviceName} 的声明能力失败", t)
            null
        } finally {
            runCatching { connection.close() }
        }
    }
}
