package com.yichao.evilgodxu.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.yichao.evilgodxu.theme.AppAlpha
import com.yichao.evilgodxu.theme.AppShape

// 统一卡片对话框容器
@Composable
internal fun DialogCard(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = AppShape.Container,
            color = MaterialTheme.colorScheme.surface.copy(alpha = AppAlpha.FloatingSurface),
            modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            content()
        }
    }
}
