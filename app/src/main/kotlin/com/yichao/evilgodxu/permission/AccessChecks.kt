package com.yichao.evilgodxu.permission

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.Settings
import androidx.core.net.toUri

/** 图片访问（含 Android 14 仅选中照片），选封面时申请 */
fun hasImageAccess(context: Context): Boolean =
    context.checkSelfPermission(mediaImagePermission()) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
        PackageManager.PERMISSION_GRANTED

/** 是否已获全部文件访问；申请走 rememberAllFilesAccessRequest */
fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

/** 文件写操作前置：缺权限就地申请并返回 false */
fun ensureTrackFileWritable(onRequestAccess: () -> Unit): Boolean {
    if (hasAllFilesAccess()) return true
    onRequestAccess()
    return false
}

/** 全部文件访问的系统授权页 */
fun allFilesAccessIntent(context: Context): Intent = Intent(
    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
    "package:${context.packageName}".toUri(),
)

/** 免打扰访问权是否已授予 */
fun isNotificationPolicyGranted(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

/** 免打扰访问权的授权页 */
fun notificationPolicyAccessIntent(): Intent =
    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
