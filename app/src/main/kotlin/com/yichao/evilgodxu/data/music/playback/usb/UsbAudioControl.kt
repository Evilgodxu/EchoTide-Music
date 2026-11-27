package com.yichao.evilgodxu.data.music.playback.usb

import android.hardware.usb.UsbDeviceConnection
import com.yichao.evilgodxu.log.CrashLogManager

// 日志类名：控制请求仅出错时记录
private const val LOG_TAG = "UsbAudioControl"

// 控制请求 1s 超时即判失败，避免卡住播放线程
private const val CONTROL_TIMEOUT_MS = 1000

/**
 * UAC 类请求编码，抽成纯函数便于验证。
 * 参数固定，写错一位即静默无效。
 */
internal object UsbAudioRequests {

    /** bmRequestType：方向 | 类型 | 接收者 */
    const val OUT_INTERFACE = 0x21
    const val IN_INTERFACE = 0xA1
    const val OUT_ENDPOINT = 0x22
    const val IN_ENDPOINT = 0xA2

    /** UAC1 的类请求：读写各有独立的请求码 */
    const val SET_CUR = 0x01
    const val GET_CUR = 0x81
    const val GET_MIN = 0x82
    const val GET_MAX = 0x83
    const val GET_RES = 0x84

    /** UAC2 读写共用码，方向看 bmRequestType */
    const val UAC2_CUR = 0x01
    const val UAC2_RANGE = 0x02

    /** 控制选择子 */
    const val CONTROL_MUTE = 0x01
    const val CONTROL_VOLUME = 0x02
    const val CONTROL_SAMPLING_FREQ = 0x01
    const val CONTROL_CLOCK_SELECTOR = 0x01

    /** 特征单元：wValue = 选择子 << 8 | 声道（0 主控），wIndex = 单元号 << 8 | 接口号 */
    fun featureUnitValue(control: Int, channel: Int): Int = (control shl 8) or (channel and 0xFF)

    fun featureUnitIndex(unitId: Int, controlInterface: Int): Int =
        (unitId shl 8) or (controlInterface and 0xFF)

    /** 时钟实体：wValue = 选择子 << 8，wIndex = 时钟号 << 8 | 接口号 */
    fun clockValue(control: Int): Int = control shl 8

    fun clockIndex(clockId: Int, controlInterface: Int): Int =
        (clockId shl 8) or (controlInterface and 0xFF)

    /** UAC1 的采样率是 24 位小端 */
    fun uac1Rate(rate: Int): ByteArray =
        ByteArray(3) { index -> ((rate shr (8 * index)) and 0xFF).toByte() }

    /** UAC2 的采样率是 32 位小端 */
    fun uac2Rate(rate: Int): ByteArray =
        ByteArray(4) { index -> ((rate shr (8 * index)) and 0xFF).toByte() }

    /** 音量取值为 1/256 dB 的有符号 16 位 */
    fun volumeValue(value256: Int): ByteArray =
        ByteArray(2) { index -> ((value256 shr (8 * index)) and 0xFF).toByte() }

    fun unsigned16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    fun signed16(bytes: ByteArray, offset: Int): Int =
        unsigned16(bytes, offset).toShort().toInt()

