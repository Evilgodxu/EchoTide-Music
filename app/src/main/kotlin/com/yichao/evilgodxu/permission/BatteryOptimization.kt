package com.yichao.evilgodxu.permission

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

// 是否已加入电池优化白名单（电源白名单）。未加入时设备熄屏静止约半小时后进入 Doze，
// Doze 会屏蔽应用唤醒锁并把网络限制在维护窗口，后台播放因此被系统中断
fun isBatteryOptimizationIgnored(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

// 拉起系统授权对话框申请电池优化白名单；部分 ROM 未实现定向授权入口，回退到电池优化列表由用户手动选择。
// 有宿主 Activity 时在宿主任务内打开：授权页关闭后系统把本任务带回前台，用户无需自行找回应用。
// 落到独立任务时该任务关闭后无处可回，只能依赖后台启动 Activity，而这一步会被多数 ROM 拦截。
// 无宿主 Activity（非 Activity 上下文）才需要 NEW_TASK，否则启动会抛异常
@SuppressLint("BatteryLife")
fun requestIgnoreBatteryOptimizations(context: Context, activity: Activity?) {
    val grantedIntent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        "package:${context.packageName}".toUri(),
    )
    val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    val target: Context = activity ?: context
    if (activity == null) {
        grantedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { target.startActivity(grantedIntent) }
        .onFailure { runCatching { target.startActivity(settingsIntent) } }
}
