package com.yichao.evilgodxu.data.music.playback.usb

/**
 * UAC 描述符的模型与解析。
 *
 * 平台 API 读不到类特定字段，故解析原始描述符。
 * 解析是纯函数，可在 JVM 验证。
 */

/** 等时端点同步类型（bmAttributes 位 2..3）；异步需读反馈端点跟随 */
enum class UsbAudioSyncType {
    NONE,
    ASYNCHRONOUS,
    ADAPTIVE,
    SYNCHRONOUS,
}

/**
 * 一个可播放的备用设置：带等时 OUT 端点。
 * alt 0 为零带宽，缺格式也不收。
 */
data class UsbAudioOutputFormat(
    val interfaceNumber: Int,
    val altSetting: Int,
    val endpointAddress: Int,
    /** 每服务间隔最大字节，已折算 wMaxPacketSize 高两位附加事务 */
    val maxPacketBytes: Int,
    /** bInterval 服务间隔；高速按 2^(bInterval-1) 微帧换算 */
    val interval: Int,
    val syncType: UsbAudioSyncType,
    /** 反馈端点地址，0 表示没有 */
    val feedbackEndpoint: Int,
    val feedbackMaxPacketBytes: Int,
    val feedbackInterval: Int,
    /** 格式标签，1 为 PCM，非 PCM 不参与直出 */
    val formatTag: Int,
    val channels: Int,
    /** 每样本子槽字节数，决定每帧排布 */
    val subslotBytes: Int,
    /** 有效位深，可小于子槽位数（如 24 位放 4 字节槽） */
    val bitResolution: Int,
    val continuousRates: Boolean,
    val minRate: Int,
    val maxRate: Int,
    /** 离散采样率档位，连续区间时为空 */
    val rates: List<Int>,
    /** UAC1 端点设速率；UAC2 恒假，走时钟实体 */
    val rateControlOnEndpoint: Boolean,
    /** 馈入该格式的时钟实体编号，0 为未解析出 */
    val clockId: Int,
    /** bTerminalLink，本流连接的终端，用于回溯音量与时钟 */
    val terminalLink: Int,
) {
    /** 每帧字节数：写出侧按它换算帧数 */
    val frameBytes: Int get() = channels * subslotBytes

    fun supportsRate(rate: Int): Boolean =
        if (continuousRates) rate in minRate..maxRate else rates.contains(rate)
}

/** Feature Unit：音量承载实体，交给硬件设置，数据不缩放 */
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

/** UAC2 时钟实体；采样率只能设在时钟源上 */
data class UsbAudioClock(
    val kind: Kind,
    val id: Int,
    /** 选择器输入引脚或倍频器上游时钟，时钟源为空 */
    val inputs: List<Int>,
    /** 时钟源：宿主可否设置采样率 */
    val frequencyWritable: Boolean,
    /** 选择器：宿主可否切换引脚 */
    val selectorWritable: Boolean,
) {
    enum class Kind { SOURCE, SELECTOR, MULTIPLIER }
}

