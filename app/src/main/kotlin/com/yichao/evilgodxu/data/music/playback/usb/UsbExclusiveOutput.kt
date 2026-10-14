package com.yichao.evilgodxu.data.music.playback.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.yichao.evilgodxu.data.music.playback.UsbExclusiveDeviceInfo
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.Closeable

// 类名前缀：接管与归还的每次决策都记在此名下
private const val LOG_TAG = "UsbExclusiveOutput"

/** 授权结果的动作名：由本应用自建 `PendingIntent` 携带，系统在弹窗作答后回送 */
private const val ACTION_USB_PERMISSION = "com.yichao.evilgodxu.USB_PERMISSION"

/** 授权请求的 `PendingIntent` 请求码：同一时刻只会有一次未决请求，固定值即可 */
private const val PERMISSION_REQUEST_CODE = 1

/** 独占路径的成色：界面据此区分"开关开着"与"真的接管到了" */
internal sealed interface UsbExclusiveState {

    /** 开关关闭 */
    object Disabled : UsbExclusiveState

    /** 开关开启但没有可接管的解码器（未接入、或接管失败） */
    object AwaitingDevice : UsbExclusiveState

    /** 已弹出系统授权框，等待作答 */
    object AwaitingPermission : UsbExclusiveState

    /** 已接管：接口归本应用，该设备已从系统音频中消失 */
    data class Active(val deviceName: String) : UsbExclusiveState
}

/**
 * USB 独占的接入与归还：申请授权、接管设备、把会话交给写出侧，并在开关关闭或设备拔出时归还。
 *
 * 与 [UsbDirectOutput] 是并列且互斥的两条路：那条走平台的专用输出流（设备仍归内核驱动，只是由本应用
 * 指定输出属性），这条把接口直接收归本应用（内核驱动被解绑，平台链路随之不存在）。**两者的成立条件
 * 互相否定**，故从开关到路由都按二选一处理。
 *
 * 授权的取得是交互，本类只负责发起与接收：系统的授权弹窗由 `UsbManager.requestPermission` 拉起，
 * 作答经本应用自建广播的 `PendingIntent` 回送。接管本身不申请权限（见 [UsbExclusiveDac.open]），
 * 故这里必须先把"有授权"这一步走完再谈接管。
 */
