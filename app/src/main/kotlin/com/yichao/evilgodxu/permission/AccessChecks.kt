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
 * 写回音频标签、导出封面这类动作需要它。这里只做检查，申请一律走
 * [rememberAllFilesAccessRequest]。
 */
fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

/**
 * 长按动作菜单的统一前置：菜单里的删除、重命名、改歌词与封面都要写回音频文件。
 *
 * 缺「全部文件访问」时就地申请一次并返回 false：菜单里每一项都要这项权限，展开了也只能空转。
 * 用户当场拒绝后不再追问，等下次启动链末段或下一次触发写操作再问。
 */
fun ensureTrackFileWritable(onRequestAccess: () -> Unit): Boolean {
    if (hasAllFilesAccess()) return true
    onRequestAccess()
    return false
}

/** 打开本应用的「全部文件访问」系统页：由 [rememberAllFilesAccessRequest] 以 ActivityResult 发起 */
fun allFilesAccessIntent(context: Context): Intent = Intent(
    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
    "package:${context.packageName}".toUri(),
)

/**
 * 免打扰访问权是否已授予。
 *
 * 系统没有按应用直达页，缺它时由界面用 ActivityResult 打开勿扰访问列表
 * （见 [notificationPolicyAccessIntent]），与该列表同任务往返。
 */
fun isNotificationPolicyGranted(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

/** 免打扰访问权的授权页：供界面以 ActivityResult 发起 */
fun notificationPolicyAccessIntent(): Intent =
    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
