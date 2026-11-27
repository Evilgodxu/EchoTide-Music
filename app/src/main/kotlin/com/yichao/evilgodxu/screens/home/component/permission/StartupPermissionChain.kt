package com.yichao.evilgodxu.screens.home.component.permission

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yichao.evilgodxu.permission.PermissionMonitor
import com.yichao.evilgodxu.permission.mediaAudioPermission
import com.yichao.evilgodxu.permission.notificationPermission
import com.yichao.evilgodxu.permission.rememberAllFilesAccessRequest

private const val REQUEST_AUDIO = "startup_audio"
private const val REQUEST_NOTIFICATION = "startup_notification"
private const val REQUEST_ALL_FILES = "startup_all_files"

/** 三段授权串行：音频→通知→全部文件，每轮至多一次 */
@Composable
internal fun StartupPermissionChain(
    mediaAudioGranted: Boolean,
    onAudioGranted: () -> Unit,
    markRequested: (String) -> Boolean,
) {
    val context = LocalContext.current
    val permissionMonitor = remember(context) { PermissionMonitor(context) }

    var stage by rememberSaveable { mutableIntStateOf(STAGE_AUDIO) }
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        onAudioGranted()
        stage = STAGE_NOTIFICATION
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        stage = STAGE_ALL_FILES
    }
    // 从授权页返回即结束本链，不再追问
    val requestAllFilesAccess = rememberAllFilesAccessRequest { stage = STAGE_DONE }

    LaunchedEffect(stage, mediaAudioGranted) {
        when (stage) {
            STAGE_AUDIO -> stage = when {
                // 状态流可能滞后，已授权就不再弹窗
                mediaAudioGranted || permissionMonitor.isMediaAudioGranted() -> {
                    onAudioGranted()
                    STAGE_NOTIFICATION
                }
                // 本轮已问过仍缺：跳过弹窗，交首页空态
                !markRequested(REQUEST_AUDIO) -> STAGE_NOTIFICATION
                else -> {
                    audioLauncher.launch(mediaAudioPermission())
                    return@LaunchedEffect
                }
            }
            STAGE_NOTIFICATION -> when {
                permissionMonitor.isNotificationGranted() -> stage = STAGE_ALL_FILES
                !markRequested(REQUEST_NOTIFICATION) -> stage = STAGE_ALL_FILES
                else -> {
                    notificationLauncher.launch(notificationPermission())
                    return@LaunchedEffect
                }
            }
            STAGE_ALL_FILES -> when {
                permissionMonitor.isAllFilesGranted() -> stage = STAGE_DONE
                !markRequested(REQUEST_ALL_FILES) -> stage = STAGE_DONE
                else -> {
                    requestAllFilesAccess()
                    return@LaunchedEffect
                }
            }
        }
    }
}

private const val STAGE_AUDIO = 0
private const val STAGE_NOTIFICATION = 1
private const val STAGE_ALL_FILES = 2
private const val STAGE_DONE = 3
