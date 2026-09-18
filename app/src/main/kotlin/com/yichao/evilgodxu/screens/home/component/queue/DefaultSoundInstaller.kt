package com.yichao.evilgodxu.screens.home.component.queue

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.clip.RingtoneInstallResult
import com.yichao.evilgodxu.data.music.clip.RingtoneUsage
import com.yichao.evilgodxu.data.music.clip.setTrackAsDefaultSound
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.delay

// 等待用户在系统设置页授予「修改系统设置」的轮询间隔
private const val WRITE_SETTINGS_POLL_MS = 500L

// 一次「设为默认铃声」请求；attempt 递增用于授权回来后重新提交 —— 同值不会重启副作用
internal data class DefaultSoundRequest(
    val track: MusicTrack,
    val usage: RingtoneUsage,
    val attempt: Int = 0,
)

/**
 * 提交请求并处理授权往返：无界面，请求为 null 时不做任何事。
 *
 * 权限缺失时拉起系统设置页，回到应用后轮询感知并自动重试，用户不必再走一次菜单。
 */
@Composable
internal fun DefaultSoundInstallerHost(
    request: DefaultSoundRequest?,
    onRequestChange: (DefaultSoundRequest?) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(request) {
        val target = request ?: return@LaunchedEffect
        when (setTrackAsDefaultSound(context, target.track, target.usage)) {
            RingtoneInstallResult.Success -> {
                Toast.makeText(context, context.getString(doneTextRes(target.usage)), Toast.LENGTH_SHORT).show()
                onRequestChange(null)
            }

            RingtoneInstallResult.WriteSettingsRequired -> {
                Toast.makeText(context, R.string.default_sound_need_write_settings, Toast.LENGTH_LONG).show()
                openWriteSettings(context)
                while (!Settings.System.canWrite(context)) delay(WRITE_SETTINGS_POLL_MS)
                onRequestChange(target.copy(attempt = target.attempt + 1))
            }

            RingtoneInstallResult.Failed -> {
                Toast.makeText(context, R.string.default_sound_failed, Toast.LENGTH_SHORT).show()
                onRequestChange(null)
            }
        }
    }
}

private fun doneTextRes(usage: RingtoneUsage): Int =
    if (usage == RingtoneUsage.RINGTONE) R.string.default_sound_ringtone_done else R.string.default_sound_alarm_done

// 拉起「修改系统设置」授权页；LocalContext 是本地化包装 context，非 Activity 时需加 NEW_TASK
private fun openWriteSettings(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri())
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
