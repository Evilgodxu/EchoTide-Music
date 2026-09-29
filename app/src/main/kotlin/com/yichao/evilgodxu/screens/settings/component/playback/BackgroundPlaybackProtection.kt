package com.yichao.evilgodxu.screens.settings.component.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.permission.isBatteryOptimizationIgnored
import com.yichao.evilgodxu.permission.requestIgnoreBatteryOptimizations
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.icons.AppIcons

// 后台播放保护：引导用户加入电池优化白名单，使熄屏播放不被系统电源策略限制
@Composable
internal fun BackgroundPlaybackProtection() {
    val context = LocalContext.current
    var ignored by remember { mutableStateOf(isBatteryOptimizationIgnored(context)) }
    // 授权状态由系统页改写，回到前台时重新读取
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ignored = isBatteryOptimizationIgnored(context)
    }
    SettingsEntry(
        icon = AppIcons.Notifications,
        title = stringResource(R.string.settings_battery_whitelist_title),
        subtitle = stringResource(
            if (ignored) {
                R.string.settings_battery_whitelist_granted
            } else {
                R.string.settings_battery_whitelist_desc
            }
        ),
        onClick = { if (!ignored) requestIgnoreBatteryOptimizations(context) },
    )
}
