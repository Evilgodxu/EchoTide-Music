package com.yichao.evilgodxu.data.music.playback.usb

/**
 * USB Audio Class 描述符的模型与解析。
 *
 * 独占直出必须自己决定"按什么格式、什么采样率把数据交给设备"，而这些答案只存在于设备的类特定描述符里：
 * 平台侧 API 只暴露接口与端点的通用字段（类、子类、端点类型、最大包长），格式类型、子槽字节、位深、
 * 采样率档位、时钟实体与音量能力一概读不到。故本文件把
 * [android.hardware.usb.UsbDeviceConnection.getRawDescriptors] 给出的原始描述符流解析成模型，
 * 供后续按源格式选定备用设置与时钟。
 *
 * 解析是纯函数、不依赖 Android 与设备：原始字节进、模型出，故可在 JVM 上直接验证。
 */

/** 等时端点的同步类型（bmAttributes 位 2..3）。异步设备的实际速率由设备自行决定，需读反馈端点跟随。 */
enum class UsbAudioSyncType {
    NONE,
    ASYNCHRONOUS,
    ADAPTIVE,
    SYNCHRONOUS,
}

/**
 * 一个可播放的备用设置：AudioStreaming 接口的某个 alt setting，且带等时 OUT 端点。
 *
 * 只收下同时具备格式描述与等时 OUT 端点的备用设置：alt setting 0 表示零带宽、没有端点，
 * 缺少格式描述的备用设置也无从据此建流，两者都不是可播放的目标。
 */
data class UsbAudioOutputFormat(
    val interfaceNumber: Int,
    val altSetting: Int,
    val endpointAddress: Int,
    /** 每个服务间隔的最大字节数，已按 wMaxPacketSize 的高两位（每微帧附加事务数）折算 */
    val maxPacketBytes: Int,
    /** bInterval：服务间隔；高速下按 2^(bInterval-1) 个微帧换算 */
    val interval: Int,
    val syncType: UsbAudioSyncType,
    /** 显式反馈端点地址，0 表示没有（同步与自适应设备不需要跟随） */
    val feedbackEndpoint: Int,
    val feedbackMaxPacketBytes: Int,
    val feedbackInterval: Int,
    /** 格式标签，1 表示 PCM（Type I）；非 PCM 的备用设置不参与直出 */
    val formatTag: Int,
    val channels: Int,
    /** 每样本子槽字节数：写出时按它决定每帧的排布 */
    val subslotBytes: Int,
    /** 有效位深，可能小于子槽位数（如 24 位放在 4 字节槽里） */
    val bitResolution: Int,
    val continuousRates: Boolean,
    val minRate: Int,
    val maxRate: Int,
    /** 离散采样率档位；[continuousRates] 为真时为空 */
    val rates: List<Int>,
    /** UAC1：采样率在端点上用 SET_CUR 设置；UAC2 恒为假（走时钟实体） */
    val rateControlOnEndpoint: Boolean,
    /** UAC2：馈入该格式的时钟实体编号，0 表示未解析出 */
    val clockId: Int,
    /** AS_GENERAL 的 bTerminalLink：该流接口所连接的终端，用于回溯音量与时钟 */
    val terminalLink: Int,
) {
    /** 每帧字节数：写出侧按它换算帧数 */
    val frameBytes: Int get() = channels * subslotBytes

    fun supportsRate(rate: Int): Boolean =
        if (continuousRates) rate in minRate..maxRate else rates.contains(rate)
}

/** Feature Unit：音量的承载实体。音量始终交给设备硬件设置，数据本身不参与缩放。 */
data class UsbAudioFeatureUnit(
    val unitId: Int,
    val sourceId: Int,
    val masterMute: Boolean,
    val masterVolume: Boolean,
    /** 逐声道的音量控制能力，下标 0 对应声道 1 */
    val channelVolume: List<Boolean>,
) {
    val hasVolume: Boolean get() = masterVolume || channelVolume.any { it }
}

