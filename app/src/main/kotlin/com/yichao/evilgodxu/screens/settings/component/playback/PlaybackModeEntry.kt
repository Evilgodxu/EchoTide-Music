package com.yichao.evilgodxu.screens.settings.component.playback

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.component.section.GroupCard
import com.yichao.evilgodxu.ui.icons.AppIcons

// 播放模式入口：悬浮播放、切歌手势与 USB 输出都在页内层级展开，读写走设置页自己的状态
@Composable
fun PlaybackModeEntry(onClick: () -> Unit) {
    GroupCard(title = stringResource(R.string.settings_section_player)) {
        SettingsEntry(
            icon = AppIcons.PlayArrow,
            title = stringResource(R.string.settings_entry_playback_mode),
            subtitle = stringResource(R.string.settings_entry_playback_mode_desc),
            onClick = onClick,
        )
    }
}
