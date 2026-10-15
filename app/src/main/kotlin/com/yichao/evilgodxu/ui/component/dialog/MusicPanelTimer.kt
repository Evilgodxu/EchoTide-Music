package com.yichao.evilgodxu.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.ui.component.DialogCard

@Composable
internal fun TimerDialog(
    visible: Boolean,
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    if (visible) {
        DialogCard(onDismiss = onCancel) {
            TimerPanelContent(
                minutes = minutes,
                onMinutesChange = onMinutesChange,
                onConfirm = onConfirm,
                onCancel = onCancel,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

// 定时面板主体：标题 + 加减分钟 + 确认/取消
@Composable
private fun TimerPanelContent(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.music_panel_timer_title),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TimerAdjustButton(
                text = "−",
                onClick = { onMinutesChange(minutes - MusicPlaybackState.TIMER_STEP_MINUTES) }
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = minutes.toString(),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.music_panel_timer_minutes),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
            TimerAdjustButton(
                text = "+",
                onClick = { onMinutesChange(minutes + MusicPlaybackState.TIMER_STEP_MINUTES) }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.widthIn(max = 200.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                onClick = onCancel
            ) {
                Text(
                    text = stringResource(R.string.music_panel_timer_cancel),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary,
                onClick = onConfirm
            ) {
                Text(
                    text = stringResource(R.string.music_panel_timer_confirm),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
internal fun TimerAdjustButton(text: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        modifier = Modifier.size(40.dp),
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