/** UAC2 时钟实体。采样率只能设在时钟源上，选择器与倍频器负责把源接到某个终端。 */
data class UsbAudioClock(
    val kind: Kind,
    val id: Int,
    /** 选择器的输入引脚、倍频器的上游时钟；时钟源为空 */
    val inputs: List<Int>,
    /** 时钟源：宿主可否设置采样率 */
    val frequencyWritable: Boolean,
    /** 选择器：宿主可否切换引脚 */
    val selectorWritable: Boolean,
) {
    enum class Kind { SOURCE, SELECTOR, MULTIPLIER }
}

/**
 * 单元链的一环。
 *
 * 只保留回溯所需的三类与它们的连接关系：经过混音器与选择器单元之后，"哪个 Feature Unit 服务于本播放
 * 链路"已不再唯一，故回溯遇到它们即停止，宁可退回回退分支也不把音量设到错误的单元上。
 */
data class UsbAudioUnit(
    val kind: Kind,
    val id: Int,
    val sourceId: Int,
    /** UAC2 终端：馈入该终端的时钟实体 */
    val clockId: Int,
) {
    enum class Kind { INPUT_TERMINAL, OUTPUT_TERMINAL, FEATURE, OTHER }
}

/** 一台 UAC 设备的解析结果。 */
data class UsbAudioDevice(
    /** 1 或 2；0 表示没有找到 AudioControl 接口 */
    val uacVersion: Int,
    val controlInterfaceNumber: Int,
    val outputs: List<UsbAudioOutputFormat>,
    val featureUnits: List<UsbAudioFeatureUnit>,
    val clocks: List<UsbAudioClock>,
    val units: List<UsbAudioUnit>,
) {
    fun clock(id: Int): UsbAudioClock? = clocks.firstOrNull { it.id == id }

    /**
     * 本播放链路可用的 Feature Unit。
     *
     * 从每个输出终端沿 sourceId 往回走，遇到具备音量能力的 Feature Unit 即记为候选，直到抵达本流接口
     * 所连的输入终端。之所以要认准这个终点：同一台设备上可能还有服务于录音链路的 Feature Unit，
     * 只按"离得近"取会把音量设到错误的单元上。
     *
     * 回退一支用于终端链路不完整（部分厂商省略输出终端）的设备：直接取由本流终端馈入的 Feature Unit。
     */
    fun playbackFeatureUnit(format: UsbAudioOutputFormat): UsbAudioFeatureUnit? {
        val unitById = units.associateBy { it.id }
        val featureById = featureUnits.associateBy { it.unitId }

        for (output in units.filter { it.kind == UsbAudioUnit.Kind.OUTPUT_TERMINAL }) {
            var candidate: UsbAudioFeatureUnit? = null
            var id = output.sourceId
            var hops = 0
            while (hops++ < MAX_UNIT_HOPS) {
                val unit = unitById[id] ?: break
                if (unit.kind == UsbAudioUnit.Kind.FEATURE) {
                    if (candidate == null) {
                        candidate = featureById[unit.id]?.takeIf { it.hasVolume }
                    }
                    id = unit.sourceId
                    continue
                }
                if (unit.kind == UsbAudioUnit.Kind.INPUT_TERMINAL && unit.id == format.terminalLink) {
                    candidate?.let { return it }
                }
                break
            }
        }
        return featureUnits.firstOrNull { it.sourceId == format.terminalLink && it.hasVolume }
    }

    /**
     * 把一个时钟实体回溯到时钟源。
     *
     * 选择器与倍频器只是通路：真正的采样率只能设在时钟源上。选择器当前选中哪一路取决于设备状态，
     * 由 [selectedPin] 提供（返回 1 起的引脚号，0 或越界按第一路处理）——解不出时按第一路走，
     * 与内核驱动在拿不到状态时的处理一致。
     */
    fun resolveClockSource(clockId: Int, selectedPin: (selectorId: Int) -> Int): Int {
        var current = clockId
        var hops = 0
        while (hops++ < MAX_CLOCK_HOPS) {
            val clock = clock(current) ?: return 0
            if (clock.kind == UsbAudioClock.Kind.SOURCE) return clock.id
            if (clock.inputs.isEmpty()) return 0
            var index = 0
            if (clock.kind == UsbAudioClock.Kind.SELECTOR) {
                val pin = selectedPin(clock.id)
                if (pin in 1..clock.inputs.size) index = pin - 1
            }
            current = clock.inputs[index]
        }
        return 0
    }
}

