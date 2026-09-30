package com.yichao.evilgodxu.screens.settings.component.dialog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.AppDialog

// 代理音源导入输入对话框：链接单行、文本多行；底部取消/确认按钮经 buttons 槽传入
@Composable
fun ProxySourceInputDialog(
    title: String,
    placeholder: String,
    singleLine: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val canConfirm = value.isNotBlank()

    AppDialog(
        onDismiss = onDismiss,
        title = title,
        scrollable = false,
        buttons = {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                onClick = onDismiss,
            ) {
                Text(
                    text = stringResource(R.string.settings_proxy_source_cancel),
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
                color = if (canConfirm) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                enabled = canConfirm,
                onClick = { onConfirm(value.trim()) },
            ) {
                Text(
                    text = stringResource(R.string.settings_proxy_source_confirm),
                    color = if (canConfirm) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = { Text(placeholder) },
            singleLine = singleLine,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp),
        )
    }
}
