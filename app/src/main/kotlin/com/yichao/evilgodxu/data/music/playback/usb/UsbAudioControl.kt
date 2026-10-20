package com.yichao.evilgodxu.data.music.playback.usb

import android.hardware.usb.UsbDeviceConnection
import com.yichao.evilgodxu.log.CrashLogManager

// 异常日志的类名前缀：控制请求只有抛出或未完成时才记（正常收发不写日志）
private const val LOG_TAG = "UsbAudioControl"

// 类特定请求都不该长等：超时即判失败，避免卡住播放线程
private const val CONTROL_TIMEOUT_MS = 1000

/**
 * UAC 类特定控制请求的编码。
 *
 * 抽成纯函数是为了能在 JVM 上验证。这些参数由规范固定，写错一位会打到错误的实体上——而错误的实体
 * 通常不回报错误，只是静默无视，表现为"设了采样率却没反应"，靠日志也看不出所以然。
 */
internal object UsbAudioRequests {

    /** bmRequestType：方向 | 类型（类） | 接收者（接口或端点） */
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

    /** UAC2 的类请求：读写共用请求码，方向由 bmRequestType 区分 */
    const val UAC2_CUR = 0x01
    const val UAC2_RANGE = 0x02

    /** 控制选择子 */
    const val CONTROL_MUTE = 0x01
    const val CONTROL_VOLUME = 0x02
    const val CONTROL_SAMPLING_FREQ = 0x01
    const val CONTROL_CLOCK_SELECTOR = 0x01

    /** 特征单元：wValue = 选择子 << 8 | 声道（0 表示主控），wIndex = 单元号 << 8 | 控制接口号 */
    fun featureUnitValue(control: Int, channel: Int): Int = (control shl 8) or (channel and 0xFF)

    fun featureUnitIndex(unitId: Int, controlInterface: Int): Int =
        (unitId shl 8) or (controlInterface and 0xFF)