private const val MAX_UNIT_HOPS = 16
private const val MAX_CLOCK_HOPS = 8

/** 连续范围展开时保留的常见音频采样率。区间内其余取值不取：设备通常并不真正支持它们。 */
private val COMMON_AUDIO_RATES = longArrayOf(
    32000, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000,
)

/**
 * 原始描述符流的解析。
 *
 * 只解析第一个配置：设备在当前配置下工作，其余配置不在直出可达范围内。
 * 描述符流由 [android.hardware.usb.UsbDeviceConnection.getRawDescriptors] 给出，
 * 形如「设备描述符 + 配置描述符 + 接口/端点/类特定描述符」的连续字节流。
 */
internal object UsbAudioDescriptorParser {

    private const val DESC_CONFIGURATION = 0x02
    private const val DESC_INTERFACE = 0x04
    private const val DESC_ENDPOINT = 0x05
    private const val DESC_CS_INTERFACE = 0x24
    private const val DESC_CS_ENDPOINT = 0x25

    private const val CLASS_AUDIO = 0x01
    private const val SUBCLASS_CONTROL = 0x01
    private const val SUBCLASS_STREAMING = 0x02

    /** 接口协议位取该值即为 UAC2 */
    private const val PROTOCOL_UAC2 = 0x20

    private const val AC_INPUT_TERMINAL = 0x02
    private const val AC_OUTPUT_TERMINAL = 0x03
    private const val AC_FEATURE_UNIT = 0x06
    private const val AC2_CLOCK_SOURCE = 0x0A
    private const val AC2_CLOCK_SELECTOR = 0x0B
    private const val AC2_CLOCK_MULTIPLIER = 0x0C

    private const val AS_GENERAL = 0x01
    private const val AS_FORMAT_TYPE = 0x02
    private const val FORMAT_TYPE_I = 0x01
    private const val EP_GENERAL = 0x01

    /** 等时端点的类型与用途位 */
    private const val XFER_TYPE_MASK = 0x03
    private const val XFER_TYPE_ISOCHRONOUS = 0x01
    private const val SYNC_TYPE_SHIFT = 2
    private const val SYNC_TYPE_MASK = 0x03
    private const val USAGE_TYPE_SHIFT = 4
    private const val USAGE_TYPE_MASK = 0x03
    private const val USAGE_TYPE_FEEDBACK = 0x01

    private const val DIRECTION_IN = 0x80

