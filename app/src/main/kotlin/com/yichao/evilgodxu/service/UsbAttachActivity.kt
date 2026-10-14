package com.yichao.evilgodxu.service

import android.app.Activity
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.yichao.evilgodxu.App
import com.yichao.evilgodxu.data.music.playback.usb.UsbExclusiveDac
import com.yichao.evilgodxu.data.settings.saveUsbExclusiveOutputMode
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.launch

// 日志前缀：接入入口的每次取舍都记在此名下
private const val LOG_TAG = "UsbAttachActivity"

/**
 * USB 接入时的接管入口：无界面，只把「这台解码器交给本应用」这件事落到设置上。
 *
 * 存在的理由是**系统的选择弹窗只在有应用认领该设备时才会出现**：清单里声明
 * `android.hardware.usb.action.USB_DEVICE_ATTACHED` 的过滤器（见 `res/xml/usb_device_filter.xml`）后，
 * 插入匹配的设备时系统会问「用哪个应用打开」，选中的应用随即被启动并**自动取得该设备的 USB 访问授权**
 * （见 AOSP `UsbProfileGroupSettingsManager`）。这也正是同类竞品在应用未启动时仍能弹窗的原因——
 * 弹窗由系统发出，不依赖应用进程。少了这一条声明，插入设备什么都不会发生，用户也就没有"交给谁"的机会。
 *
 * 选中本应用即表示同意接管，故这里把独占开关打开：开关本身是互斥的（见 `PlaybackPrefs`），
 * 打开独占会同时关掉平台直出那条路。播放服务若在运行会立即接管该设备，未运行则下次播放时生效。
 *
 * 只认音频类设备：过滤器已经把范围限到音频类，此处再核一次接口，避免别的设备误触发接管。
 */
class UsbAttachActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        if (device == null || !UsbExclusiveDac.isAudioDac(device)) {
            CrashLogManager.logInfo(LOG_TAG, "接入的设备不是 USB 音频解码器，不接管")
            finish()
            return
        }
        // 落盘走进程级作用域：本入口紧接着就结束，绑定 Activity 生命周期的作用域会被随即取消
        val app = application as App
        app.appScope.launch {
            saveUsbExclusiveOutputMode(true)
            CrashLogManager.logInfo(
                LOG_TAG,
                "已在系统弹窗中选中本应用，${device.productName ?: device.deviceName} 交由独占接管",
            )
        }
        finish()
    }
}
