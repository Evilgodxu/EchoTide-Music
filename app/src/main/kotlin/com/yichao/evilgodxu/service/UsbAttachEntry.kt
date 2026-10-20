package com.yichao.evilgodxu.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.yichao.evilgodxu.log.CrashLogManager

/** 组件状态的每次同步都记在此名下：它与开关分属两处，不一致时只能靠日志回溯 */
private const val LOG_TAG = "UsbAttachEntry"

/**
 * 让 USB 接入入口（[UsbAttachActivity]）的启用状态跟随两个 USB 开关——独占与直出，任一开启即启用。
 *
 * 系统的「用哪个应用打开」只在有应用认领该设备时出现，而清单里的声明是静态的：组件一直可用，
 * 插上解码器就必然弹窗，而两个开关都关着时这个弹窗既无用途，又会被读成「插上就自动开了独占」。
 * 故按开关启停组件——停用后其意图过滤器不再参与匹配，系统根本看不到本应用。
 *
 * 直出也算在内：那份授权本来是给独占接管用的，但音频信息面板的设备能力只认描述符，而读描述符要授权
 * （见 `UsbAudioCapabilities`）。只开直出时若拿不到授权，面板就只能退回平台那两份报不全的值。
 *
 * 清单里已声明 `android:enabled="false"`：全新安装且从未打开过本应用时不参与匹配。
 * 调用方订阅的是落盘值而非界面回调：组件状态由系统持久保存，与开关分属两处，
 * 任何一处漏同步都会让两者长期不一致。
 */
internal fun Context.setUsbAttachEntryEnabled(enabled: Boolean) {
    val component = ComponentName(this, UsbAttachActivity::class.java)
    val state = if (enabled) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
    // 失败不上抛：调用方是长期订阅开关的协程，一次写入失败不该把后续的同步一起带走，
    // 留痕即可——真出这种事，表现是开关与弹窗对不上，日志是唯一的线索
    runCatching {
        // 已是目标状态就不再写：那是一次跨进程的持久设置写入，重复写只多一次开销
        if (packageManager.getComponentEnabledSetting(component) != state) {
            // 只改组件状态，不带杀进程：本应用正跑着，切换开关不该把它重启一遍
            packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }.onFailure { CrashLogManager.logException(LOG_TAG, "同步 USB 接入入口的启用状态失败", it) }
}