    fun parse(raw: ByteArray): UsbAudioDevice {
        val units = mutableListOf<UsbAudioUnit>()
        val featureUnits = mutableListOf<UsbAudioFeatureUnit>()
        val clocks = mutableListOf<UsbAudioClock>()
        val outputs = mutableListOf<UsbAudioOutputFormat>()

        var uacVersion = 0
        var controlInterface = -1
        var area = Area.NONE
        var configsSeen = 0

        // 当前备用设置的累积状态。alt setting 之间由接口描述符分隔，故遇到新接口时结算上一个
        var alt = AltBuilder()

        fun finishAlt() {
            if (area == Area.STREAMING) alt.build()?.let { outputs += it }
            alt = AltBuilder()
        }

        var start = 0
        while (start + 2 <= raw.size) {
            val length = raw.u8(start)
            val type = raw.u8(start + 1)
            // 长度不合法时停止：继续推进只会读到流之外
            if (length < 2 || start + length > raw.size) break
            val next = start + length

            when (type) {
                DESC_CONFIGURATION -> {
                    if (++configsSeen > 1) break
                }

                DESC_INTERFACE -> if (length >= 9) {
                    finishAlt()
                    val interfaceNumber = raw.u8(start + 2)
                    val interfaceClass = raw.u8(start + 5)
                    val subclass = raw.u8(start + 6)
                    val protocol = raw.u8(start + 7)
                    when {
                        interfaceClass == CLASS_AUDIO && subclass == SUBCLASS_CONTROL -> {
                            area = Area.CONTROL
                            controlInterface = interfaceNumber
                            uacVersion = if (protocol == PROTOCOL_UAC2) 2 else 1
                        }

                        interfaceClass == CLASS_AUDIO && subclass == SUBCLASS_STREAMING -> {
                            area = Area.STREAMING
                            alt = AltBuilder().apply {
                                this.interfaceNumber = interfaceNumber
                                altSetting = raw.u8(start + 3)
                            }
                        }

                        else -> area = Area.OTHER
                    }
                }

                DESC_ENDPOINT -> if (length >= 7 && area == Area.STREAMING) {
                    alt.onEndpoint(
                        address = raw.u8(start + 2),
                        attributes = raw.u8(start + 3),
                        maxPacket = raw.u16(start + 4),
                        interval = raw.u8(start + 6),
                        // UAC1 的等时端点比通用端点多两位：bRefresh 与 bSynchAddress
                        synchAddress = if (length >= 9) raw.u8(start + 8) else 0,
                    )
                }

                DESC_CS_INTERFACE -> if (length >= 3) {
                    when (area) {
                        Area.CONTROL -> when (uacVersion) {
                            1 -> parseUac1Control(raw, start, length, units, featureUnits)
                            2 -> parseUac2Control(raw, start, length, units, featureUnits, clocks)
                        }

                        Area.STREAMING -> alt.onClassSpecific(raw, start, length, uacVersion)
                        else -> Unit
                    }
                }

                DESC_CS_ENDPOINT -> if (length >= 4 && area == Area.STREAMING) {
                    if (uacVersion == 1 && raw.u8(start + 2) == EP_GENERAL) {
                        alt.rateControlOnEndpoint = (raw.u8(start + 3) and 0x01) != 0
                    }
                }
            }
            start = next
        }
        finishAlt()

        // UAC2 的采样率出自时钟实体：经 AS_GENERAL 的终端链接找到输入终端，再取它声明的时钟
        val resolved = if (uacVersion == 2) {
            val terminalClock = units
                .filter { it.kind == UsbAudioUnit.Kind.INPUT_TERMINAL }
                .associate { it.id to it.clockId }
            outputs.map { it.copy(clockId = terminalClock[it.terminalLink] ?: 0) }
        } else {
            outputs
        }

        return UsbAudioDevice(
            uacVersion = uacVersion,
            controlInterfaceNumber = controlInterface,
            outputs = resolved,
            featureUnits = featureUnits,
            clocks = clocks,
            units = units,
        )
    }

    private fun parseUac1Control(
        d: ByteArray,
        start: Int,
        length: Int,
        units: MutableList<UsbAudioUnit>,
        featureUnits: MutableList<UsbAudioFeatureUnit>,
    ) {
        when (d.u8(start + 2)) {
            AC_INPUT_TERMINAL -> if (length >= 6) {
                units += UsbAudioUnit(
                    kind = UsbAudioUnit.Kind.INPUT_TERMINAL,
                    id = d.u8(start + 3),
                    sourceId = 0,
                    clockId = 0,
                )
            }

            AC_OUTPUT_TERMINAL -> if (length >= 8) {
                units += UsbAudioUnit(
                    kind = UsbAudioUnit.Kind.OUTPUT_TERMINAL,
                    id = d.u8(start + 3),
                    sourceId = d.u8(start + 7),
                    clockId = 0,
                )
            }

            AC_FEATURE_UNIT -> if (length >= 7) {
                val unitId = d.u8(start + 3)
                val sourceId = d.u8(start + 4)
                val controlSize = d.u8(start + 5)
                var masterMute = false
                var masterVolume = false
                val channelVolume = mutableListOf<Boolean>()
                // 条目为「主控制 + 每声道一个」，UAC1 的条目宽度由描述符自述
                if (controlSize > 0) {
                    val entries = (length - 7) / controlSize
                    for (i in 0 until entries) {
                        val controls = d.u8(start + 6 + i * controlSize)
                        if (i == 0) {
                            masterMute = (controls and 0x01) != 0
                            masterVolume = (controls and 0x02) != 0
                        } else {
                            channelVolume += (controls and 0x02) != 0
                        }
                    }
                }
                featureUnits += UsbAudioFeatureUnit(
                    unitId = unitId,
                    sourceId = sourceId,
                    masterMute = masterMute,
                    masterVolume = masterVolume,
                    channelVolume = channelVolume,
                )
                units += UsbAudioUnit(UsbAudioUnit.Kind.FEATURE, unitId, sourceId, clockId = 0)
            }

            // 其余单元只需记住连接关系，回溯在遇到它们时即停止
            else -> if (length >= 4 && d.u8(start + 2) > AC_INPUT_TERMINAL) {
                units += UsbAudioUnit(
                    kind = UsbAudioUnit.Kind.OTHER,
                    id = d.u8(start + 3),
                    sourceId = 0,
                    clockId = 0,
                )
            }
        }
    }

