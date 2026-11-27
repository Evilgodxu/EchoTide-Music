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

// 日志类名：接管与归还的每次决策都记在此名下
private const val LOG_TAG = "UsbExclusiveOutput"

/** 授权结果动作名，由自建 PendingIntent 携带回送 */
private const val ACTION_USB_PERMISSION = "com.yichao.evilgodxu.USB_PERMISSION"

/** 接入入口完成选中即授权后的动作名 */
private const val ACTION_USB_ATTACH_CONFIRMED = "com.yichao.evilgodxu.USB_ATTACH_CONFIRMED"

/** 授权请求码，同一时刻只有一次未决请求 */
private const val PERMISSION_REQUEST_CODE = 1

/** 独占成色：区分开关开着与真的接管到 */
internal sealed interface UsbExclusiveState {

    /** 开关关闭 */
    object Disabled : UsbExclusiveState

    /** 开关开启但无可接管解码器 */
    object AwaitingDevice : UsbExclusiveState

    /** 已弹出系统授权框，等待作答 */
    object AwaitingPermission : UsbExclusiveState

    /** 已接管，该设备已从系统音频中消失 */
    data class Active(val deviceName: String) : UsbExclusiveState
}

/**
 * USB 独占的接入与归还。
 *
 * 与 UsbDirectOutput 互斥，接口收归本应用。
 * 接管前先经 beforeClaim 让写出侧放开平台。
 */
internal class UsbExclusiveOutput(
    private val context: Context,
    /** 成色回调：接管与归还都经此处对外发布 */
    private val onStateChanged: (UsbExclusiveState) -> Unit,
    /**
     * 接管前交棒：先放开平台输出。
     * 接管解绑驱动，平台异步收尾会抹掉备用设置。
     */
    private val beforeClaim: (proceed: () -> Unit) -> Unit = { it() },
) : Closeable {

    private val usbManager: UsbManager? = context.getSystemService(UsbManager::class.java)

    private var enabled = false
    private var registered = false

    /**
     * 当前会话，null 表示未接管。
     * 写出侧在播放线程读，故按易变发布。
     */
    @Volatile
    private var claim: UsbExclusiveDac? = null

    /** 已请求授权而尚未作答的设备，仅用于去重 */
    private var awaiting: UsbDevice? = null

    /**
     * 是否因失焦让出过设备。
     * 只收回自己让出的，链路失效不置此位。
     */
    private var suspendedForFocus = false

    private var state: UsbExclusiveState = UsbExclusiveState.Disabled

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> onPermissionResult(intent)
                ACTION_USB_ATTACH_CONFIRMED -> onAttachConfirmed()
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onDeviceAttached()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDeviceDetached(intent)
            }
        }
    }

    /** 供写出侧取用的会话，为空表示本次独占不成立 */
    fun currentSession(): UsbExclusiveDac? = claim

    /**
     * 当前接管的解码器，未接管时为 null。
     * 设备已从系统消失，面板只能由此取。
     */
    fun deviceInfo(): UsbExclusiveDeviceInfo? =
        claim?.let { active -> runCatching { active.declaredCapabilities() }.getOrNull() }

    /**
     * 开关，开启即尝试接管，关闭即归还。
     * 不归还则驱动一直解绑，设备不出现。
     */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        if (!enabled) {
            suspendedForFocus = false
            releaseClaim()
            publish(UsbExclusiveState.Disabled)
            return
        }
        ensureRegistered()
        acquire(prompt = true)
    }

    /**
     * 失焦时归还接口，让出设备。
     *
     * 继续持有会让来电与其他应用用不到它。
     * 开关与授权不动，重获焦点再收回。
     */
    @Synchronized
    fun releaseForFocusLoss() {
        if (claim == null) return
        releaseClaim()
        suspendedForFocus = true
        publish(UsbExclusiveState.AwaitingDevice)
    }

    /**
     * 重获焦点，收回此前让出的设备。
     * 链路失效与拔出不在此重试。
     */
    @Synchronized
    fun reclaimAfterFocusLoss() {
        if (!suspendedForFocus) return
        suspendedForFocus = false
        if (!enabled || claim != null) return
        ensureRegistered()
        acquire(prompt = false)
    }

    @Synchronized
    override fun close() {
        suspendedForFocus = false
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
     * 订阅 USB 事件，只用非导出注册。
     * 非导出可挡其他应用伪造接入或拔出。
     */
    private fun ensureRegistered() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(ACTION_USB_ATTACH_CONFIRMED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    /**
     * 尝试接管；无设备或无授权都如实发布。
     * 接入广播触发时不弹框，避免重复授权框。
     */
    private fun acquire(prompt: Boolean) {
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
            if (prompt) requestPermission(manager, device)
            publish(UsbExclusiveState.AwaitingPermission)
            return
        }
        openClaim(manager, device)
    }

    private fun openClaim(manager: UsbManager, device: UsbDevice) {
        releaseClaim()
        // 接管交给写出侧，只有它能同一趟改道
        beforeClaim { claimNow(manager, device) }
    }

    /**
     * 真正取设备，跑在写出侧线程，故复核状态。
     */
    @Synchronized
    private fun claimNow(manager: UsbManager, device: UsbDevice) {
        if (!enabled || claim != null) return
        val opened = UsbExclusiveDac.open(manager, device)
        if (opened == null) {
            // 失败不重试，反复解挂比不接管更糟
            publish(UsbExclusiveState.AwaitingDevice)
            return
        }
        claim = opened
        publish(UsbExclusiveState.Active(opened.deviceName))
    }

    private fun requestPermission(manager: UsbManager, device: UsbDevice) {
        awaiting = device
        // 须显式指定包名，可变 PendingIntent 不收隐式
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
        acquire(prompt = false)
    }

    /**
     * 接入入口完成「选中即授权」，补一声。
     * 万一未授予，这次就补发授权框。
     */
    @Synchronized
    private fun onAttachConfirmed() {
        if (!enabled || claim != null) return
        acquire(prompt = true)
    }

    @Synchronized
    private fun onDeviceDetached(intent: Intent) {
        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
        val current = claim ?: return
        // 只有手里那台拔出才作废会话
        if (current.device.deviceName != device.deviceName) return
        suspendedForFocus = false
        releaseClaim()
        if (enabled) acquire(prompt = true) else publish(UsbExclusiveState.Disabled)
    }

    /**
     * 写出侧报告链路失效，归还并回到等待设备。
     * 故障后立刻重接管多半同样失败。
     */
    @Synchronized
    fun onStreamFailed() {
        if (claim == null) return
        // 链路已坏，不因失焦收回
        suspendedForFocus = false
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

/**
 * 告知独占路径：入口刚完成「选中即授权」。
 * 权限已落定，补一次不会多弹窗。
 */
internal fun Context.notifyUsbAttachConfirmed() {
    sendBroadcast(Intent(ACTION_USB_ATTACH_CONFIRMED).setPackage(packageName))
}
