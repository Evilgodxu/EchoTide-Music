package com.yichao.evilgodxu.permission

import android.annotation.SuppressLint
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

// 拉起系统授权对话框申请电池优化白名单；部分 ROM 未实现定向授权入口，回退到电池优化列表由用户手动选择
@SuppressLint("BatteryLife")
fun requestIgnoreBatteryOptimizations(context: Context) {
    val grantedIntent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        "package:${context.packageName}".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(grantedIntent) }
        .onFailure { runCatching { context.startActivity(settingsIntent) } }
}
