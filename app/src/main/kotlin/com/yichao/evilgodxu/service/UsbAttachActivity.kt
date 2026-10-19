package com.yichao.evilgodxu.service

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.yichao.evilgodxu.App
import com.yichao.evilgodxu.MainActivity
import com.yichao.evilgodxu.data.music.playback.usb.UsbExclusiveDac
import com.yichao.evilgodxu.data.settings.usbExclusiveOutputModeFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * USB 接入时的接管入口：无界面，接住系统的选择框，然后把应用带到前台。
 *
 * 弹窗只在有应用认领设备时出现，选中的应用会随之取得该设备的访问授权——这也正是应用未启动
 * 仍能弹窗的原因：弹窗由系统发出，不依赖应用进程。
 *
 * 这条链路只在 USB 独占开关已开启时成立：弹窗换来的授权只有独占接管要用，故组件由开关启停
 * （见 [setUsbAttachEntryEnabled]），本入口再按落盘值核一次。此处只把界面带到前台、不写开关——
 * 是否接管由用户在设置里决定。界面也必须带出来：选完之后什么都不出现，用户只会当成没生效。
 */
class UsbAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        // 过滤器已限到音频类，此处再核一次接口，避免别的设备误触发接管
        if (device == null || !UsbExclusiveDac.isAudioDac(device)) {
            finish()
            return
        }
        // 落盘值的读取是异步的，而本入口没有界面，故读盘期间保持透明窗口，读完再决定去留。
        // 走进程级作用域：本入口紧接着就结束，绑定 Activity 生命周期的作用域会被随即取消
        val app = application as App
        app.appScope.launch {
            // 开关刚被关掉、系统的这次启动已在投递途中时，只有这道核对拦得住——组件状态那时已来不及改
            val takeoverWanted = usbExclusiveOutputModeFlow().first()
            withContext(Dispatchers.Main) {
                // 先起界面再结束自己：本入口没有界面，若先结束，这次启动就可能随任务的清理一起被丢掉
                if (takeoverWanted) startActivity(Intent(this@UsbAttachActivity, MainActivity::class.java))
                finish()
            }
        }
    }
}
