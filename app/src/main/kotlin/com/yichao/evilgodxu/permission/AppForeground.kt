package com.yichao.evilgodxu.permission

import android.content.Context
import android.content.Intent

// 把应用带回前台：系统特殊权限授权发生在系统设置页，授权后不应让用户自己找回来；
// 悬浮窗一侧（迷你播放器的封面入口）同样经此回到应用主界面，两处口径一致
fun bringAppToFront(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or
        Intent.FLAG_ACTIVITY_NEW_TASK
    context.startActivity(intent)
}