internal class UsbExclusiveOutput(
    private val context: Context,
    /** 成色回调：接管与归还都经此处对外发布 */
    private val onStateChanged: (UsbExclusiveState) -> Unit,
) : Closeable {

    private val usbManager: UsbManager? = context.getSystemService(UsbManager::class.java)

    private var enabled = false
    private var registered = false

    /**
     * 当前持有的独占会话，null 表示未接管。
     *
     * 写出侧在播放线程上读取它，而接管与归还在主线程（广播接收）与播放线程（链路失效）两处发生，
     * 故按易变发布，避免写出侧读到尚未发布的新会话。
     */
    @Volatile
    private var claim: UsbExclusiveDac? = null

    /** 已请求授权而尚未作答的设备，仅用于去重 */
    private var awaiting: UsbDevice? = null

    private var state: UsbExclusiveState = UsbExclusiveState.Disabled

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> onPermissionResult(intent)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onDeviceAttached()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDeviceDetached(intent)
            }
        }
    }

    /** 供写出侧取用的会话；取出后即可立即用于配置，为空则本次独占不成立 */
    fun currentSession(): UsbExclusiveDac? = claim

    /**
     * 当前接管到的解码器信息；未接管时为 null。
     *
     * 独占期间它已从系统音频里消失（内核驱动被解绑），平台侧一次也查不到，故音频信息面板的设备那几行
     * 只能由此取——否则面板会落到"扬声器"上，与实际发声的设备完全对不上。
     */
    fun deviceInfo(): UsbExclusiveDeviceInfo? =
        claim?.let { active -> runCatching { active.declaredCapabilities() }.getOrNull() }

    /**
     * 开关。开启即尝试接管，关闭即归还。
     *
     * 关闭时归还这一步是硬要求而非清理：接口不还，内核驱动就一直处于解绑状态，该解码器在系统音频里
     * 始终不出现，通话与别的应用都用不到它。
     */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            releaseClaim()
            publish(UsbExclusiveState.Disabled)
            return
        }
        ensureRegistered()
        acquire()
    }

    @Synchronized
    override fun close() {
        releaseClaim()
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
                .onFailure { CrashLogManager.logException(LOG_TAG, "注销 USB 事件接收失败", it) }
            registered = false
        }
        enabled = false
        publish(UsbExclusiveState.Disabled)
    }

    /**
     * 订阅 USB 事件。
     *
     * 只用**非导出**注册：三条动作都来自系统，而投递判定对 system uid 一律放行（见 AOSP 的
     * `BroadcastSkipPolicy`，非导出只挡其他应用）。非导出因此白得一层防护——别的应用无法伪造
     * 接入/拔出事件，把本应用从设备上赶下来。
     */
    private fun ensureRegistered() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    /** 尝试接管当前接入的解码器；没有设备或没有授权都如实发布对应成色 */
    private fun acquire() {
        val manager = usbManager ?: run {
            publish(UsbExclusiveState.AwaitingDevice)
            return
        }
        val device = UsbExclusiveDac.attachedDacs(manager).firstOrNull()
        if (device == null) {
            publish(UsbExclusiveState.AwaitingDevice)
            return
        }
        if (!manager.hasPermission(device)) {
            requestPermission(manager, device)
            publish(UsbExclusiveState.AwaitingPermission)
            return
        }
        openClaim(manager, device)
    }

    private fun openClaim(manager: UsbManager, device: UsbDevice) {
        releaseClaim()
        val opened = UsbExclusiveDac.open(manager, device)
        if (opened == null) {
            // 接管失败不重试：同一原因反复接管只会反复解开又挂回内核驱动，比不接管更糟。
            // 等下一次开关或拔插再试；失败原因由 UsbExclusiveDac 在异常日志里给出
            publish(UsbExclusiveState.AwaitingDevice)
            return
        }
        claim = opened
        publish(UsbExclusiveState.Active(opened.deviceName))
    }

    private fun requestPermission(manager: UsbManager, device: UsbDevice) {
        awaiting = device
        // 必须显式指定包名：系统回送的意图本身不带包名，而 Android 14 起不允许可变的 PendingIntent
        // 包裹隐式意图（直接抛 IllegalArgumentException）
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(
            context,
            PERMISSION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_MUTABLE,
        )
        manager.requestPermission(device, pending)
    }

    @Synchronized
    private fun onPermissionResult(intent: Intent) {
        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        awaiting = null
        if (device == null) return
        if (!granted) {
            publish(UsbExclusiveState.AwaitingDevice)
            return
        }
        if (!enabled || claim != null) return
        val manager = usbManager ?: return
        openClaim(manager, device)
    }

    @Synchronized
    private fun onDeviceAttached() {
        if (!enabled || claim != null) return
        acquire()
    }

    @Synchronized
    private fun onDeviceDetached(intent: Intent) {
        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
        val current = claim ?: return
        // 拔出的可能是另一台设备，只有手里的那台走了才作废会话
        if (current.device.deviceName != device.deviceName) return
        releaseClaim()
        if (enabled) acquire() else publish(UsbExclusiveState.Disabled)
    }

    /**
     * 写出侧报告链路已失效（等时流终止或设备失联）。
     *
     * 归还设备并回到"等待设备"，**不立即重接管**：同一台设备在故障之后立刻再接管一次多半还是同样的
     * 结果，而每一次接管都要把内核驱动解开再挂回，比干脆不接管更糟。等重新插拔或用户重开开关再试。
     */
    @Synchronized
    fun onStreamFailed() {
        if (claim == null) return
        releaseClaim()
        publish(UsbExclusiveState.AwaitingDevice)
    }

    private fun releaseClaim() {
        claim?.close()
        claim = null
    }

    private fun publish(next: UsbExclusiveState) {
        if (state == next) return
        state = next
        onStateChanged(next)
    }
}
