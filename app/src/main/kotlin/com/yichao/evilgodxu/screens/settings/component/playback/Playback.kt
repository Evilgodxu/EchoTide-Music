package com.yichao.evilgodxu.screens.settings.component.playback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.component.AppSwitch
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.section.GroupCard

// 播放设置：悬浮播放与逐字渲染开关、滑动切歌与背景流动开关、排版入口
@Composable
fun Playback(
    miniPlayerEnabled: Boolean,
    onMiniPlayerEnabledChange: (Boolean) -> Unit,
    wordByWordRendering: Boolean,
    onWordByWordRenderingChange: (Boolean) -> Unit,
    swipeToChangeTrack: Boolean,
    onSwipeToChangeTrackChange: (Boolean) -> Unit,
    backgroundFlow: Boolean,
    onBackgroundFlowChange: (Boolean) -> Unit,
    onTypographyClick: () -> Unit,
) {
    GroupCard(title = stringResource(R.string.settings_section_player)) {
        PlayerSwitchRow(
            title = stringResource(R.string.settings_mini_player_title),
            description = stringResource(R.string.settings_mini_player_desc),
            checked = miniPlayerEnabled,
            onCheckedChange = onMiniPlayerEnabledChange,
        )
        PlayerSwitchRow(
            title = stringResource(R.string.settings_word_by_word_title),
            description = stringResource(R.string.settings_word_by_word_desc),
            checked = wordByWordRendering,
            onCheckedChange = onWordByWordRenderingChange,
        )
        PlayerSwitchRow(
            title = stringResource(R.string.settings_swipe_track_title),
            description = stringResource(R.string.settings_swipe_track_desc),
            checked = swipeToChangeTrack,
            onCheckedChange = onSwipeToChangeTrackChange,
        )
        PlayerSwitchRow(
            title = stringResource(R.string.settings_background_flow_title),
            description = stringResource(R.string.settings_background_flow_desc),
            checked = backgroundFlow,
            onCheckedChange = onBackgroundFlowChange,
        )
        SettingsEntry(
            icon = AppIcons.TextFields,
            title = stringResource(R.string.settings_typography_title),
            subtitle = stringResource(R.string.settings_typography_desc),
            onClick = onTypographyClick,
        )
    }
}

@Composable
private fun PlayerSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = description,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.width(12.dp))
        AppSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
