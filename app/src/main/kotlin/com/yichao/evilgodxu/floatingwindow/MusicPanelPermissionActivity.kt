package com.yichao.evilgodxu.floatingwindow

import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri

// 透明权限申请 Activity，用于从 Service/无障碍服务上下文动态申请权限：
// 1. 申请 READ_MEDIA_AUDIO（音频文件访问）
// 2. 申请 READ_MEDIA_IMAGES（读取本地封面候选图片）
// 3. 音频权限申请完后，若未授予全部文件访问权限，自动跳转系统设置由用户手动授予
class MusicPanelPermissionActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 当前权限申请流程结束（无论授予与否），继续检查下一个权限
        requestNextPermission()
    }

    // 音乐面板为高优先级悬浮窗，需等用户从系统设置返回后再展示，避免遮挡设置页
    private val allFilesSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        completeAndFinish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNextPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        MusicPanelPermissionBridge.clearPendingShowAction()
    }

    // 链式申请缺失的权限：音频 → 图片 → 全部文件访问
    private fun requestNextPermission() {
        when {
            hasAudioPermission() && hasImagePermission() && hasAllFilesAccess() -> completeAndFinish()
            !hasAudioPermission() -> permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
            !hasImagePermission() -> permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
            else -> launchAllFilesSettings()
        }
    }

    private fun hasAudioPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun hasImagePermission(): Boolean {
        // 与 PermissionMonitor 同口径：只拿到「选中的照片」也足以挑选封面，不再重复索要
        return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    private fun launchAllFilesSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:$packageName".toUri()
        )
        try {
            allFilesSettingsLauncher.launch(intent)
        } catch (e: Exception) {
            // 部分 ROM 不支持该入口时兜底跳转应用详情页
            allFilesSettingsLauncher.launch(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:$packageName".toUri()
                )
            )
        }
    }

    private fun completeAndFinish() {
        MusicPanelPermissionBridge.takePendingShowAction()?.invoke()
        finish()
    }
}

// 权限申请与面板显示的桥接对象
object MusicPanelPermissionBridge {
    private var pendingToken = 0L
    private var pendingAction: (() -> Unit)? = null

    var pendingShowAction: (() -> Unit)?
        @Synchronized get() = pendingAction
        @Synchronized set(value) {
            pendingToken++
            pendingAction = value
        }

    @Synchronized
    fun clearPendingShowAction() {
        pendingToken++
        pendingAction = null
    }

    @Synchronized
    fun takePendingShowAction(): (() -> Unit)? {
        pendingToken++
        return pendingAction.also { pendingAction = null }
    }
}
