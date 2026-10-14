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
 * 存在的理由是**系统的选择弹窗只在有应用认领该设备时才会出现**：清单里声明
 * `android.hardware.usb.action.USB_DEVICE_ATTACHED` 的过滤器（见 `res/xml/usb_device_filter.xml`）后，
 * 插入匹配的设备时系统会问「用哪个应用打开」，选中的应用随即被启动并**自动取得该设备的 USB 访问授权**
 * （见 AOSP `UsbProfileGroupSettingsManager`）。这也正是同类竞品在应用未启动时仍能弹窗的原因——
 * 弹窗由系统发出，不依赖应用进程。少了这一条声明，插入设备什么都不会发生，用户也就没有"交给谁"的机会。
 *
 * 这条链路**只在 USB 独占开关已开启时成立**：弹窗换来的授权只有独占接管要用，开关关着时它没有用途，
 * 而"插上就弹、选了就接管"会被读成应用擅自改了开关。故组件由开关启停（见 [setUsbAttachEntryEnabled]），
 * 本入口自己再按落盘值核一次，并且**只把界面带到前台、不写开关**：要不要接管由用户在设置里决定，
 * 插入设备不是替用户改这个决定的时机。界面也要带出来——系统的选择框问的是「用哪个应用打开」，
 * 选完之后什么界面都不出现，用户只会当成没生效。
 *
 * 只认音频类设备：过滤器已经把范围限到音频类，此处再核一次接口，避免别的设备误触发接管。
 */
class UsbAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
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
