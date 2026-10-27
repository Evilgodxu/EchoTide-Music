package com.yichao.evilgodxu.permission

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.core.net.toUri

/**
 * 图片访问（含 Android 14 的「仅选中的照片」）。
 *
 * 本地封面候选来自系统图片库，这是它唯一的权限前提：选封面时就地申请，不在启动期要求。
 */
fun hasImageAccess(context: Context): Boolean =
    context.checkSelfPermission(mediaImagePermission()) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
        PackageManager.PERMISSION_GRANTED

/**
 * 写回音频文件元数据需要「全部文件」访问。
 *
 * 缺权限时打开系统授权页并返回 false。两种调用方：一是元数据页在打开时就预申请，
 * 二是写标签、换封面这类动作在落笔前的兜底——两处都不该把这项权限放进启动流程。
 * 系统页无按应用直达入口时回退到应用详情页。
 */
fun ensureAllFilesAccess(context: Context): Boolean {
    if (Environment.isExternalStorageManager()) return true
    val direct = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        "package:${context.packageName}".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:${context.packageName}".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(direct) }
        .onFailure { runCatching { context.startActivity(fallback) } }
    // 系统不会在授权后自动送回本应用：跳过去之后跟着授权状态，一授予就把任务带回来
    PermissionReturnMonitor.watch(context, PermissionType.MANAGE_EXTERNAL_STORAGE)
    return false
}

/**
 * 免打扰访问权：USB 直出期间要把系统置为「仅闹钟」，缺它就只能挡不住通知与铃声。
 *
 * 只在打开直出开关时申请——它只服务于这一条输出路径。系统没有按应用直达页，
 * 只能打开勿扰访问列表由用户在其中勾选；该页在少数 ROM 上缺失时回退到应用详情页。
 */
fun requestNotificationPolicyAccessIfNeeded(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.isNotificationPolicyAccessGranted) return
    val policyPage = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:${context.packageName}".toUri(),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(policyPage) }
        .onFailure { runCatching { context.startActivity(fallback) } }
    PermissionReturnMonitor.watch(context, PermissionType.NOTIFICATION_POLICY)
}
