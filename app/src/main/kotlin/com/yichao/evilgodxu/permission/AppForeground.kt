package com.yichao.evilgodxu.permission

import android.app.Activity
import android.content.Intent

// 把应用带回前台：系统特殊权限授权发生在系统设置页，授权后不应让用户自己找回来
fun bringAppToFront(activity: Activity) {
    val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName) ?: return
    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or
        Intent.FLAG_ACTIVITY_NEW_TASK
    activity.startActivity(intent)
}