    private fun parseUac2Control(
        d: ByteArray,
        start: Int,
        length: Int,
        units: MutableList<UsbAudioUnit>,
        featureUnits: MutableList<UsbAudioFeatureUnit>,
        clocks: MutableList<UsbAudioClock>,
    ) {
        when (d.u8(start + 2)) {
            AC_INPUT_TERMINAL -> if (length >= 17) {
                units += UsbAudioUnit(
                    kind = UsbAudioUnit.Kind.INPUT_TERMINAL,
                    id = d.u8(start + 3),
                    sourceId = 0,
                    clockId = d.u8(start + 7),
                )
            }

            AC_OUTPUT_TERMINAL -> if (length >= 12) {
                units += UsbAudioUnit(
                    kind = UsbAudioUnit.Kind.OUTPUT_TERMINAL,
                    id = d.u8(start + 3),
                    sourceId = d.u8(start + 7),
                    clockId = d.u8(start + 8),
                )
            }

            AC_FEATURE_UNIT -> if (length >= 10) {
                val unitId = d.u8(start + 3)
                val sourceId = d.u8(start + 4)
                // UAC2 每条目固定 4 字节，控制位为 2 位字段，「宿主可写」取 0b11
                val entries = (length - 6) / 4
                var masterMute = false
                var masterVolume = false
                val channelVolume = mutableListOf<Boolean>()
                for (i in 0 until entries) {
                    val controls = d.u32(start + 5 + 4 * i)
                    if (i == 0) {
                        masterMute = controls.writable(0)
                        masterVolume = controls.writable(1)
                    } else {
                        channelVolume += controls.writable(1)
                    }
                }
                featureUnits += UsbAudioFeatureUnit(
                    unitId = unitId,
                    sourceId = sourceId,
                    masterMute = masterMute,
                    masterVolume = masterVolume,
                    channelVolume = channelVolume,
                )
                units += UsbAudioUnit(UsbAudioUnit.Kind.FEATURE, unitId, sourceId, clockId = 0)
            }

            AC2_CLOCK_SOURCE -> if (length >= 8) {
                clocks += UsbAudioClock(
                    kind = UsbAudioClock.Kind.SOURCE,
                    id = d.u8(start + 3),
                    inputs = emptyList(),
                    frequencyWritable = d.u8(start + 5).toLong().writable(0),
                    selectorWritable = false,
                )
            }

            AC2_CLOCK_SELECTOR -> if (length >= 7) {
                val pins = d.u8(start + 4)
                clocks += UsbAudioClock(
                    kind = UsbAudioClock.Kind.SELECTOR,
                    id = d.u8(start + 3),
                    inputs = (0 until pins).filter { 5 + it < length }.map { d.u8(start + 5 + it) },
                    frequencyWritable = false,
                    selectorWritable = 5 + pins < length &&
                        d.u8(start + 5 + pins).toLong().writable(0),
                )
            }

            AC2_CLOCK_MULTIPLIER -> if (length >= 7) {
                clocks += UsbAudioClock(
                    kind = UsbAudioClock.Kind.MULTIPLIER,
                    id = d.u8(start + 3),
                    inputs = listOf(d.u8(start + 4)),
                    frequencyWritable = false,
                    selectorWritable = false,
                )
            }

            else -> if (length >= 4 && d.u8(start + 2) > AC_INPUT_TERMINAL) {
                units += UsbAudioUnit(
                    kind = UsbAudioUnit.Kind.OTHER,
                    id = d.u8(start + 3),
                    sourceId = 0,
                    clockId = 0,
                )
            }
        }
    }

    /** 一个备用设置的累积状态；切换到下一个接口时结算 */
    private class AltBuilder {
        var interfaceNumber = 0
        var altSetting = 0
        var rateControlOnEndpoint = false

