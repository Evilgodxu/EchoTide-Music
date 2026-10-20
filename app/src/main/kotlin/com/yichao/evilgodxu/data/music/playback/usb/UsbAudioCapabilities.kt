package com.yichao.evilgodxu.data.music.playback.usb

import android.content.Context
import android.hardware.usb.UsbDevice
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
 *
 * UAC1 声明连续区间时 `rates` 为空、上下限才有值；直接取 `rates` 会把这类设备的档位整片丢掉
 * （实测 KT02H20 即如此），故连续区间按 UAC2 RANGE 应答的同一口径展开。
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
 * 把解析结果摊成可与描述符抓包对照的行，回答「设备声明了档位，为什么一个也读不到」。
 *
 * 读档位的链路是「格式 → 终端 → 时钟实体 → 时钟源应答」，四层中任何一层断开，结果都是一张空表。
 * 故逐层摊开：哪一层先缺，报告里就先断在哪里。末尾另直接问一遍时钟源——回溯得 0 而这里读得到，
 * 说明断在链路回溯；两处都读不到，才轮到控制传输或设备不应答。
 */
internal fun usbAudioStructureLines(parsed: UsbAudioDevice, control: UsbAudioControl): List<String> = buildList {
    add("结构: UAC${parsed.uacVersion}，控制接口 #${parsed.controlInterfaceNumber}，可播放格式 ${parsed.outputs.size} 个")
    parsed.outputs.forEach { format ->
        val rateText = if (format.continuousRates) {
            "连续 ${format.minRate}..${format.maxRate}"
        } else {
            "离散 ${format.rates.joinToString(" ").ifEmpty { "未声明" }}"
        }
        // 同步方式与反馈端点是「要不要设采样率」的前提：自适应/同步设备跟着包速率走，异步设备才非设不可
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
 * 另找一条与采样率无关的类请求探一次路。
 *
 * 时钟档位读不到时，是「设备只拒这一个请求」还是「所有类请求都不被应答」，只能靠另一条请求分流：
 * 前者要往设备实现上查，后者要往宿主侧的管道上查，两条路的下一步完全不同。
 */
private fun MutableList<String>.appendClassRequestProbe(parsed: UsbAudioDevice, control: UsbAudioControl) {
    // 探的必须是本应用在独占路径上真正会写音量的那个单元：录音链的单元可能因功能未启用而拒答，
    // 拿它探路会把「设备不答类请求」与「这个单元不活跃」混成同一现象
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

// 端点与实体编号都是单字节，按两位十六进制给出，便于与抓包对照
private fun hex(value: Int): String = "0x" + value.toString(16).uppercase()

private fun clockKindName(kind: UsbAudioClock.Kind): String = when (kind) {
    UsbAudioClock.Kind.SOURCE -> "源"
    UsbAudioClock.Kind.SELECTOR -> "选择器"
    UsbAudioClock.Kind.MULTIPLIER -> "倍频器"
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
 * 授权本身不在此申请：弹窗属于交互，由界面在合适的时机发起（见 [UsbAccessRequest]）。
 */
internal object UsbAudioCapabilities {

    /**
     * 本次运行期间由独占路径读到的设备声明。
     *
     * 只读与独占两条路拿到的不是同一份答案：接口被 claim 之前，设备可能根本不答类请求（实测 KT02H20
     * 即如此，见 `docs/注意事项.md`），于是一份本该有的档位在分享出来的报告里成了「未声明」。独占路径
     * 既然已经读到，就留一份在这里——报告优先引用它，比空着或只报平台值更接近事实。
     *
     * 只在进程内有效：重启后若没再独占过，本来也没有可引用的真值。
     */
    @Volatile
    private var rememberedDeclared: UsbExclusiveDeviceInfo? = null

    /** 记下独占路径读到的声明；空档位不记，免得把「这次没读到」盖在真值上 */
    fun remember(info: UsbExclusiveDeviceInfo) {
        if (info.supportedSampleRates.isEmpty()) return
        rememberedDeclared = info
    }

    /** 取本次运行期间读到的声明；产品名对不上（换了一台解码器）时不返回旧值 */
    fun remembered(preferProductName: String?): UsbExclusiveDeviceInfo? =
        rememberedDeclared?.takeIf { usbProductNameMatches(preferProductName, it.name) }

    /**
     * @param preferProductName 优先读取的产品名：多台解码器同接时用它挑出正在发声的那一台
     */
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
     * 描述符结构摘要，供诊断「设备声明了档位，为什么一个也读不到」。
     *
     * 档位读不到有一串可能：终端没解析出来、终端与时钟没接上、时钟实体没解析出来、或控制传输本身失败。
     * 四者在 `supportedSampleRates` 里都表现为空表，只能靠结构本身分辨，故逐层把解析结果摊开。
     * 每行都带实体编号，可与 USB 描述符抓包直接对照。
     */
    fun describeStructure(context: Context, preferProductName: String? = null): List<String>? =
        withParsedDevice(context, preferProductName) { _, parsed, control ->
            usbAudioStructureLines(parsed, control)
        }

    /**
     * 打开设备、解析描述符、交给 [block]，随后关闭。
     *
     * 解析失败或没有可播放格式一律视为读不到：调用方拿到 null 就退回平台上报值，不在这里区分原因。
     */
    private fun <T> withParsedDevice(
        context: Context,
        preferProductName: String?,
        block: (UsbDevice, UsbAudioDevice, UsbAudioControl) -> T,
    ): T? {
        val manager = context.getSystemService(UsbManager::class.java) ?: return null
        val candidates = UsbExclusiveDac.attachedDacs(manager)
        // 名称来自两处（ALSA 声卡名 / 描述符 iProduct），须归一后再比；归一后仍对不上且不唯一时不猜
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
