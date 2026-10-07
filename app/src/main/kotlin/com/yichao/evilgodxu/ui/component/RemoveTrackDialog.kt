package com.yichao.evilgodxu.ui.component

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.R

// 曲目操作的统一确认弹窗（从歌单移除 / 永久删除 / 拉黑）：默认文案为「从歌单移除」，
// 标题、正文与确认文案按场景传入，按钮样式与其余确认框一致
@Composable
internal fun RemoveTrackDialog(
    track: MusicTrack?,
    onConfirm: (MusicTrack) -> Unit,
    onDismiss: () -> Unit,
    titleRes: Int = R.string.playlist_remove_track_title,
    messageRes: Int = R.string.playlist_remove_track_message,
    confirmRes: Int = R.string.playlist_remove_track_confirm,
    // 可选附加区：渲染在正文与按钮之间，供调用方放置选项列表等（如拉黑时的删除与否选项）。
    // 未传入即不渲染，既有无附加项的确认框布局不受影响
    option: (@Composable () -> Unit)? = null,
) {
    if (track == null) return
    DialogCard(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(titleRes),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(messageRes, track.title, track.artist),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            if (option != null) {
                Spacer(modifier = Modifier.height(8.dp))
                option()
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.widthIn(max = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    onClick = onDismiss,
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_rename_cancel),
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
                    onClick = { onConfirm(track) },
                ) {
                    Text(
                        text = stringResource(confirmRes),
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