    fun unsigned24(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
}

/** 特征单元声明的音量可调范围，单位 1/256 dB */
internal data class UsbAudioVolumeRange(val min: Int, val max: Int, val step: Int) {
    val usable: Boolean get() = max > min
}

/**
 * UAC2 特征单元 RANGE 应答解析。
 * 先 2 字节子区间数，每区间 6 字节（16 位值）。
 * 取第一个子区间；时钟实体区间为 12 字节。
 */
internal fun usbAudioVolumeRange(reply: ByteArray, length: Int): UsbAudioVolumeRange? {
    if (length < 8) return null
    if (UsbAudioRequests.unsigned16(reply, 0) == 0) return null
    return UsbAudioVolumeRange(
        min = UsbAudioRequests.signed16(reply, 2),
        max = UsbAudioRequests.signed16(reply, 4),
        step = UsbAudioRequests.signed16(reply, 6),
    )
}

/**
 * 采样率与音量控制，全走 controlTransfer
 */
internal class UsbAudioControl(
    private val connection: UsbDeviceConnection,
    private val device: UsbAudioDevice,
) {

    private val uacVersion = device.uacVersion
    private val controlInterface = device.controlInterfaceNumber

    /**
     * 逐格式回溯时钟源，0 表示回溯不到。
     * 选中引脚只能问设备，故由调用方给。
     */
    fun resolveClockSources(selectedPin: (selectorId: Int) -> Int): List<Int> =
        device.outputs.map { format -> device.resolveClockSource(format.clockId, selectedPin) }

    /** 读时钟源当前选中引脚，拿不到返回 0，由回溯按第一路处理 */
    fun selectedClockPin(selectorId: Int): Int {
        val reply = ByteArray(1)
        if (!controlIn(UsbAudioRequests.UAC2_CUR, UsbAudioRequests.clockValue(UsbAudioRequests.CONTROL_CLOCK_SELECTOR),
                UsbAudioRequests.clockIndex(selectorId, controlInterface), reply, reply.size)
        ) {
            return 0
        }
        return reply[0].toInt() and 0xFF
    }

    /**
     * 读时钟源档位，依次试：问满、两步问、读当前。
     * trace 收实际字节数，用于分辨不应答与拒绝。
     */
    fun readClockRates(clockSourceId: Int, trace: ((String) -> Unit)? = null): List<Int> {
        val index = UsbAudioRequests.clockIndex(clockSourceId, controlInterface)
        val value = UsbAudioRequests.clockValue(UsbAudioRequests.CONTROL_SAMPLING_FREQ)

        val bulk = ByteArray(MAX_RANGE_REPLY_BYTES)
        val bulkRead = transferIn(UsbAudioRequests.UAC2_RANGE, value, index, bulk)
        trace?.invoke("一次问满 ${bulk.size}B 得 $bulkRead")
        if (bulkRead >= 2) {
            val ranges = UsbAudioRequests.unsigned16(bulk, 0)
            if (ranges in 1..MAX_RANGE_ENTRIES && bulkRead >= 2 + 12 * ranges) {
                usbAudioRatesFromRange(bulk, 0, 2 + 12 * ranges)
                    .takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
        }

        val head = ByteArray(2)
        val headRead = transferIn(UsbAudioRequests.UAC2_RANGE, value, index, head)
        trace?.invoke("先问个数 2B 得 $headRead")
        if (headRead == head.size) {
            val ranges = UsbAudioRequests.unsigned16(head, 0)
            if (ranges in 1..MAX_RANGE_ENTRIES) {
                val reply = ByteArray(2 + 12 * ranges)
                val read = transferIn(UsbAudioRequests.UAC2_RANGE, value, index, reply)
                trace?.invoke("按个数取 ${reply.size}B 得 $read")
                if (read == reply.size) {
                    usbAudioRatesFromRange(reply, 0, read).takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        }

        val current = ByteArray(4)
        val currentRead = transferIn(UsbAudioRequests.UAC2_CUR, value, index, current)
        trace?.invoke("读当前值 4B 得 $currentRead")
        if (currentRead == current.size) {
            val rate = UsbAudioRequests.unsigned16(current, 0) or
                (UsbAudioRequests.unsigned16(current, 2) shl 16)
            if (rate > 0) return listOf(rate)
        }
        return emptyList()
    }

    /**
     * 在时钟源上设采样率，返回生效值。
     * 须先于切备用设置；写后等时钟重锁再回读。
     */
    fun setClockRate(clockSourceId: Int, rate: Int): Int {
        val index = UsbAudioRequests.clockIndex(clockSourceId, controlInterface)
        val value = UsbAudioRequests.clockValue(UsbAudioRequests.CONTROL_SAMPLING_FREQ)
        val readBack = {
            val current = ByteArray(4)
            if (controlIn(UsbAudioRequests.UAC2_CUR, value, index, current, current.size)) {
                UsbAudioRequests.unsigned16(current, 0) or
                    (UsbAudioRequests.unsigned16(current, 2) shl 16)
            } else {
                0
            }
        }
        val current = readBack()
        if (current == rate) return current
        // 不可写且有确定速率：不试写，直接如实回报
        device.clock(clockSourceId)?.takeIf { !it.frequencyWritable && current != 0 }?.let {
            return current
        }
        if (!controlOut(
                UsbAudioRequests.UAC2_CUR,
                value,
                index,
                UsbAudioRequests.uac2Rate(rate),
            )
        ) {
            return readBack()
        }
        Thread.sleep(CLOCK_RELOCK_WAIT_MS)
        return readBack()
    }

    /**
     * 在端点设采样率（UAC1）。
     * 须在切备用设置之后，与 UAC2 相反。
     */
    fun setEndpointRate(endpointAddress: Int, rate: Int): Int {
        val value = UsbAudioRequests.CONTROL_SAMPLING_FREQ shl 8
        controlOut(
            UsbAudioRequests.SET_CUR,
            value,
            endpointAddress,
            UsbAudioRequests.uac1Rate(rate),
            UsbAudioRequests.OUT_ENDPOINT,
        )
        val back = ByteArray(3)
        if (controlIn(
                UsbAudioRequests.GET_CUR,
                value,
                endpointAddress,
                back,
                back.size,
                UsbAudioRequests.IN_ENDPOINT,
            )
        ) {
            return UsbAudioRequests.unsigned24(back, 0)
        }
        return 0
    }

    /** 读音量范围，设备未暴露音量控制时返回 null */
    fun volumeRange(featureUnit: UsbAudioFeatureUnit): UsbAudioVolumeRange? {
        val channel = volumeChannel(featureUnit) ?: return null
        if (uacVersion == 2) {
            // UAC2 用 RANGE 一次取回，子区间为 16 位值
            val reply = ByteArray(2 + 6 * MAX_VOLUME_SUB_RANGES)
            val read = controlIn(
                UsbAudioRequests.UAC2_RANGE,
                UsbAudioRequests.featureUnitValue(UsbAudioRequests.CONTROL_VOLUME, channel),
                UsbAudioRequests.featureUnitIndex(featureUnit.unitId, controlInterface),
                reply,
                reply.size,
            )
            if (!read) return null
            return usbAudioVolumeRange(reply, reply.size)
        }
        val min = ByteArray(2)
        val max = ByteArray(2)
        val step = ByteArray(2)
        val index = UsbAudioRequests.featureUnitIndex(featureUnit.unitId, controlInterface)
        val value = UsbAudioRequests.featureUnitValue(UsbAudioRequests.CONTROL_VOLUME, channel)
        if (!controlIn(UsbAudioRequests.GET_MIN, value, index, min, min.size)) return null
        if (!controlIn(UsbAudioRequests.GET_MAX, value, index, max, max.size)) return null
        controlIn(UsbAudioRequests.GET_RES, value, index, step, step.size)
        return UsbAudioVolumeRange(
            min = UsbAudioRequests.signed16(min, 0),
            max = UsbAudioRequests.signed16(max, 0),
            step = UsbAudioRequests.signed16(step, 0),
        )
    }

    /** 读当前音量值 1/256 dB，读不到返回 null */
    fun currentVolume(featureUnit: UsbAudioFeatureUnit): Int? {
        val channel = volumeChannel(featureUnit) ?: return null
        val reply = ByteArray(2)
        val value = UsbAudioRequests.featureUnitValue(UsbAudioRequests.CONTROL_VOLUME, channel)
        val index = UsbAudioRequests.featureUnitIndex(featureUnit.unitId, controlInterface)
        val request = if (uacVersion == 2) UsbAudioRequests.UAC2_CUR else UsbAudioRequests.GET_CUR
        if (!controlIn(request, value, index, reply, reply.size)) return null
        return UsbAudioRequests.signed16(reply, 0)
    }

    /** 设置音量 1/256 dB，硬件缩放，数据本身不参与 */
    fun setVolume(featureUnit: UsbAudioFeatureUnit, value256: Int): Boolean {
        val channels = volumeChannels(featureUnit)
        if (channels.isEmpty()) return false
        val index = UsbAudioRequests.featureUnitIndex(featureUnit.unitId, controlInterface)
        val request = if (uacVersion == 2) UsbAudioRequests.UAC2_CUR else UsbAudioRequests.SET_CUR
        return channels.all { channel ->
            controlOut(
                request,
                UsbAudioRequests.featureUnitValue(UsbAudioRequests.CONTROL_VOLUME, channel),
                index,
                UsbAudioRequests.volumeValue(value256),
            )
        }
    }

    /** 主控可写只设主控，否则逐个设可写声道 */
    private fun volumeChannels(featureUnit: UsbAudioFeatureUnit): List<Int> = when {
        !featureUnit.hasVolume -> emptyList()
        featureUnit.masterVolume -> listOf(MASTER_CHANNEL)
        else -> featureUnit.channelVolume
            .mapIndexedNotNull { index, writable -> if (writable) index + 1 else null }
    }

    private fun volumeChannel(featureUnit: UsbAudioFeatureUnit): Int? = volumeChannels(featureUnit).firstOrNull()

    /**
     * 类请求的写。
     * UAC2 读写同码，UAC1 读写异码，由调用方传。
     */
    private fun controlOut(
        request: Int,
        value: Int,
        index: Int,
        data: ByteArray,
        requestType: Int = UsbAudioRequests.OUT_INTERFACE,
    ): Boolean {
        val transferred = runCatching {
            connection.controlTransfer(requestType, request, value, index, data, data.size, CONTROL_TIMEOUT_MS)
        }.onFailure {
            CrashLogManager.logException(LOG_TAG, "控制请求 $request 抛出异常", it)
        }.getOrDefault(-1)
        return transferred == data.size
    }

    private fun controlIn(
        request: Int,
        value: Int,
        index: Int,
        data: ByteArray,
        length: Int,
        requestType: Int = UsbAudioRequests.IN_INTERFACE,
    ): Boolean = transferIn(request, value, index, data, requestType) == length

    /**
     * 类请求的读，返回实际字节数，-1 为失败。
     * 与 controlIn 分开，便于诊断。
     */
    private fun transferIn(
        request: Int,
        value: Int,
        index: Int,
        data: ByteArray,
        requestType: Int = UsbAudioRequests.IN_INTERFACE,
    ): Int = runCatching {
        connection.controlTransfer(requestType, request, value, index, data, data.size, CONTROL_TIMEOUT_MS)
    }.onFailure {
        CrashLogManager.logException(LOG_TAG, "控制请求 $request 抛出异常", it)
    }.getOrDefault(-1)

    private companion object {
        /** 主控的声道号 */
        const val MASTER_CHANNEL = 0

        /** 时钟重锁 20ms，不等则回读到旧速率 */
        const val CLOCK_RELOCK_WAIT_MS = 20L

        /** 子区间个数上限，防异常应答申请超大缓冲 */
        const val MAX_RANGE_ENTRIES = 8

        /** RANGE 请求上限：2 字节子区间数 + 每子区间 12 字节（下限、上限、步长各 32 位） */
        const val MAX_RANGE_REPLY_BYTES = 2 + 12 * MAX_RANGE_ENTRIES

        const val MAX_VOLUME_SUB_RANGES = 4
    }
}
