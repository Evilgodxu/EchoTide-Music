package com.yichao.evilgodxu.screens.settings.component.blacklist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.component.DialogCard
import com.yichao.evilgodxu.ui.component.section.GroupCard
import com.yichao.evilgodxu.ui.icons.AppIcons

// 黑名单设置：展示已拉黑数量，重置后曲目重新参与播放列表展示与每日推荐
@Composable
fun Blacklist(
    blockedCount: Int,
    onReset: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }

    GroupCard(title = stringResource(R.string.settings_section_blacklist)) {
        SettingsEntry(
            icon = AppIcons.Block,
            title = stringResource(R.string.settings_blacklist_reset_title),
            subtitle = stringResource(R.string.settings_blacklist_reset_desc, blockedCount),
            onClick = { showConfirm = true },
        )
    }

    if (showConfirm) {
        DialogCard(onDismiss = { showConfirm = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.settings_blacklist_reset_confirm_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.settings_blacklist_reset_confirm_message),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.widthIn(max = 200.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        onClick = { showConfirm = false },
                    ) {
                        Text(
                            text = stringResource(R.string.settings_blacklist_reset_cancel),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.error,
                        onClick = {
                            showConfirm = false
                            onReset()
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.settings_blacklist_reset_confirm),
                            color = MaterialTheme.colorScheme.onError,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}