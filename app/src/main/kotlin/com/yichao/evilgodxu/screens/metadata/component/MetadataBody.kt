package com.yichao.evilgodxu.screens.metadata.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.yichao.evilgodxu.ui.component.section.GroupCard

// 元数据表单分组：分组标题 + 卡片内容，字段纵向排列填满卡片宽度
@Composable
internal fun MetadataSection(
    title: String,
    content: @Composable () -> Unit,
) {
    GroupCard(title = title) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) { content() }
    }
}

/**
 * 元数据文本字段：单行或多行输入。
 *
 * 歌词用多行：整篇 LRC 文本可达数百行，单行输入框会把它挤成一条不可读的长行。
 */
@Composable
internal fun MetadataTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 14.sp) },
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    )
}

// 字段说明：说明该字段的取值约定或落点，避免用户按界面印象误判
@Composable
internal fun MetadataFieldHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 2.dp, bottom = 4.dp),
    )
}

// 保存栏：整宽按钮 + 结果提示，提示文案由保存结果驱动
@Composable
internal fun MetadataSaveBar(
    saving: Boolean,
    message: String?,
    messageIsError: Boolean,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = if (saving) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary,
            // 保存进行中不接受重复提交：各容器的标签重写都要搬运整段音频
            onClick = { if (!saving) onSave() },
        ) {
            Text(
                text = stringResource(R.string.metadata_save),
                color = if (saving) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            )
        }
        if (message != null) {
            Text(
                text = message,
                color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
