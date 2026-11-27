package com.yichao.evilgodxu.data.music.playback.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.yichao.evilgodxu.data.music.playback.UsbExclusiveDeviceInfo
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock

// 日志类名：接管与归还仅出错时记录
private const val LOG_TAG = "UsbExclusiveDac"

/**
 * 对一台 USB 解码器的独占接管。
 *
 * claimInterface 解绑驱动，设备消失。
 * 控制与流接口须一并接管，只接一半不成立。
 * 释放走 close，逐个归还接口，驱动随后重绑。
 */
internal class UsbExclusiveDac private constructor(
    val device: UsbDevice,
    val descriptors: UsbAudioDevice,
    private val connection: UsbDeviceConnection,
    private val claimed: List<UsbInterface>,
) : Closeable {

    /** 设备名，取不到产品名时退回节点名 */
    val deviceName: String =
        device.productName?.takeIf { it.isNotBlank() } ?: device.deviceName

    /** 设备声明的可播放格式，供写出侧挑选 */
    val formats: List<UsbAudioOutputFormat> get() = descriptors.outputs

    /**
     * 设备侧操作的互斥锁。
     * 关闭后再发请求即 use-after-free。
     */
    private val deviceLock = ReentrantLock()

    /** 已归还，此后设备调用一律按失败返回 */
    private var closed = false

    /** 采样率与音量控制，只在本类锁内取用 */
    private val control = UsbAudioControl(connection, descriptors)

    /**
     * 锁内执行一次设备操作，已归还时返回 fallback。
     * 归还窗口期内仍会被调用，须容错。
     */
    private inline fun <T> withDevice(fallback: T, block: () -> T): T {
        deviceLock.lock()
        try {
            if (closed) return fallback
            return block()
        } finally {
            deviceLock.unlock()
        }
    }

    /**
     * 设备节点 fd，已归还时为 -1。
     * 原生等时层靠它提交 URB，Java USB 无此入口。
     */
    val fileDescriptor: Int get() = withDevice(-1) { connection.fileDescriptor }

    /** 已就绪流接口号，0 表示未就绪，停止输出时据此退零带宽 */
    private var activeStreamInterface = 0

    /**
     * 已读过的时钟档位，同一时钟源共享。
     * 播放线程与主线程都会读，故用并发映射。
     */
    private val clockRates = java.util.concurrent.ConcurrentHashMap<Int, List<Int>>()

    /**
     * 设备声明的播放能力。
     * 独占期间平台查不到，只能问一次时钟并缓存。
     * 已归还时返回 null。
     */
    fun declaredCapabilities(): UsbExclusiveDeviceInfo? =
        withDevice(null) { usbAudioDeviceInfo(deviceName, descriptors, control, clockRates) }
            // 独占是唯一读得到声明的路，留一份给诊断报告引用
            ?.also { UsbAudioCapabilities.remember(it) }

    /**
     * 让设备按格式与采样率就绪，成功即可建流。
     *
     * 两版顺序相反：UAC2 先设时钟，UAC1 后设端点。
     * 设完回读核对，不符即失败，避免时间轴错却无报错。
     */
    fun prepareStream(format: UsbAudioOutputFormat, sampleRate: Int): Boolean =
        withDevice(false) { prepareStreamLocked(format, sampleRate) }

    private fun prepareStreamLocked(format: UsbAudioOutputFormat, sampleRate: Int): Boolean {
        val uacVersion = descriptors.uacVersion
        var deviceRate = 0
        var clockSource = 0

        if (uacVersion == 2) {
            clockSource = descriptors.resolveClockSource(format.clockId) { control.selectedClockPin(it) }
            if (clockSource == 0) return false
            // UAC2 档位要问时钟，再据实核对
            val supported = clockRates.getOrPut(clockSource) { control.readClockRates(clockSource) }
            if (supported.isNotEmpty() && !supported.contains(sampleRate)) return false
            deviceRate = control.setClockRate(clockSource, sampleRate)
        } else if (!format.supportsRate(sampleRate)) {
            return false
        }

        if (!selectAlternateSetting(format.interfaceNumber, format.altSetting)) return false
        activeStreamInterface = format.interfaceNumber

        if (uacVersion == 1) {
            // 仅多档或可设端点需设速率，单档不必
            val rateSettable = format.rateControlOnEndpoint || format.continuousRates ||
                format.rates.size > 1
            if (rateSettable) {
                deviceRate = control.setEndpointRate(format.endpointAddress, sampleRate)
            }
        }

        if (deviceRate != 0 && deviceRate != sampleRate) {
            // 退回零带宽，免设备继续占用带宽
            selectAlternateSetting(format.interfaceNumber, 0)
            activeStreamInterface = 0
            return false
        }
        // 回读 0 只是没应答，不足以否掉直出
        if (deviceRate == 0 && uacVersion == 2 && format.syncType == UsbAudioSyncType.ASYNCHRONOUS) {
            CrashLogManager.logException(
                LOG_TAG,
                "时钟源 #$clockSource 采样率读写均无应答（请求 $sampleRate），本次直出未经速率核验",
                null,
            )
        }
        return true
    }

    /**
     * 把流接口退回零带宽，仍保持接管。
     * 零带宽免得白占，换曲不必重 claim。
     */
    fun idle() = withDevice(Unit) { idleLocked() }

    private fun idleLocked() {
        val streamInterface = activeStreamInterface
        if (streamInterface == 0) return
        activeStreamInterface = 0
        selectAlternateSetting(streamInterface, 0)
    }

    /** 选定备用设置：等时端点只在非零档存在 */
    private fun selectAlternateSetting(interfaceNumber: Int, altSetting: Int): Boolean {
        val target = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == interfaceNumber && it.alternateSetting == altSetting }
            ?: return false
        return connection.setInterface(target)
    }

    /**
     * 归还设备，逐个归还接口并关闭。
     * 全程持锁等控制请求结束，之后按失败返回。
     */
    override fun close() {
        deviceLock.lock()
        try {
            if (closed) return
            closed = true
            // 反序归还，与接管的嵌套顺序相反
            for (intf in claimed.asReversed()) {
                runCatching { connection.releaseInterface(intf) }
                    .onFailure { CrashLogManager.logException(LOG_TAG, "归还接口 ${intf.id} 失败", it) }
            }
            runCatching { connection.close() }
                .onFailure { CrashLogManager.logException(LOG_TAG, "关闭设备连接失败", it) }
        } finally {
            deviceLock.unlock()
        }
    }

    companion object {

        /** 当前接入的 USB 音频解码器；可能不止一台 */
        fun attachedDacs(manager: UsbManager): List<UsbDevice> =
            manager.deviceList.values.filter { UsbAudioInterfaces.isUsbAudioOutput(it) }

        fun isAudioDac(device: UsbDevice): Boolean = UsbAudioInterfaces.isUsbAudioOutput(device)

        /**
         * 接管 device，调用方须已授权。
         * 失败返回 null，本次退回平台。
         */
        fun open(manager: UsbManager, device: UsbDevice): UsbExclusiveDac? {
            if (!manager.hasPermission(device)) return null
            val connection = manager.openDevice(device) ?: return null
            val claimed = mutableListOf<UsbInterface>()
            try {
                for (intf in UsbAudioInterfaces.playbackInterfaces(device)) {
                    // force 才解绑内核驱动，否则声明必然失败
                    if (!connection.claimInterface(intf, true)) {
                        throw IllegalStateException("接口 ${intf.id} 声明失败（内核驱动可能仍在持有）")
                    }
                    claimed += intf
                }
                if (claimed.isEmpty()) {
                    throw IllegalStateException("设备未暴露 USB 音频接口")
                }
                val raw = connection.rawDescriptors
                    ?: throw IllegalStateException("读取原始描述符失败")
                val parsed = UsbAudioDescriptorParser.parse(raw)
                if (parsed.uacVersion == 0 || parsed.outputs.isEmpty()) {
                    throw IllegalStateException(
                        "描述符未给出可播放的音频格式（UAC 版本 ${parsed.uacVersion}）",
                    )
                }
                return UsbExclusiveDac(device, parsed, connection, claimed)
            } catch (t: Throwable) {
                CrashLogManager.logException(LOG_TAG, "接管 ${device.deviceName} 失败，已回滚", t)
                for (intf in claimed.asReversed()) {
                    runCatching { connection.releaseInterface(intf) }
                }
                runCatching { connection.close() }
                return null
            }
        }
    }
}
