package com.yichao.evilgodxu.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.yichao.evilgodxu.log.CrashLogManager

/** 组件状态的每次同步都记在此名下：它与开关分属两处，不一致时只能靠日志回溯 */
private const val LOG_TAG = "UsbAttachEntry"

/**
 * 让 USB 接入入口（[UsbAttachActivity]）的启用状态跟随 USB 独占开关。
 *
 * 系统的「用哪个应用打开」选择框只对声明了认领意图的设备出现，而清单里的声明是**静态**的：组件一直可用，
 * 插上解码器就必然弹窗。可这个弹窗只有一个用途——把设备的访问授权交出来供独占接管，开关关着时它既无
 * 用途，又会被读成「插上就自动开了独占」。故按开关取值启停组件：组件停用后其意图过滤器不再参与匹配，
 * 系统根本看不到本应用，弹窗自然不出现。
 *
 * 清单里已声明 `android:enabled="false"`，全新安装且从未打开过本应用时不参与匹配；开关一旦开启，
 * 由应用进程把它置为启用（此时进程必然在运行）。
 *
 * 调用方订阅的是**落盘值**而非界面回调：组件状态由系统持久保存，与开关分属两处，任何一处漏同步都会让
 * 两者长期不一致，而订阅同一个开关流则不受"谁改的开关"影响。
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
