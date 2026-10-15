package com.yichao.evilgodxu.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.yichao.evilgodxu.ui.component.DialogCard

@Composable
internal fun RenameDialog(
    visible: Boolean,
    isTitle: Boolean,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    if (visible) {
        DialogCard(onDismiss = onCancel) {
            RenameContent(
                initialValue = initialValue,
                isTitle = isTitle,
                onConfirm = onConfirm,
                onCancel = onCancel,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

// 重命名共享主体：标题 + 输入框 + 确认/取消，供全屏蒙层与对话框复用
@Composable
private fun RenameContent(
    initialValue: String,
    isTitle: Boolean,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (isTitle) stringResource(R.string.music_panel_rename_title)
                   else stringResource(R.string.music_panel_rename_artist),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
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
                    text = stringResource(R.string.music_panel_rename_cancel),
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
                onClick = {
                    val trimmed = value.trim()
                    if (trimmed.isNotEmpty()) onConfirm(trimmed)
                }
            ) {
                Text(
                    text = stringResource(R.string.music_panel_rename_confirm),
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
