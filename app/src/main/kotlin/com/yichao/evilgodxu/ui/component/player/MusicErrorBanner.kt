package com.yichao.evilgodxu.ui.component.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 音乐面板通用错误横幅：通栏红底样式，显示约 2 秒后自动消失 */
@Composable
internal fun MusicErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(message) {
        delay(2000)
        onDismiss()
    }
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        fontSize = 11.sp,
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