    /** 时钟实体：wValue = 选择子 << 8，wIndex = 时钟号 << 8 | 控制接口号 */
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
 * UAC2 特征单元的 RANGE 应答解析：先给子区间数（16 位），每个子区间 6 字节——最小、最大、步长，
 * 各为 16 位有符号。取第一个子区间即可，与内核驱动一致。
 *
 * 注意与时钟实体的 RANGE 不同：那里每个子区间是 12 字节的 32 位值（见 [usbAudioRatesFromRange]）。
 * 两者都是 RANGE 请求，但实体不同、布局也不同。
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
 * 设备的类特定控制：采样率与音量。
 *
 * 全部走 [UsbDeviceConnection.controlTransfer] 这一公开入口，不需要任何原生代码——USB 主机 API 缺的只有
 * 等时传输，控制传输一直是齐的。所以这一层留在 Kotlin 里，只有把 PCM 送进等时端点那一步才落原生。
 */
internal class UsbAudioControl(
    private val connection: UsbDeviceConnection,
    private val device: UsbAudioDevice,
) {

    private val uacVersion = device.uacVersion
    private val controlInterface = device.controlInterfaceNumber

    /**
     * 逐个格式回溯出真正承载采样率的时钟源编号，0 表示回溯不到。
     *
     * 选择器当前选中哪一路只能问设备，故 [selectedPin] 由调用方给（它读 UAC2 的当前选择子）；
     * 同一时钟源会被多个格式共用，故按源缓存一次读数，避免逐格式重问。
     */
    fun resolveClockSources(selectedPin: (selectorId: Int) -> Int): List<Int> =
        device.outputs.map { format -> device.resolveClockSource(format.clockId, selectedPin) }

    /** 读某个时钟源的当前选中引脚；拿不到时返回 0，由回溯按第一路处理 */
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
     * 读时钟源支持的采样率档位。
     *
     * 三条路依次尝试，谁先给出非空档位就用谁：
     * 1. **一次问满**：请求长度取应答结构的上限，设备按实际长度短答复、宿主多要的部分被短包截断。
     *    这是内核驱动的问法（`snd_usb_get_sample_rate` 一次要 `2 + 12 * MAX`），对「只认与应答等长的
     *    请求」的设备最保险——KT02H20 就走这条才答得上来；
     * 2. **两步问**：先要子区间个数、再按个数取。少数设备对超长请求报错，只认第二种；
     * 3. **读当前值**：把"此刻正在用的那一档"当作唯一已知档位。
     *
     * [trace] 供诊断收取每次尝试的实际传输字节数：三次都失败时，是设备不应答还是宿主侧被拒，
     * 只能靠这三个数分辨——-1 表示传输失败，0 或短读表示应答形状不符。
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
     * 在时钟源上设置采样率，返回设备实际生效的采样率（读不到时为 0）。
     *
     * 必须在**切换备用设置之前**调用：UAC2 的采样率控制通道是时钟实体，而切换备用设置会把流接口带入
     * 工作带宽，此时若时钟仍在旧速率，部分设备会拒绝切换或直接以旧速率工作。
     *
     * 写后回读并留出重新锁定时钟的时间，是因为设备的时钟需要重新锁定；不等这一次就回读，读到的仍是旧值。
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
        // 时钟源声明为不可写、且此刻有确定的速率：不试写，直接如实回报
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
     * 在端点上设置采样率（UAC1），返回设备实际生效的采样率（读不到时为 0）。
     *
     * 必须在**切换备用设置之后**调用：UAC1 的采样率控制挂在流端点上，而端点在备用设置 0 上并不存在。
     * 这与 UAC2 的顺序相反，两者不可互换。
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

    /** 读特征单元的音量范围；设备未暴露音量控制时返回 null */
    fun volumeRange(featureUnit: UsbAudioFeatureUnit): UsbAudioVolumeRange? {
        val channel = volumeChannel(featureUnit) ?: return null
        if (uacVersion == 2) {
            // UAC2 用 RANGE 一次性取回范围，每个子区间是 16 位值
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

    /** 读特征单元当前的音量值（1/256 dB）；读不到时返回 null */
    fun currentVolume(featureUnit: UsbAudioFeatureUnit): Int? {
        val channel = volumeChannel(featureUnit) ?: return null
        val reply = ByteArray(2)
        val value = UsbAudioRequests.featureUnitValue(UsbAudioRequests.CONTROL_VOLUME, channel)
        val index = UsbAudioRequests.featureUnitIndex(featureUnit.unitId, controlInterface)
        val request = if (uacVersion == 2) UsbAudioRequests.UAC2_CUR else UsbAudioRequests.GET_CUR
        if (!controlIn(request, value, index, reply, reply.size)) return null
        return UsbAudioRequests.signed16(reply, 0)
    }

    /** 在特征单元上设置音量（1/256 dB）。音量设在硬件里，数据本身因此不参与缩放。 */
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

    /** 主控可写时只设主控；否则逐个设置可写的声道 */
    private fun volumeChannels(featureUnit: UsbAudioFeatureUnit): List<Int> = when {
        !featureUnit.hasVolume -> emptyList()
        featureUnit.masterVolume -> listOf(MASTER_CHANNEL)
        else -> featureUnit.channelVolume
            .mapIndexedNotNull { index, writable -> if (writable) index + 1 else null }
    }

    private fun volumeChannel(featureUnit: UsbAudioFeatureUnit): Int? = volumeChannels(featureUnit).firstOrNull()

    /**
     * 类请求的写：bmRequestType 取接口类，方向为出。
     *
     * UAC2 的读写在规范里共用请求码，但 UAC1 的 GET_CUR 与 SET_CUR 是不同的码——调用方给出的请求码按
     * 各自版本传，本方法只负责搬运。
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
     * 类请求的读，返回实际传输的字节数：-1 为传输失败，短读如实给出。
     *
     * 与 [controlIn] 分开：多数调用点只需「读到想要的长度就算成功」，但诊断要知道失败是传输被拒还是
     * 应答形状不符——这两种情况的下一步完全不同，混成一个布尔值就分不出来了。
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

        /** 时钟重新锁定前不等这一次，回读拿到的仍是旧速率 */
        const val CLOCK_RELOCK_WAIT_MS = 20L

        /** 子区间个数的合理上限：防止设备的异常应答让缓冲区按天量申请 */
        const val MAX_RANGE_ENTRIES = 8

        /** RANGE 应答的请求上限：2 字节子区间数 + 每个子区间 12 字节（下限、上限、步长各 32 位） */
        const val MAX_RANGE_REPLY_BYTES = 2 + 12 * MAX_RANGE_ENTRIES

        const val MAX_VOLUME_SUB_RANGES = 4
    }
}
