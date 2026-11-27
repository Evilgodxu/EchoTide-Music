package com.yichao.evilgodxu.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.yichao.evilgodxu.log.CrashLogManager

/** 同步失败留痕用 */
private const val LOG_TAG = "UsbAttachEntry"

/** 让 USB 接入入口跟随独占开关启停；直出不需授权不计入 */
internal fun Context.setUsbAttachEntryEnabled(enabled: Boolean) {
    val component = ComponentName(this, UsbAttachActivity::class.java)
    val state = if (enabled) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
    // 失败不上抛，仅留痕，避免中断长期订阅
    runCatching {
        // 已是目标状态则跳过，避免跨进程写
        if (packageManager.getComponentEnabledSetting(component) != state) {
            // 只改组件状态，不杀进程
            packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }.onFailure { CrashLogManager.logException(LOG_TAG, "同步 USB 接入入口的启用状态失败", it) }
}
