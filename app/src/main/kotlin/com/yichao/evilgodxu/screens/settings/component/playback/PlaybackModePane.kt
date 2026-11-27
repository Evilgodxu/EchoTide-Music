package com.yichao.evilgodxu.screens.settings.component.playback

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.SettingsScrollColumn
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsSwitchRow
import com.yichao.evilgodxu.ui.component.section.GroupCard

/** 播放模式页：交互与输出两组开关 */
@Composable
internal fun PlaybackModePane(
    miniPlayerEnabled: Boolean,
    swipeToChangeTrack: Boolean,
    usbDirectOutput: Boolean,
    usbExclusiveOutput: Boolean,
    volumeGain: Boolean,
    innerPadding: PaddingValues,
    onMiniPlayerEnabledChange: (Boolean) -> Unit,
    onSwipeToChangeTrackChange: (Boolean) -> Unit,
    onUsbDirectOutputChange: (Boolean) -> Unit,
    onUsbExclusiveOutputChange: (Boolean) -> Unit,
    onVolumeGainChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScrollColumn(innerPadding = innerPadding, modifier = modifier) {
        GroupCard(title = stringResource(R.string.settings_section_interaction)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_mini_player_title),
                description = stringResource(R.string.settings_mini_player_desc),
                checked = miniPlayerEnabled,
                onCheckedChange = onMiniPlayerEnabledChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_swipe_track_title),
                description = stringResource(R.string.settings_swipe_track_desc),
                checked = swipeToChangeTrack,
                onCheckedChange = onSwipeToChangeTrackChange,
            )
        }
        GroupCard(title = stringResource(R.string.settings_section_output)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_volume_gain_title),
                description = stringResource(R.string.settings_volume_gain_desc),
                checked = volumeGain,
                onCheckedChange = onVolumeGainChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_usb_direct_output_title),
                description = stringResource(R.string.settings_usb_direct_output_desc),
                checked = usbDirectOutput,
                onCheckedChange = onUsbDirectOutputChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_usb_exclusive_title),
                description = stringResource(R.string.settings_usb_exclusive_desc),
                checked = usbExclusiveOutput,
                onCheckedChange = onUsbExclusiveOutputChange,
            )
        }
    }
}