        private var formatTag = 0
        private var channels = 0
        private var subslotBytes = 0
        private var bitResolution = 0
        private var continuousRates = false
        private var minRate = 0
        private var maxRate = 0
        private var rates: List<Int> = emptyList()
        private var terminalLink = 0
        private var hasFormat = false

        private var endpointAddress = 0
        private var maxPacketBytes = 0
        private var interval = 0
        private var syncType = UsbAudioSyncType.NONE
        private var feedbackEndpoint = 0
        private var feedbackMaxPacketBytes = 0
        private var feedbackInterval = 0
        private var hasOutEndpoint = false

        /**
         * 端点分类：等时 OUT 且用途非反馈的是数据端点；等时 IN 且用途为反馈（或地址与 UAC1 在数据端点上
         * 声明的 bSynchAddress 相符）的是反馈端点。UAC1 没有"反馈"用途位，只能靠地址配对识别。
         */
        fun onEndpoint(
            address: Int,
            attributes: Int,
            maxPacket: Int,
            interval: Int,
            synchAddress: Int,
        ) {
            if ((attributes and XFER_TYPE_MASK) != XFER_TYPE_ISOCHRONOUS) return
            val isFeedback = (attributes shr USAGE_TYPE_SHIFT) and USAGE_TYPE_MASK == USAGE_TYPE_FEEDBACK
            // wMaxPacketSize 高两位为每微帧的附加事务数
            val packetBytes = (maxPacket and 0x7FF) * (1 + ((maxPacket shr 11) and 0x03))
            val directionIn = (address and DIRECTION_IN) != 0
            if (!directionIn && !isFeedback) {
                endpointAddress = address
                maxPacketBytes = packetBytes
                this.interval = interval
                syncType = SYNC_TYPE_ORDER[(attributes shr SYNC_TYPE_SHIFT) and SYNC_TYPE_MASK]
                if (syncType == UsbAudioSyncType.ASYNCHRONOUS && synchAddress != 0) {
                    feedbackEndpoint = synchAddress
                }
                hasOutEndpoint = true
            } else if (directionIn && (isFeedback || address == feedbackEndpoint)) {
                feedbackEndpoint = address
                feedbackMaxPacketBytes = packetBytes
                feedbackInterval = interval
            }
        }

        fun onClassSpecific(d: ByteArray, start: Int, length: Int, uacVersion: Int) {
            when {
                // UAC1：终端链接 + 格式标签；UAC2：终端链接 + bmFormats 位 0 表示 PCM，声道数在此声明
                uacVersion == 1 && d.u8(start + 2) == AS_GENERAL && length >= 7 -> {
                    terminalLink = d.u8(start + 3)
                    formatTag = d.u16(start + 5)
                }

                uacVersion == 2 && d.u8(start + 2) == AS_GENERAL && length >= 16 -> {
                    terminalLink = d.u8(start + 3)
                    val pcm = d.u8(start + 5) == FORMAT_TYPE_I && (d.u32(start + 6) and 0x1L) != 0L
                    formatTag = if (pcm) 1 else 0
                    channels = d.u8(start + 10)
                }

                uacVersion == 1 && d.u8(start + 2) == AS_FORMAT_TYPE && length >= 8 &&
                    d.u8(start + 3) == FORMAT_TYPE_I -> {
                    channels = d.u8(start + 4)
                    subslotBytes = d.u8(start + 5)
                    bitResolution = d.u8(start + 6)
                    val freqCount = d.u8(start + 7)
                    if (freqCount == 0 && length >= 14) {
                        continuousRates = true
                        minRate = d.u24(start + 8)
                        maxRate = d.u24(start + 11)
                    } else {
                        rates = (0 until freqCount)
                            .filter { 8 + 3 * (it + 1) <= length }
                            .map { d.u24(start + 8 + 3 * it) }
                    }
                    hasFormat = true
                }

                // UAC2 的档位不出现在描述符里，改由时钟源在运行期应答
                uacVersion == 2 && d.u8(start + 2) == AS_FORMAT_TYPE && length >= 6 &&
                    d.u8(start + 3) == FORMAT_TYPE_I -> {
                    subslotBytes = d.u8(start + 4)
                    bitResolution = d.u8(start + 5)
                    hasFormat = true
                }
            }
        }

