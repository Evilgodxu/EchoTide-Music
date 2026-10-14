package com.yichao.evilgodxu.data.music.playback.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.Closeable

// 诊断日志的类名前缀：接管与归还的每次决策都记在此名下
private const val LOG_TAG = "UsbExclusiveDac"

/**
 * 对一台 USB 音频解码器的独占接管。
 *
 * 独占的来历在 [UsbDeviceConnection.claimInterface] 的 force 参数上：[UsbManager.openDevice] 只给一个设备
 * 句柄，不改变接口归属，故单靠"打开"拿不到任何排他性；真正把接口收归本应用的是 claim，而 force 为真时
 * 内核的音频驱动会被解绑。**解绑的那一刻该解码器即从系统音频里消失**——别的应用与通话都用不到它，
 * 这正是独占的代价，也是它唯一成立的方式：接口同一时刻只能有一个持有者，本应用持有意味着内核驱动不再持有。
 *
 * 接管范围必须成对：[UsbAudioInterfaces.playbackInterfaces] 给出的控制接口与流接口一并接管。
 * 只接一半会让内核驱动仍参与另一半，独占不成立。
 *
 * 释放走 [close]：逐个归还接口并关闭连接，内核驱动随后重新绑定，设备恢复为系统可用。归还通常在下次
 * 枚举时才完成，故恢复不是瞬时的。
 */
