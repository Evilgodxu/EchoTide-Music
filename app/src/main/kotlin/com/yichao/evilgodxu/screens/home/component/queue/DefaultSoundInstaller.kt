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
import androidx.compose.ui.platform.LocalResources
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

// 默认铃声请求；attempt 递增以重拉副作用
internal data class DefaultSoundRequest(
    val track: MusicTrack,
    val usage: RingtoneUsage,
    val attempt: Int = 0,
)

/** 提交请求并处理授权往返：授权后回前台重试 */
@Composable
internal fun DefaultSoundInstallerHost(
    request: DefaultSoundRequest?,
    onRequestChange: (DefaultSoundRequest?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 文案取 LocalResources，随配置变化刷新
    val resources = LocalResources.current
    // 宿主 Activity 从注册表所有者获取
    val activity = LocalActivityResultRegistryOwner.current as? Activity
    val permissionMonitor = remember { PermissionMonitor(context) }
    // 等待授权标志；放请求内会重启协程
    var awaitingGrant by remember { mutableStateOf(false) }

    LaunchedEffect(request) {
        val target = request ?: return@LaunchedEffect
        awaitingGrant = false
        when (setTrackAsDefaultSound(context, target.track, target.usage)) {
            RingtoneInstallResult.Success -> {
                Toast.makeText(context, resources.getString(doneTextRes(target.usage)), Toast.LENGTH_SHORT).show()
                onRequestChange(null)
            }

            RingtoneInstallResult.WriteSettingsRequired -> {
                awaitingGrant = true
                Toast.makeText(context, R.string.default_sound_need_write_settings, Toast.LENGTH_LONG).show()
                launchWriteSettings(context, activity)
                // 授权即回前台，并以递增 attempt 重试
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

    // 返回时未授权即收尾，已授权交上方监控
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

// 跳转授权页；无 Activity 时加 NEW_TASK
private fun launchWriteSettings(context: Context, activity: Activity?) {
    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri())
    if (activity != null) {
        activity.startActivity(intent)
    } else {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
