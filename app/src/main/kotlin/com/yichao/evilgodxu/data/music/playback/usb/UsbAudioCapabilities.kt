package com.yichao.evilgodxu.data.music.playback.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.yichao.evilgodxu.data.music.playback.UsbExclusiveDeviceInfo
import com.yichao.evilgodxu.data.music.playback.outputEncodingOfBitDepth
import com.yichao.evilgodxu.log.CrashLogManager

// 异常日志类名前缀：只在读描述符出错时记录
private const val LOG_TAG = "UsbAudioCapabilities"

/**
 * 由描述符与控制通道拼出设备信息。
 * UAC2 档位问时钟，UAC1 区间展开。
 */
internal fun usbAudioDeviceInfo(
    name: String,
    parsed: UsbAudioDevice,
    control: UsbAudioControl,
    clockRateCache: MutableMap<Int, List<Int>>,
): UsbExclusiveDeviceInfo {
    val declared = parsed.outputs.flatMap { format ->
        if (format.continuousRates) {
            usbAudioRatesInRange(format.minRate.toLong(), format.maxRate.toLong(), 0L)
        } else {
            format.rates
        }
    }.filter { it > 0 }
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
        uacVersion = parsed.uacVersion,
    )
}

/**
 * 摊成可与抓包对照的行。
 * 逐层展开格式、终端、时钟实体与应答。
 */
internal fun usbAudioStructureLines(parsed: UsbAudioDevice, control: UsbAudioControl): List<String> = buildList {
    add("结构: UAC${parsed.uacVersion}，控制接口 #${parsed.controlInterfaceNumber}，可播放格式 ${parsed.outputs.size} 个")
    parsed.outputs.forEach { format ->
        val rateText = if (format.continuousRates) {
            "连续 ${format.minRate}..${format.maxRate}"
        } else {
            "离散 ${format.rates.joinToString(" ").ifEmpty { "未声明" }}"
        }
        // 自适应/同步跟包速率，异步才须设速率
        val feedback = if (format.feedbackEndpoint == 0) "无" else hex(format.feedbackEndpoint)
        add(
            "格式: alt${format.altSetting} 接口=${format.interfaceNumber} 端点=${hex(format.endpointAddress)}" +
                " 包=${format.maxPacketBytes}B 同步=${syncTypeName(format.syncType)} 反馈=$feedback" +
                " 终端=${format.terminalLink} 时钟=${format.clockId} 位深=${format.bitResolution} $rateText"
        )
    }
    parsed.units
        .filter { it.kind == UsbAudioUnit.Kind.INPUT_TERMINAL || it.kind == UsbAudioUnit.Kind.OUTPUT_TERMINAL }
        .forEach { unit ->
            val kind = if (unit.kind == UsbAudioUnit.Kind.INPUT_TERMINAL) "输入" else "输出"
            add("终端: #${unit.id} $kind 时钟=${unit.clockId} 源=${unit.sourceId}")
        }
    parsed.featureUnits.forEach { unit ->
        val channels = if (unit.channelVolume.isEmpty()) {
            "无"
        } else {
            unit.channelVolume.joinToString(" ") { if (it) "可写" else "只读" }
        }
        add(
            "单元: 特征#${unit.unitId} 源=${unit.sourceId}" +
                " 主音量=${if (unit.masterVolume) "可写" else "否"} 声道音量=$channels"
        )
    }
    if (parsed.clocks.isEmpty()) add("时钟: 无（UAC2 才会声明时钟实体）")
    parsed.clocks.forEach { clock ->
        val inputs = if (clock.inputs.isEmpty()) "-" else clock.inputs.joinToString(" ")
        add(
            "时钟: #${clock.id} ${clockKindName(clock.kind)} 输入=$inputs" +
                " 频率可写=${if (clock.frequencyWritable) "是" else "否"}"
        )
    }
    val sources = parsed.outputs.map { parsed.resolveClockSource(it.clockId) { id -> control.selectedClockPin(id) } }
    add("回溯: 各格式的时钟源 ${sources.joinToString(" ")}（0 表示回溯不到）")
    parsed.clocks.filter { it.kind == UsbAudioClock.Kind.SOURCE }.forEach { source ->
        val trace = mutableListOf<String>()
        val rates = runCatching { control.readClockRates(source.id) { trace += it } }
            .getOrDefault(emptyList())
        add(
            "时钟源档位: #${source.id} -> ${rates.joinToString(" ").ifEmpty { "读不到" }}" +
                "（${trace.joinToString("；")}）"
        )
    }
    appendClassRequestProbe(parsed, control)
}