        /** 不具备格式描述、等时 OUT 端点，或仅为 alt 0（零带宽）时，不是可播放目标 */
        fun build(): UsbAudioOutputFormat? {
            if (!hasFormat || !hasOutEndpoint || altSetting == 0) return null
            return UsbAudioOutputFormat(
                interfaceNumber = interfaceNumber,
                altSetting = altSetting,
                endpointAddress = endpointAddress,
                maxPacketBytes = maxPacketBytes,
                interval = interval,
                syncType = syncType,
                feedbackEndpoint = feedbackEndpoint,
                feedbackMaxPacketBytes = feedbackMaxPacketBytes,
                feedbackInterval = feedbackInterval,
                formatTag = formatTag,
                channels = channels,
                subslotBytes = subslotBytes,
                bitResolution = bitResolution,
                continuousRates = continuousRates,
                minRate = minRate,
                maxRate = maxRate,
                rates = rates,
                rateControlOnEndpoint = rateControlOnEndpoint,
                clockId = 0,
                terminalLink = terminalLink,
            )
        }
    }

    private enum class Area { NONE, CONTROL, STREAMING, OTHER }

    /** 同步类型取值与 [UsbAudioSyncType] 的声明顺序一致，故按位取值可直接索引 */
    private val SYNC_TYPE_ORDER = arrayOf(
        UsbAudioSyncType.NONE,
        UsbAudioSyncType.ASYNCHRONOUS,
        UsbAudioSyncType.ADAPTIVE,
        UsbAudioSyncType.SYNCHRONOUS,
    )
}

/**
 * 展开 UAC2 时钟源的 RANGE 应答：先给子区间数，每个区间 12 字节（下限、上限、步长），各为 32 位。
 *
 * 区间内只保留常见音频采样率。把算术上落在区间里的每个值都当成可用档位是错的：不少设备的区间宽到
 * 覆盖大量非音频频率，而它实际只在其中少数档位上工作。
 *
 * @param length 可读字节数，自 [offset] 起算；应答长度由调用方从控制传输的返回长度给出。
 */
internal fun usbAudioRatesFromRange(data: ByteArray, offset: Int, length: Int): List<Int> {
    if (length < 2) return emptyList()
    val ranges = data.u16(offset)
    val rates = mutableListOf<Int>()
    for (i in 0 until ranges) {
        // 区间条目须完整落在可读范围内，否则停止而非读越界
        if (12 * (i + 1) > length - 2) break
        val entry = offset + 2 + 12 * i
        rates += usbAudioRatesInRange(data.u32(entry), data.u32(entry + 4), data.u32(entry + 8))
    }
    return rates.distinct().sorted()
}

/**
 * 一个采样率区间里可用的档位。
 *
 * 上下限相同是单点档位；步长为 0 表示连续。UAC1 的连续区间与 UAC2 的 RANGE 应答走同一条口径，
 * 两处因此给出同一份档位表——同一个区间不该因声明它的是哪个版本而报出不同的支持范围。
 */
internal fun usbAudioRatesInRange(low: Long, high: Long, step: Long): List<Int> {
    if (low == high) return if (low in 1L..MAX_AUDIO_RATE.toLong()) listOf(low.toInt()) else emptyList()
    return COMMON_AUDIO_RATES
        .filter { it in low..high && (step == 0L || (it - low) % step == 0L) }
        .map { it.toInt() }
}

private const val MAX_AUDIO_RATE = 768000

/** UAC2 的控制位为 2 位字段，0b11 才表示宿主可写 */
private fun Long.writable(field: Int): Boolean = (this shr (2 * field)) and 0x3L == 0x3L

private fun ByteArray.u8(pos: Int): Int = this[pos].toInt() and 0xFF

private fun ByteArray.u16(pos: Int): Int = u8(pos) or (u8(pos + 1) shl 8)

private fun ByteArray.u24(pos: Int): Int = u8(pos) or (u8(pos + 1) shl 8) or (u8(pos + 2) shl 16)

private fun ByteArray.u32(pos: Int): Long =
    u8(pos).toLong() or (u8(pos + 1).toLong() shl 8) or (u8(pos + 2).toLong() shl 16) or
        (u8(pos + 3).toLong() shl 24)
