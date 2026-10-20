package com.yichao.evilgodxu.data.music.playback.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.yichao.evilgodxu.data.music.playback.USB_OUTPUT_TYPES

/** 授权结果的动作名：与独占那条各用一个，两处的 `PendingIntent` 因此不会互相命中 */
private const val ACTION_USB_ACCESS = "com.yichao.evilgodxu.USB_ACCESS"

/** 请求码：同一时刻只会有一次未决请求，固定值即可 */
private const val ACCESS_REQUEST_CODE = 2

/**
 * 为「读设备描述符」要一次 USB 授权。
 *
 * 音频信息面板的设备能力按描述符给（见 [UsbAudioCapabilities]），而 `UsbManager.openDevice` 要求该设备
 * 已被授权。全应用原先只有独占入口会申请授权，故只用直出时描述符永远读不到、面板退回平台那两份报不全的
 * 值。授权属于交互，何时打断用户由界面决定，本类只负责发起与收尾——分工同 [UsbExclusiveDac.open]。
 *
 * 优先挑当前正在输出的那台解码器：多台同接时按名称对上，对不上且不唯一时不动手。替用户授权一台
 * 可能根本没在听的设备，只会多一次弹窗。
 */
internal object UsbAccessRequest {

    fun requestIfNeeded(context: Context) {
        val manager = context.getSystemService(UsbManager::class.java) ?: return
        val dacs = UsbExclusiveDac.attachedDacs(manager)
        if (dacs.isEmpty()) return
        val routed = routedUsbProductName(context)
        val device = dacs.firstOrNull { usbProductNameMatches(routed, it.productName) }
            ?: dacs.singleOrNull()
            ?: return
        if (manager.hasPermission(device)) return
        registerResultReceiver(context)
        manager.requestPermission(device, permissionIntent(context))
    }

    /**
     * 接收授权结果的空实现：授权本身由系统记录，下一次采集自然读到描述符，这里只负责注销自己。
     *
     * 不留常驻接收器：授权只在这一刻有未决请求，收完即走才不至于让一个监听器伴随后台活到进程结束。
     */
    private fun registerResultReceiver(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching { context.unregisterReceiver(this) }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_ACCESS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // 必须显式指定包名：系统回送的意图本身不带包名，而 Android 14 起可变的 PendingIntent 不接受隐式意图
    private fun permissionIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ACCESS_REQUEST_CODE,
        Intent(ACTION_USB_ACCESS).setPackage(context.packageName),
        PendingIntent.FLAG_MUTABLE,
    )

    // 当前正在输出的 USB 设备名：与直出同一判据（USB 类型且为 sink），名称经归一后才可跨来源比较
    private fun routedUsbProductName(context: Context): String? {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && it.type in USB_OUTPUT_TYPES }
            ?.productName
            ?.toString()
    }
}