/**
 * 另发一条无关类请求，看设备是否全都不答。
 */
private fun MutableList<String>.appendClassRequestProbe(parsed: UsbAudioDevice, control: UsbAudioControl) {
    // 探播放链特征单元，录音链可能拒答
    val feature = parsed.outputs.firstNotNullOfOrNull { parsed.playbackFeatureUnit(it) }
    if (feature == null) {
        add("类请求自检: 无带音量的播放链特征单元，无法另证类请求是否可达")
        return
    }
    val volume = runCatching { control.currentVolume(feature) }.getOrNull()
    add(
        "类请求自检: 播放链特征单元#${feature.unitId} 读音量 -> " +
            if (volume == null) "失败" else "成功（$volume/256 dB）"
    )
}

private fun syncTypeName(syncType: UsbAudioSyncType): String = when (syncType) {
    UsbAudioSyncType.NONE -> "无"
    UsbAudioSyncType.ASYNCHRONOUS -> "异步"
    UsbAudioSyncType.ADAPTIVE -> "自适应"
    UsbAudioSyncType.SYNCHRONOUS -> "同步"
}

// 编号单字节，按十六进制给出便于与抓包对照
private fun hex(value: Int): String = "0x" + value.toString(16).uppercase()

private fun clockKindName(kind: UsbAudioClock.Kind): String = when (kind) {
    UsbAudioClock.Kind.SOURCE -> "源"
    UsbAudioClock.Kind.SELECTOR -> "选择器"
    UsbAudioClock.Kind.MULTIPLIER -> "倍频器"
}

/**
 * 只读声明能力：打开设备读完即关。
 *
 * 不 claim 接口，免得内核驱动被解绑。
 * 读不到返回 null，由调用方用平台 API。
 */
internal object UsbAudioCapabilities {

    /**
     * 独占路径读到的声明，仅进程内有效。
     * claim 前设备可能不答类请求，故留真值给报告。
     */
    @Volatile
    private var rememberedDeclared: UsbExclusiveDeviceInfo? = null

    /** 记下独占路径读到的声明，空档位不记 */
    fun remember(info: UsbExclusiveDeviceInfo) {
        if (info.supportedSampleRates.isEmpty()) return
        rememberedDeclared = info
    }

    /** 取本次运行读到的声明，产品名不符时不返回旧值 */
    fun remembered(preferProductName: String?): UsbExclusiveDeviceInfo? =
        rememberedDeclared?.takeIf { usbProductNameMatches(preferProductName, it.name) }

    /** 优先读取的产品名：多台解码器同接时挑出正在发声的那台 */
    fun read(context: Context, preferProductName: String? = null): UsbExclusiveDeviceInfo? =
        withParsedDevice(context, preferProductName) { device, parsed, control ->
            usbAudioDeviceInfo(
                name = device.productName?.takeIf { it.isNotBlank() } ?: device.deviceName,
                parsed = parsed,
                control = control,
                clockRateCache = HashMap(),
            )
        }

    /**
     * 结构摘要，每行带编号可与抓包对照。
     */
    fun describeStructure(context: Context, preferProductName: String? = null): List<String>? =
        withParsedDevice(context, preferProductName) { _, parsed, control ->
            usbAudioStructureLines(parsed, control)
        }

    /**
     * 打开设备、解析描述符，交给 block 后关闭。
     * 解析失败或无播放格式都返回 null。
     */
    private fun <T> withParsedDevice(
        context: Context,
        preferProductName: String?,
        block: (UsbDevice, UsbAudioDevice, UsbAudioControl) -> T,
    ): T? {
        val manager = context.getSystemService(UsbManager::class.java) ?: return null
        val candidates = UsbExclusiveDac.attachedDacs(manager)
        // 名称须归一后比较，对不上且不唯一时不猜
        val device = candidates.firstOrNull { usbProductNameMatches(preferProductName, it.productName) }
            ?: candidates.singleOrNull()
            ?: return null
        if (!manager.hasPermission(device)) return null
        val connection = manager.openDevice(device) ?: return null
        return try {
            val raw = connection.rawDescriptors ?: return null
            val parsed = UsbAudioDescriptorParser.parse(raw)
            if (parsed.outputs.isEmpty()) {
                null
            } else {
                block(device, parsed, UsbAudioControl(connection, parsed))
            }
        } catch (t: Throwable) {
            CrashLogManager.logException(LOG_TAG, "读取 ${device.deviceName} 的声明能力失败", t)
            null
        } finally {
            runCatching { connection.close() }
        }
    }
}
