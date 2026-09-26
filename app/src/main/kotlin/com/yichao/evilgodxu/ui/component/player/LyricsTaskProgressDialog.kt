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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.ui.component.DialogCard

/**
 * 歌词后台任务的进度对话框，供逐字对齐与自动补译共用。
 *
 * 这类任务耗时随歌词行数增长，收起对话框只隐藏展示、不中断任务，故按钮语义是「后台继续」而不是「取消」。
 *
 * @param fraction 进度比例；传 null 表示进度未知，按不确定态展示
 */
@Composable
internal fun LyricsTaskProgressDialog(
    visible: Boolean,
    title: String,
    statusText: String,
    fraction: Float?,
    actionText: String,
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
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(16.dp))
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
                text = statusText,
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
                    text = actionText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
        }
    }
}
