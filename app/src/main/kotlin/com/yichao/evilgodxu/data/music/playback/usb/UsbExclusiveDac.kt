package com.yichao.evilgodxu.data.music.playback.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.yichao.evilgodxu.data.music.playback.UsbExclusiveDeviceInfo
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock

// 异常日志的类名前缀：接管与归还只有出错时才记（正常路径不写日志）
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

    /**
     * 设备侧操作的互斥锁。
     *
     * 控制传输有两个发起方——播放线程（就绪流程）与主线程（面板查设备能力）——而 [close] 可能在任一时刻
     * 到来。`UsbDeviceConnection` 关闭后其内部对象即被释放，此时再用它发控制请求就是 use-after-free
     * （症状是进程无声消失、不留任何异常）。故所有设备侧调用与关闭本身都串行在这把锁上：关闭会等到
     * 在飞的那次控制请求结束再动手。
     */
    private val deviceLock = ReentrantLock()

    /** 已归还：置位后任何设备侧调用都不再触碰连接，一律按失败返回 */
    private var closed = false

    /** 类特定控制：采样率与音量。全部走公开的控制传输，不依赖原生层。只在本类的锁内取用 */
    private val control = UsbAudioControl(connection, descriptors)

    /**
     * 在锁内执行一次设备侧操作；已归还时直接返回 [fallback]，不再触碰连接。
     *
     * 归还之后仍被调用不是异常情形，而是必须处理的情形：写出侧从收到归还通知到自身复位之间有一段窗口，
     * 期间它会照常调用这里（例如 [idle] 要退回零带宽），而连接此刻已经关了。
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
     * 设备节点的文件描述符；已归还时为 -1。
     *
     * 原生等时层正是拿它经 usbfs 提交 URB 的：`UsbManager.openDevice` 给的就是这一个 fd，
     * 而公开的 Java USB 主机 API 没有等时传输的入口，所以它必须交给原生层。
     */
    val fileDescriptor: Int get() = withDevice(-1) { connection.fileDescriptor }

    /** 已就绪的流接口号：停止输出时据此把接口退回零带宽；0 表示当前未就绪 */
    private var activeStreamInterface = 0

    /**
     * 已读过的时钟档位：同一时钟源会被多个格式共用，逐格式重问纯属多余。
     *
     * 并发的取用方有两个：就绪流程在播放线程上补齐档位，而界面查设备信息时会在主线程上问一次，
     * 故用并发映射而不是普通可变映射。
     */
    private val clockRates = java.util.concurrent.ConcurrentHashMap<Int, List<Int>>()

    /**
     * 设备真实声明的播放能力。
     *
     * 独占期间设备已从系统音频里消失（内核驱动被解绑），平台侧一次也查不到它，故这些项只能由接管方给出。
     * 而它也确实是唯一准的口径：平台 API 只报得出动态混音端口声明的条目（实测只给最高一档），
     * 描述符里声明的 16/24/32 三档在那里看不到。
     *
     * 会问一次设备时钟以补齐采样率（UAC2 的档位不在描述符里），问过即缓存。
     *
     * 已归还时返回 null：这时问不了时钟，而只报描述符里声明的那几档会与在用时给出的答案不一致——
     * 同一个设备在两处显示成不同能力，比读不到更糟。
     */
    fun declaredCapabilities(): UsbExclusiveDeviceInfo? =
        withDevice(null) { usbAudioDeviceInfo(deviceName, descriptors, control, clockRates) }
            // 独占期间它是唯一读得到设备声明的那条路，顺手留一份给只读方（诊断报告）引用
            ?.also { UsbAudioCapabilities.remember(it) }

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
    fun prepareStream(format: UsbAudioOutputFormat, sampleRate: Int): Boolean =
        withDevice(false) { prepareStreamLocked(format, sampleRate) }

    private fun prepareStreamLocked(format: UsbAudioOutputFormat, sampleRate: Int): Boolean {
        val uacVersion = descriptors.uacVersion
        var deviceRate = 0
        var clockSource = 0

        if (uacVersion == 2) {
            clockSource = descriptors.resolveClockSource(format.clockId) { control.selectedClockPin(it) }
            if (clockSource == 0) return false
            // UAC2 的档位不在描述符里：先问时钟，再据实核对源速率是否被支持
            val supported = clockRates.getOrPut(clockSource) { control.readClockRates(clockSource) }
            if (supported.isNotEmpty() && !supported.contains(sampleRate)) return false
            deviceRate = control.setClockRate(clockSource, sampleRate)
        } else if (!format.supportsRate(sampleRate)) {
            return false
        }

        if (!selectAlternateSetting(format.interfaceNumber, format.altSetting)) return false
        activeStreamInterface = format.interfaceNumber

        if (uacVersion == 1) {
            // 只有"多档位或可设"的端点上这一请求才有意义；单档固定的设备不必也不该去设
            val rateSettable = format.rateControlOnEndpoint || format.continuousRates ||
                format.rates.size > 1
            if (rateSettable) {
                deviceRate = control.setEndpointRate(format.endpointAddress, sampleRate)
            }
        }

        if (deviceRate != 0 && deviceRate != sampleRate) {
            // 退回零带宽：留在已切换的备用设置上会让设备继续占用带宽
            selectAlternateSetting(format.interfaceNumber, 0)
            activeStreamInterface = 0
            return false
        }
        // 回读为 0 只说明"设备没告诉我们要不要设"，不足以否掉直出。是否值得留痕取决于端点的同步方式：
        // 自适应与同步端点跟着包速率走，时钟不必设、设了没应答也无碍；只有异步端点的时钟说了算，
        // 那时"未设置"意味着以错误速率送出，只能靠听感发现——故仅在异步端点记这一条
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
     * 把流接口退回零带宽，但保留接管。
     *
     * 停止输出时调用：留在非零备用设置上会让设备白白占着带宽与时钟（部分解码器随之显示"有信号"），
     * 而接管本身要继续持有——换曲时若重新 claim 一次，内核驱动的解绑与重挂会白走一遍。
     */
    fun idle() = withDevice(Unit) { idleLocked() }

    private fun idleLocked() {
        val streamInterface = activeStreamInterface
        if (streamInterface == 0) return
        activeStreamInterface = 0
        selectAlternateSetting(streamInterface, 0)
    }

    /** 选定备用设置。等时端点只在非零备用设置上存在，未选定前设备侧并没有那条端点。 */
    private fun selectAlternateSetting(interfaceNumber: Int, altSetting: Int): Boolean {
        val target = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == interfaceNumber && it.alternateSetting == altSetting }
            ?: return false
        return connection.setInterface(target)
    }

    /**
     * 归还设备：逐个归还接口并关闭连接，内核驱动随后重新绑定。
     *
     * 整体在 [deviceLock] 内完成——锁外可能正有一次控制请求在发（面板在主线程查设备能力），
     * 此刻释放连接即 use-after-free。锁会把归还推迟到那次请求结束，随后置位 [closed]，
     * 此后的设备侧调用一律按失败返回，不再触碰已释放的连接。
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
         * 接管 [device]。
         *
         * 调用方须已取得该设备的 USB 访问授权（[UsbManager.requestPermission] 的结果）：本节制不申请权限，
         * 因为权限弹窗属于交互，应由界面层决定何时打断用户。
         *
         * 失败一律返回 null，不向上抛：接管失败意味本次播放用不上独占，退回平台路径即可，
         * 不该把播放带走。失败原因随异常入日志。
         */
        fun open(manager: UsbManager, device: UsbDevice): UsbExclusiveDac? {
            if (!manager.hasPermission(device)) return null
            val connection = manager.openDevice(device) ?: return null
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
