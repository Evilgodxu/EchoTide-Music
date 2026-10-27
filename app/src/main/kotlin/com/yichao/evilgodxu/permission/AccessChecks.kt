package com.yichao.evilgodxu.permission

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
 * 是否已获「全部文件访问」。
 *
 * 写回音频标签、导出封面这类动作需要它。这里只做检查——申请一律由界面用
 * ActivityResult 打开系统页（见 [allFilesAccessIntent]），返回时回到原任务，
 * 不靠后台拉起，也不会把应用推到一个新任务里去。
 */
fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

/** 打开本应用的「全部文件访问」系统页：由界面以 ActivityResult 发起 */
fun allFilesAccessIntent(context: Context): Intent = Intent(
    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
    "package:${context.packageName}".toUri(),
)

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

/** 免打扰访问权的授权页：供界面以 ActivityResult 发起 */
fun notificationPolicyAccessIntent(): Intent =
    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
