package com.yichao.evilgodxu.permission

import android.content.Context
import android.content.Intent

// 把应用带回前台；悬浮窗封面入口同样走此处
fun bringAppToFront(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or
        Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(intent)
}
