package com.yichao.evilgodxu.ui.component.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.DialogCard

/**
 * 逐字对齐进度对话框。
 *
 * 对齐耗时随歌词行数增长，故对话框收起后任务继续在后台执行；这里的收起只隐藏展示，
 * 因此按钮文案是「后台继续」而不是「取消」。
 */
@Composable
internal fun LyricsAlignDialog(
    visible: Boolean,
    progress: Pair<Int, Int>?,
    onCollapse: () -> Unit,
) {
    if (!visible) return
    DialogCard(onDismiss = onCollapse) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.music_panel_word_align_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            // 进度未知（尚在解码首行）时按不确定态展示，避免出现长时间不动的 0%
            val fraction = progress?.let { if (it.second > 0) it.first / it.second.toFloat() else 0f }
            if (fraction == null) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp)
            } else {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = progress?.let {
                    stringResource(R.string.music_panel_word_align_progress, it.first, it.second)
                } ?: stringResource(R.string.music_panel_word_align_decoding),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                onClick = onCollapse,
            ) {
                Text(
                    text = stringResource(R.string.music_panel_word_align_background),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
        }
    }
}
