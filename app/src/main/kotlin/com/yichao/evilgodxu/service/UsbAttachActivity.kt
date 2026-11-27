package com.yichao.evilgodxu.service

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.yichao.evilgodxu.App
import com.yichao.evilgodxu.MainActivity
import com.yichao.evilgodxu.data.music.playback.usb.UsbExclusiveDac
import com.yichao.evilgodxu.data.music.playback.usb.notifyUsbAttachConfirmed
import com.yichao.evilgodxu.data.settings.usbExclusiveOutputModeFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** USB 接入接管：拉起到前台并补一声授权通知；只服务独占 */
class UsbAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        // 再核一次音频接口，避免误触发
        if (device == null || !UsbExclusiveDac.isAudioDac(device)) {
            finish()
            return
        }
        // 读盘异步，本入口即结束，故走进程级作用域
        val app = application as App
        app.container.appScope.launch {
            // 开关刚关时只有这道核对拦得住
            val entryWanted = usbExclusiveOutputModeFlow().first()
            withContext(Dispatchers.Main) {
                // 先起界面再结束，否则启动可能被丢弃
                if (entryWanted) {
                    startActivity(Intent(this@UsbAttachActivity, MainActivity::class.java))
                    // 选择框已带来授权，补一声通知接管
                    notifyUsbAttachConfirmed()
                }
                finish()
            }
        }
    }
}
