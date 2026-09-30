package com.yichao.evilgodxu.screens.home.component.queue

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.AppDialog
import com.yichao.evilgodxu.ui.component.DialogOption
import com.yichao.evilgodxu.ui.icons.AppIcons

/**
 * 高级菜单对话框：五项操作随曲目可用性整体置灰。
 *
 * 不设取消入口，点击遮罩或返回键即可收起。
 */
@Composable
internal fun TrackAdvancedMenuDialog(
    visible: Boolean,
    actionsEnabled: Boolean,
    onShare: () -> Unit,
    onSetRingtone: () -> Unit,
    onSetAlarm: () -> Unit,
    onViewSpectrum: () -> Unit,
    onEditMetadata: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.playlist_advanced_menu_title),
    ) {
        // 置灰原因随菜单一并给出，避免用户把「不可点」当成故障
        if (!actionsEnabled) {
            Text(
                text = stringResource(R.string.playlist_advanced_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(4.dp))
        }
        DialogOption(
            icon = AppIcons.Share,
            label = stringResource(R.string.playlist_advanced_menu_share),
            enabled = actionsEnabled,
            onClick = onShare,
        )
        DialogOption(
            icon = AppIcons.Notifications,
            label = stringResource(R.string.playlist_advanced_menu_ringtone),
            enabled = actionsEnabled,
            onClick = onSetRingtone,
        )
        DialogOption(
            icon = AppIcons.Alarm,
            label = stringResource(R.string.playlist_advanced_menu_alarm),
            enabled = actionsEnabled,
            onClick = onSetAlarm,
        )
        DialogOption(
            icon = AppIcons.Equalizer,
            label = stringResource(R.string.playlist_advanced_menu_spectrum),
            enabled = actionsEnabled,
            onClick = onViewSpectrum,
        )
        DialogOption(
            icon = AppIcons.Edit,
            label = stringResource(R.string.playlist_advanced_menu_metadata),
            enabled = actionsEnabled,
            onClick = onEditMetadata,
        )
    }
}
