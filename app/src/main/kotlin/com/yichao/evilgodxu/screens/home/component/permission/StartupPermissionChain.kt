package com.yichao.evilgodxu.screens.home.component.permission

import android.app.Activity
import androidx.activity.compose.LocalActivityResultRegistryOwner
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
import com.yichao.evilgodxu.permission.isBatteryOptimizationIgnored
import com.yichao.evilgodxu.permission.mediaAudioPermission
import com.yichao.evilgodxu.permission.notificationPermission
import com.yichao.evilgodxu.permission.requestIgnoreBatteryOptimizations

private const val REQUEST_AUDIO = "startup_audio"
private const val REQUEST_NOTIFICATION = "startup_notification"
private const val REQUEST_BATTERY = "startup_battery"

/**
 * 启动期的三段授权，按「音频 → 通知 → 省电策略」串行推进。
 *
 * 每一项只在缺它时才弹，且每轮启动最多问一次：用户拒绝后不追问，下次冷启动重走一条链。
 * 这里没有常驻的权限提示——所有权限都在用得上它的那一刻申请，本组件只负责把启动这三项排好序。
 * 音频是唯一的前提：没有它读不到曲目，其后的两项都服务于「能正常听」——通知给前台播放，
 * 省电白名单免 Doze 掐断熄屏播放。
 */
@Composable
internal fun StartupPermissionChain(
    mediaAudioGranted: Boolean,
    onAudioGranted: () -> Unit,
    markRequested: (String) -> Boolean,
) {
    val context = LocalContext.current
    val activity = LocalActivityResultRegistryOwner.current as? Activity
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
        stage = STAGE_BATTERY
    }

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
                permissionMonitor.isNotificationGranted() -> stage = STAGE_BATTERY
                !markRequested(REQUEST_NOTIFICATION) -> stage = STAGE_BATTERY
                else -> {
                    notificationLauncher.launch(notificationPermission())
                    return@LaunchedEffect
                }
            }
            STAGE_BATTERY -> {
                if (!isBatteryOptimizationIgnored(context) && markRequested(REQUEST_BATTERY)) {
                    requestIgnoreBatteryOptimizations(context, activity)
                }
                stage = STAGE_DONE
            }
        }
    }
}

private const val STAGE_AUDIO = 0
private const val STAGE_NOTIFICATION = 1
private const val STAGE_BATTERY = 2
private const val STAGE_DONE = 3
