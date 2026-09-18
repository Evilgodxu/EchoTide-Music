package com.yichao.evilgodxu.permission

import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.os.Environment
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn

// 需要申请的核心权限类型
enum class PermissionType {
    OVERLAY,                 // 悬浮窗（系统特殊权限）
    MANAGE_EXTERNAL_STORAGE, // 全部文件（系统特殊权限）
    WRITE_SETTINGS,          // 修改系统设置（系统特殊权限）
    MEDIA_AUDIO,             // 音乐访问（运行时权限）
    MEDIA_IMAGES,            // 图片访问（运行时权限）
}

// 音乐访问的运行时权限名
fun mediaAudioPermission(): String = Manifest.permission.READ_MEDIA_AUDIO

// 图片访问的运行时权限名
fun mediaImagePermission(): String = Manifest.permission.READ_MEDIA_IMAGES

// 权限状态监控器
class PermissionMonitor(private val context: Context) {

    fun isOverlayGranted(): Boolean = Settings.canDrawOverlays(context)

    fun isAllFilesGranted(): Boolean = Environment.isExternalStorageManager()

    fun isWriteSettingsGranted(): Boolean = Settings.System.canWrite(context)

    fun isMediaAudioGranted(): Boolean =
        context.checkSelfPermission(mediaAudioPermission()) == PackageManager.PERMISSION_GRANTED

    fun isMediaImageGranted(): Boolean =
        context.checkSelfPermission(mediaImagePermission()) == PackageManager.PERMISSION_GRANTED

    fun isGranted(permissionType: PermissionType): Boolean = when (permissionType) {
        PermissionType.OVERLAY -> isOverlayGranted()
        PermissionType.MANAGE_EXTERNAL_STORAGE -> isAllFilesGranted()
        PermissionType.WRITE_SETTINGS -> isWriteSettingsGranted()
        PermissionType.MEDIA_AUDIO -> isMediaAudioGranted()
        PermissionType.MEDIA_IMAGES -> isMediaImageGranted()
    }

    // 持续监控指定权限，直到授权后返回 true
    fun monitorPermission(permissionType: PermissionType, intervalMs: Long = 500): Flow<Boolean> = flow {
        while (true) {
            val granted = isGranted(permissionType)
            emit(granted)
            if (granted) break
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)
}