internal class UsbExclusiveDac private constructor(
    val device: UsbDevice,
    val descriptors: UsbAudioDevice,
    private val connection: UsbDeviceConnection,
    private val claimed: List<UsbInterface>,
) : Closeable {

    /** 供诊断与界面展示的设备名，取不到产品名时退回设备节点名 */
    val deviceName: String =
        device.productName?.takeIf { it.isNotBlank() } ?: device.deviceName

    /** 设备声明的可播放格式；写出侧只从中挑选，不做事前建轨试探 */
    val formats: List<UsbAudioOutputFormat> get() = descriptors.outputs

    /** 类特定控制：采样率与音量。全部走公开的控制传输，不依赖原生层。 */
    val control = UsbAudioControl(connection, descriptors)

    /** 已读过的时钟档位：同一时钟源会被多个格式共用，逐格式重问纯属多余 */
    private val clockRates = mutableMapOf<Int, List<Int>>()

    /**
     * 让设备按 [format] 与 [sampleRate] 就绪，成功即可以该格式建流。
     *
     * 两种版本的顺序**相反**，不可互换：
     * - UAC2 的采样率在时钟实体上，必须在切换备用设置**之前**设——切换会把流接口带入工作带宽，
     *   此时时钟若仍在旧速率，部分设备会拒绝切换或直接按旧速率工作；
     * - UAC1 的采样率挂在流端点上，而端点只存在于非零备用设置上，故必须在切换**之后**设。
     *
     * 设完一律回读核对。设备完全可能静默把请求改成它支持的档位，此时如实判为失败：直出讲究数据原样，
     * "以设备实际速率工作却当作按源速率送出"会表现为没报错但时间轴不对，比直接失败更难查。
     */
    fun prepareStream(format: UsbAudioOutputFormat, sampleRate: Int): Boolean {
        val uacVersion = descriptors.uacVersion
        var deviceRate = 0
        var clockSource = 0

        if (uacVersion == 2) {
            clockSource = descriptors.resolveClockSource(format.clockId) { control.selectedClockPin(it) }
            if (clockSource == 0) {
                CrashLogManager.logInfo(LOG_TAG, "格式 ${label(format)} 回溯不到时钟源，无法确定采样率")
                return false
            }
            // UAC2 的档位不在描述符里：先问时钟，再据实核对源速率是否被支持
            val supported = clockRates.getOrPut(clockSource) { control.readClockRates(clockSource) }
            if (supported.isNotEmpty() && !supported.contains(sampleRate)) {
                CrashLogManager.logInfo(
                    LOG_TAG,
                    "时钟 $clockSource 不支持 ${sampleRate}Hz，可用档位 $supported",
                )
                return false
            }
            deviceRate = control.setClockRate(clockSource, sampleRate)
        } else if (!format.supportsRate(sampleRate)) {
            CrashLogManager.logInfo(
                LOG_TAG,
                "格式 ${label(format)} 未声明 ${sampleRate}Hz，声明档位 ${describeRates(format)}",
            )
            return false
        }

        if (!selectAlternateSetting(format.interfaceNumber, format.altSetting)) return false

        if (uacVersion == 1) {
            // 只有"多档位或可设"的端点上这一请求才有意义；单档固定的设备不必也不该去设
            val rateSettable = format.rateControlOnEndpoint || format.continuousRates ||
                format.rates.size > 1
            if (rateSettable) {
                deviceRate = control.setEndpointRate(format.endpointAddress, sampleRate)
            }
        }

        if (deviceRate != 0 && deviceRate != sampleRate) {
            CrashLogManager.logInfo(
                LOG_TAG,
                "已请求 ${sampleRate}Hz，设备实际为 ${deviceRate}Hz，本次输出不成立",
            )
            // 退回零带宽：留在已切换的备用设置上会让设备继续占用带宽
            selectAlternateSetting(format.interfaceNumber, 0)
            return false
        }
        CrashLogManager.logInfo(
            LOG_TAG,
            "已就绪：${label(format)} ${sampleRate}Hz" +
                if (clockSource != 0) "（时钟 $clockSource）" else "",
        )
        return true
    }

    /** 选定备用设置。等时端点只在非零备用设置上存在，未选定前设备侧并没有那条端点。 */
    private fun selectAlternateSetting(interfaceNumber: Int, altSetting: Int): Boolean {
        val target = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == interfaceNumber && it.alternateSetting == altSetting }
        if (target == null) {
            CrashLogManager.logInfo(LOG_TAG, "设备未暴露接口 $interfaceNumber 的备用设置 $altSetting")
            return false
        }
        if (!connection.setInterface(target)) {
            CrashLogManager.logInfo(LOG_TAG, "切换接口 $interfaceNumber 到备用设置 $altSetting 失败")
            return false
        }
        return true
    }

    private fun label(format: UsbAudioOutputFormat): String =
        "接口${format.interfaceNumber}.${format.altSetting}"

    private fun describeRates(format: UsbAudioOutputFormat): String =
        if (format.continuousRates) "${format.minRate}..${format.maxRate}Hz" else "${format.rates}"

    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        // 反序归还，与接管的嵌套顺序相反
        for (intf in claimed.asReversed()) {
            runCatching { connection.releaseInterface(intf) }
                .onFailure { CrashLogManager.logException(LOG_TAG, "归还接口 ${intf.id} 失败", it) }
        }
        runCatching { connection.close() }
            .onFailure { CrashLogManager.logException(LOG_TAG, "关闭设备连接失败", it) }
        // 归还后内核驱动须重新绑定，该设备才会重新出现在系统音频里——恢复不是瞬时的
        CrashLogManager.logInfo(LOG_TAG, "已归还 $deviceName")
    }

    companion object {

        /** 当前接入的 USB 音频解码器；可能不止一台 */
        fun attachedDacs(manager: UsbManager): List<UsbDevice> =
            manager.deviceList.values.filter { UsbAudioInterfaces.isUsbAudioOutput(it) }

        fun isAudioDac(device: UsbDevice): Boolean = UsbAudioInterfaces.isUsbAudioOutput(device)

        /**
         * 接管 [device]。
         *
         * 调用方须已取得该设备的 USB 访问授权（[UsbManager.requestPermission] 的结果）：本节制不申请权限，
         * 因为权限弹窗属于交互，应由界面层决定何时打断用户。
         *
         * 失败一律返回 null 并留痕，不向上抛：接管失败意味本次直出用不上独占，退回平台路径即可，
         * 不该把播放带走。
         */
        fun open(manager: UsbManager, device: UsbDevice): UsbExclusiveDac? {
            if (!manager.hasPermission(device)) {
                CrashLogManager.logInfo(
                    LOG_TAG,
                    "未取得 ${device.deviceName} 的 USB 访问授权，无法接管",
                )
                return null
            }
            val connection = manager.openDevice(device)
            if (connection == null) {
                CrashLogManager.logInfo(LOG_TAG, "打开 ${device.deviceName} 失败，系统未返回连接")
                return null
            }
            val claimed = mutableListOf<UsbInterface>()
            try {
                for (intf in UsbAudioInterfaces.playbackInterfaces(device)) {
                    // force 为真才会解绑内核音频驱动；为假时该接口已被驱动持有，声明必然失败
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
                CrashLogManager.logInfo(
                    LOG_TAG,
                    "已接管 ${device.deviceName}：接口 ${claimed.joinToString { it.id.toString() }}，" +
                        "该设备已从系统音频中移除",
                )
                CrashLogManager.logInfo(LOG_TAG, "描述符摘要：${summarize(parsed)}")
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

        /**
         * 描述符摘要，供真机上判断"设备到底声明了什么"。
         *
         * 直出能否成立取决于设备自报的档位，而这套信息在系统侧读不到、只能从原始描述符解析，
         * 故接管成功后整条摘要入日志，避免逐台设备靠猜。
         */
        private fun summarize(parsed: UsbAudioDevice): String {
            val formats = parsed.outputs.joinToString(separator = " | ") { format ->
                val rates = when {
                    format.continuousRates -> "${format.minRate}..${format.maxRate}Hz(连续)"
                    format.rates.isEmpty() -> "速率由时钟应答"
                    else -> format.rates.joinToString("/") + "Hz"
                }
                buildString {
                    append("接口${format.interfaceNumber}.${format.altSetting} ")
                    append("${format.channels}ch/${format.subslotBytes}字节槽/${format.bitResolution}位 ")
                    append(rates)
                    if (format.syncType == UsbAudioSyncType.ASYNCHRONOUS) {
                        append(" 异步(反馈端点0x${format.feedbackEndpoint.toString(16)})")
                    }
                    if (format.clockId != 0) append(" 时钟${format.clockId}")
                }
            }
            return "UAC${parsed.uacVersion} 控制接口${parsed.controlInterfaceNumber} " +
                "格式[$formats] 时钟[${parsed.clocks.joinToString { "${it.id}/${it.kind}" }}]"
        }
    }
}