/**
 * 单元链的一环，只留回溯所需三类。
 * 遇混音器或选择器即停，宁可不设音量。
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
    /** 1 或 2；0 表示无 AudioControl 接口 */
    val uacVersion: Int,
    val controlInterfaceNumber: Int,
    val outputs: List<UsbAudioOutputFormat>,
    val featureUnits: List<UsbAudioFeatureUnit>,
    val clocks: List<UsbAudioClock>,
    val units: List<UsbAudioUnit>,
) {
    fun clock(id: Int): UsbAudioClock? = clocks.firstOrNull { it.id == id }

    /**
     * 本播放链的 Feature Unit，由输出回溯输入
     * 认准终点以避开录音链；不完整时取本流终端馈入的。
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
     * 把时钟实体回溯到时钟源。
     * 选择器按引脚走，0 或越界取第一路。
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

/** 展开连续范围时只保留常见音频采样率，设备未必真支持其余值 */
private val COMMON_AUDIO_RATES = longArrayOf(
    32000, 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000,
)

/**
 * 原始描述符流的解析。
 * 只解析第一个配置，其余不可达。
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

        // 当前备用设置的累积状态，遇新接口时结算上一个
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
                        // UAC1 端点另有 bRefresh 两字节
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

        // UAC2 采样率出自时钟：经终端链接找输入终端并取其时钟
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
                // 条目为主控制 + 每声道一个，宽度由描述符自述
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

            // 其余单元只记连接关系，回溯遇到即停
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
                // UAC2 每条目 4 字节，控制位 2 位，0b11 可写
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

    /** 一个备用设置的累积状态，切到下一个接口时结算 */
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
         * 等时 OUT 非反馈为数据端点，IN 反馈为反馈端点。
         * UAC1 无用途位，靠 bSynchAddress 配对
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
            // wMaxPacketSize 高两位为每微帧附加事务数
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
                // UAC1 取终端链接与格式标签，UAC2 另读
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

                // UAC2 档位不在描述符，由时钟源运行期应答
                uacVersion == 2 && d.u8(start + 2) == AS_FORMAT_TYPE && length >= 6 &&
                    d.u8(start + 3) == FORMAT_TYPE_I -> {
                    subslotBytes = d.u8(start + 4)
                    bitResolution = d.u8(start + 5)
                    hasFormat = true
                }
            }
        }

        /** 缺格式描述、缺等时 OUT 端点或为 alt 0 时不可播放 */
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

    /** 同步类型按位取值可直接索引，顺序与枚举一致 */
    private val SYNC_TYPE_ORDER = arrayOf(
        UsbAudioSyncType.NONE,
        UsbAudioSyncType.ASYNCHRONOUS,
        UsbAudioSyncType.ADAPTIVE,
        UsbAudioSyncType.SYNCHRONOUS,
    )
}

/**
 * 展开时钟源 RANGE：2 字节区间数 + 12 字节
 * 只留常见采样率，许多设备实际档位很少。
 */
internal fun usbAudioRatesFromRange(data: ByteArray, offset: Int, length: Int): List<Int> {
    if (length < 2) return emptyList()
    val ranges = data.u16(offset)
    val rates = mutableListOf<Int>()
    for (i in 0 until ranges) {
        // 条目须完整落在可读范围内，否则停止而非读越界
        if (12 * (i + 1) > length - 2) break
        val entry = offset + 2 + 12 * i
        rates += usbAudioRatesInRange(data.u32(entry), data.u32(entry + 4), data.u32(entry + 8))
    }
    return rates.distinct().sorted()
}

/**
 * 区间内可用的档位。
 * 上下限相同为单点；步长 0 为连续。
 */
internal fun usbAudioRatesInRange(low: Long, high: Long, step: Long): List<Int> {
    if (low == high) return if (low in 1L..MAX_AUDIO_RATE.toLong()) listOf(low.toInt()) else emptyList()
    return COMMON_AUDIO_RATES
        .filter { it in low..high && (step == 0L || (it - low) % step == 0L) }
        .map { it.toInt() }
}

private const val MAX_AUDIO_RATE = 768000

/** UAC2 控制位为 2 位字段，0b11 才表示宿主可写 */
private fun Long.writable(field: Int): Boolean = (this shr (2 * field)) and 0x3L == 0x3L

private fun ByteArray.u8(pos: Int): Int = this[pos].toInt() and 0xFF

private fun ByteArray.u16(pos: Int): Int = u8(pos) or (u8(pos + 1) shl 8)

private fun ByteArray.u24(pos: Int): Int = u8(pos) or (u8(pos + 1) shl 8) or (u8(pos + 2) shl 16)

private fun ByteArray.u32(pos: Int): Long =
    u8(pos).toLong() or (u8(pos + 1).toLong() shl 8) or (u8(pos + 2).toLong() shl 16) or
        (u8(pos + 3).toLong() shl 24)
