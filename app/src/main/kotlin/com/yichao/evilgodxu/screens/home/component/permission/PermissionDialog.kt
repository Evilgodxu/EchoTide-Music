package com.yichao.evilgodxu.screens.home.component.permission

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.data.settings.usbDirectOutputModeFlow
import com.yichao.evilgodxu.permission.bluetoothConnectPermission
import com.yichao.evilgodxu.permission.isBatteryOptimizationIgnored
import com.yichao.evilgodxu.permission.mediaAudioPermission
import com.yichao.evilgodxu.permission.mediaImagePermission
import com.yichao.evilgodxu.permission.notificationPermission
import com.yichao.evilgodxu.permission.requestIgnoreBatteryOptimizations
import com.yichao.evilgodxu.permission.PermissionType
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.home.HomeUiState
import com.yichao.evilgodxu.ui.icons.AppIcons

// 权限状态对话框：只列出缺失的权限并逐项申请，某项授予后该行即时消失，全部授权后对话框自动隐藏。
// 所有权限统一由这里的按钮发起：系统弹窗没有用途说明也不体现先后顺序，
// 启动时替用户弹出会让其在不了解用途的情况下授权与拒绝
@Composable
fun PermissionDialog(
    uiState: HomeUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onStartPermissionMonitor: (PermissionType, Activity) -> Unit = { _, _ -> },
    onStopPermissionMonitor: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // USB 直出开关与设置页共用同一偏好：免打扰权限只在该开关开启时才有用途
    val usbDirectOutput by context.usbDirectOutputModeFlow()
        .collectAsStateWithLifecycle(initialValue = false)
    // LocalContext 为本地化包装 context，宿主 Activity 需从注册表所有者获取
    val activity = LocalActivityResultRegistryOwner.current as? Activity

    // 电池优化白名单：已加入时不重复申请；授权页在宿主任务内打开，返回即回到本应用，
    // 权限监控再兜底把应用带回前台
    val requestBatteryWhitelist: () -> Unit = {
        if (!isBatteryOptimizationIgnored(context)) {
            activity?.let { onStartPermissionMonitor(PermissionType.BATTERY_OPTIMIZATION, it) }
            requestIgnoreBatteryOptimizations(context, activity)
        }
    }

    // 运行时权限（音乐访问、图片、蓝牙、通知）申请结果回调后统一刷新状态
    val runtimePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefresh()
    }

    // 通知权限单独申请：需要在回调里判断系统是否还愿意弹窗，以决定后续是否改跳通知设置
    var notificationRequested by remember { mutableStateOf(false) }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationRequested = true
        onRefresh()
    }

    // 通知权限申请：系统还能弹窗时直接申请；已被永久拒绝（弹窗不再出现）则改跳通知设置，
    // 并由权限监控在系统页授权后把应用带回前台，避免点击后毫无反馈
    val requestNotificationPermission: () -> Unit = {
        val host = activity
        val dialogAvailable = !notificationRequested ||
            host?.shouldShowRequestPermissionRationale(notificationPermission()) == true
        if (dialogAvailable) {
            notificationLauncher.launch(notificationPermission())
        } else {
            host?.let { onStartPermissionMonitor(PermissionType.NOTIFICATIONS, it) }
            launchNotificationSettings(context, host)
        }
    }

    // 从系统设置页返回时刷新权限状态并停止监控
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefresh()
                onStopPermissionMonitor()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onStopPermissionMonitor()
        }
    }

    // 核心权限齐备、仅可选权限缺失时允许关闭：拒绝后首页不应被永久占用。
    // 关闭状态跨页面跳转保留，下次启动时重新列出
    val dismissible = uiState.blockingPermissionsGranted
    var dismissed by rememberSaveable { mutableStateOf(false) }

    // 免打扰访问只在启用 USB 直出后才需要，不计入 allPermissionsSatisfied，故单独判定其缺失
    val notificationPolicyNeeded = usbDirectOutput && !uiState.notificationPolicyGranted

    if ((!uiState.allPermissionsSatisfied || notificationPolicyNeeded) && !dismissed) {
        Dialog(
            onDismissRequest = { if (dismissible) dismissed = true },
            properties = DialogProperties(
                dismissOnBackPress = dismissible,
                dismissOnClickOutside = dismissible,
            ),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Column(
                    // 可选权限逐项补入后行数不定，横屏或小屏下需可滚动，避免末项被窗口裁掉
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.home_permission_title),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.home_permission_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!uiState.allFilesGranted) {
                        PermissionCardRow(
                            icon = {
                                Icon(
                                    AppIcons.Folder,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            title = stringResource(R.string.permission_all_files_title),
                            onRequest = {
                                // 跳转系统设置前启动权限监控，授权后自动返回本应用
                                activity?.let {
                                    onStartPermissionMonitor(
                                        PermissionType.MANAGE_EXTERNAL_STORAGE,
                                        it,
                                    )
                                }
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    "package:${context.packageName}".toUri(),
                                )
                                if (activity != null) {
                                    activity.startActivity(intent)
                                } else {
                                    // 无宿主 Activity 时需加 NEW_TASK
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }
                            },
                        )
                    }
                    if (!uiState.mediaAudioGranted) {
                        PermissionCardRow(
                            icon = {
                                Icon(
                                    AppIcons.MusicNote,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            title = stringResource(R.string.permission_music_title),
                            onRequest = {
                                runtimePermissionLauncher.launch(arrayOf(mediaAudioPermission()))
                            },
                        )
                    }
                    if (!uiState.mediaImageGranted) {
                        PermissionCardRow(
                            icon = {
                                Icon(
                                    AppIcons.Image,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            title = stringResource(R.string.permission_image_title),
                            onRequest = {
                                runtimePermissionLauncher.launch(arrayOf(mediaImagePermission()))
                            },
                        )
                    }
                    // 蓝牙、通知与电池优化白名单同样仅在缺失时列出，但三者都不参与对话框关闭判定：
                    // 否则用户拒绝其一就会让首页被权限对话框永久占用
                    if (!uiState.bluetoothConnectGranted) {
                        PermissionCardRow(
                            icon = {
                                Icon(
                                    AppIcons.Bluetooth,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            title = stringResource(R.string.permission_bluetooth_title),
                            onRequest = {
                                runtimePermissionLauncher.launch(arrayOf(bluetoothConnectPermission()))
                            },
                        )
                    }
                    if (!uiState.notificationGranted) {
                        PermissionCardRow(
                            icon = {
                                Icon(
                                    AppIcons.Notifications,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            title = stringResource(R.string.permission_notification_title),
                            onRequest = requestNotificationPermission,
                        )
                    }
                    if (!uiState.batteryWhitelistGranted) {
                        PermissionCardRow(
                            icon = {
                                Icon(
                                    AppIcons.BatterySaver,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            title = stringResource(R.string.permission_battery_title),
                            onRequest = requestBatteryWhitelist,
                        )
                    }
                    // 免打扰访问：仅启用 USB 直出后列出——直出期间置为「仅闹钟」可挡掉通知与铃声，
                    // 未启用直出则用不到，不该被要求授予系统特殊权限
                    if (notificationPolicyNeeded) {
                        PermissionCardRow(
                            icon = {
                                Icon(
                                    AppIcons.Block,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                            title = stringResource(R.string.permission_dnd_title),
                            onRequest = {
                                activity?.let {
                                    onStartPermissionMonitor(PermissionType.NOTIFICATION_POLICY, it)
                                }
                                launchNotificationPolicySettings(context, activity)
                            },
                        )
                    }
                }
            }
        }
    }
}

// 跳转本应用的通知设置页；定向入口在部分 ROM 上不存在时回退到应用详情页。
// 与全部文件访问的跳转一致：无宿主 Activity 时需加 NEW_TASK
private fun launchNotificationSettings(context: Context, activity: Activity?) {
    val notificationIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    val detailsIntent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:${context.packageName}".toUri(),
    )
    val flags = if (activity == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0
    val target = activity ?: context
    runCatching { target.startActivity(notificationIntent.addFlags(flags)) }
        .onFailure { runCatching { target.startActivity(detailsIntent.addFlags(flags)) } }
}

// 跳转系统的勿扰访问授权列表（免打扰访问权只有公开的这个入口，无按应用直达页），由用户在其中为本应用开启；
// 该页在少数 ROM 上缺失时回退到应用详情页。与通知设置跳转一致：无宿主 Activity 时需加 NEW_TASK
private fun launchNotificationPolicySettings(context: Context, activity: Activity?) {
    val flags = if (activity == null) Intent.FLAG_ACTIVITY_NEW_TASK else 0
    val target: Context = activity ?: context
    val policyIntent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    val detailsIntent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:${context.packageName}".toUri(),
    )
    runCatching { target.startActivity(policyIntent.addFlags(flags)) }
        .onFailure { runCatching { target.startActivity(detailsIntent.addFlags(flags)) } }
}
