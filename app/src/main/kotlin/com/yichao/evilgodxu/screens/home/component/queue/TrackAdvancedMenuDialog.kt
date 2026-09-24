package com.yichao.evilgodxu.screens.home.component.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons

/**
 * 高级菜单对话框：四项操作随曲目可用性整体置灰。
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
    onDismiss: () -> Unit,
) {
    if (!visible) return
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.playlist_advanced_menu_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // 置灰原因随菜单一并给出，避免用户把「不可点」当成故障
            if (!actionsEnabled) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.playlist_advanced_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            AdvancedMenuItem(
                icon = AppIcons.Share,
                labelRes = R.string.playlist_advanced_menu_share,
                enabled = actionsEnabled,
                onClick = onShare,
            )
            AdvancedMenuItem(
                icon = AppIcons.Notifications,
                labelRes = R.string.playlist_advanced_menu_ringtone,
                enabled = actionsEnabled,
                onClick = onSetRingtone,
            )
            AdvancedMenuItem(
                icon = AppIcons.Alarm,
                labelRes = R.string.playlist_advanced_menu_alarm,
                enabled = actionsEnabled,
                onClick = onSetAlarm,
            )
            AdvancedMenuItem(
                icon = AppIcons.Equalizer,
                labelRes = R.string.playlist_advanced_menu_spectrum,
                enabled = actionsEnabled,
                onClick = onViewSpectrum,
            )
        }
    }
}

// 菜单项：图标与文案横排，整行可点；置灰时同步降低图标与文字的不透明度
@Composable
private fun AdvancedMenuItem(
    icon: ImageVector,
    labelRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = when {
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = stringResource(labelRes),
            color = contentColor,
            fontSize = 14.sp,
        )
    }
}
