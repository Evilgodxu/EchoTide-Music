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

/**
 * 启动期的三段授权，按「音频 → 通知 → 全部文件」串行推进。
 *
 * 每一项只在缺它时才弹，且每轮启动最多问一次：用户拒绝后不追问，下次冷启动重走一条链，
 * 期间各写操作触发点会就地再申请。音频是唯一的前提——没有它读不到曲目；通知服务于「能正常听」；
 * 全部文件排在最后，写回音频标签与封面才需要它，不该挡在能听歌之前。
 */
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
    // 授权页返回即收束本链：无论用户是否拨开开关，都不再追问同一次启动
    val requestAllFilesAccess = rememberAllFilesAccessRequest { stage = STAGE_DONE }

    LaunchedEffect(stage, mediaAudioGranted) {
        when (stage) {
            STAGE_AUDIO -> stage = when {
                // 状态流可能落后于系统实况，故两处都认：已授权就不必再弹一次系统窗
                mediaAudioGranted || permissionMonitor.isMediaAudioGranted() -> {
                    onAudioGranted()
                    STAGE_NOTIFICATION
                }
                // 本轮已问过仍缺：不再重复弹系统窗，直接推进，交由首页空态承担
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
