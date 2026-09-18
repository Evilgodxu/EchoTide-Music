package com.yichao.evilgodxu.screens.home.component.queue

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.clip.RingtoneInstallResult
import com.yichao.evilgodxu.data.music.clip.RingtoneUsage
import com.yichao.evilgodxu.data.music.clip.setTrackAsDefaultSound
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.permission.PermissionMonitor
import com.yichao.evilgodxu.permission.PermissionType
import com.yichao.evilgodxu.permission.bringAppToFront

// 一次「设为默认铃声」请求；attempt 递增用于授权回来后重新提交 —— 同值不会重启副作用
internal data class DefaultSoundRequest(
    val track: MusicTrack,
    val usage: RingtoneUsage,
    val attempt: Int = 0,
)

/**
 * 提交请求并处理授权往返：无界面，请求为 null 时不做任何事。
 *
 * 修改系统设置属系统特殊权限，走与权限对话框同一套节奏：跳转设置页前启动
 * [PermissionMonitor] 监控，授权后带回应用前台并自动重试，用户不必再走一次菜单；
 * 未授权就返回则收尾，不留无意义轮询。
 */
@Composable
internal fun DefaultSoundInstallerHost(
    request: DefaultSoundRequest?,
    onRequestChange: (DefaultSoundRequest?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // LocalContext 为本地化包装 context，宿主 Activity 需从注册表所有者获取
    val activity = LocalActivityResultRegistryOwner.current as? Activity
    val permissionMonitor = remember { PermissionMonitor(context) }
    // 等待授权期间的状态：不放进请求对象，否则改动会把提交协程一并重启
    var awaitingGrant by remember { mutableStateOf(false) }

    LaunchedEffect(request) {
        val target = request ?: return@LaunchedEffect
        awaitingGrant = false
        when (setTrackAsDefaultSound(context, target.track, target.usage)) {
            RingtoneInstallResult.Success -> {
                Toast.makeText(context, context.getString(doneTextRes(target.usage)), Toast.LENGTH_SHORT).show()
                onRequestChange(null)
            }

            RingtoneInstallResult.WriteSettingsRequired -> {
                awaitingGrant = true
                Toast.makeText(context, R.string.default_sound_need_write_settings, Toast.LENGTH_LONG).show()
                launchWriteSettings(context, activity)
                // 监控授予：授权那一刻把应用带回前台，再以递增的 attempt 触发一次真正的设置
                permissionMonitor.monitorPermission(PermissionType.WRITE_SETTINGS).collect { granted ->
                    if (!granted) return@collect
                    activity?.let { bringAppToFront(it) }
                    onRequestChange(target.copy(attempt = target.attempt + 1))
                }
            }

            RingtoneInstallResult.Failed -> {
                Toast.makeText(context, R.string.default_sound_failed, Toast.LENGTH_SHORT).show()
                onRequestChange(null)
            }
        }
    }

    // 从系统设置页返回时判定：已授权交给上面的监控续做，未授权则收尾停止轮询
    val pendingRequest by rememberUpdatedState(request)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            if (awaitingGrant && pendingRequest != null && !permissionMonitor.isWriteSettingsGranted()) {
                onRequestChange(null)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private fun doneTextRes(usage: RingtoneUsage): Int =
    if (usage == RingtoneUsage.RINGTONE) R.string.default_sound_ringtone_done else R.string.default_sound_alarm_done

// 跳转「修改系统设置」授权页；无宿主 Activity 时需加 NEW_TASK
private fun launchWriteSettings(context: Context, activity: Activity?) {
    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri())
    if (activity != null) {
        activity.startActivity(intent)
    } else {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
