package com.yichao.evilgodxu.screens.metadata.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.section.GroupCard

// 条目展示态与编辑态共用的水平内边距：切换时文字位置不跳动。
// Dp 非编译期常量，只能用 val
internal val ENTRY_HORIZONTAL_PADDING = 16.dp

// 输入框细边框：聚焦与否都取同一宽度，避免默认描边在聚焦时变粗
private val ENTRY_FIELD_SHAPE = RoundedCornerShape(8.dp)
private val ENTRY_FIELD_BORDER_WIDTH = 1.dp
private val ENTRY_FIELD_CONTENT_PADDING = 12.dp

// 值文本默认字号：编辑态与展示态保持一致
private val ENTRY_VALUE_FONT_SIZE = 15.sp

/**
 * 元数据表单分组：分组标题 + 卡片内容。条目自行负责行内边距与分隔线
 */
@Composable
internal fun MetadataSection(
    title: String,
    content: @Composable () -> Unit,
) {
    GroupCard(title = title) { content() }
}

/**
 * 可编辑条目：展示态为「标签 + 值」两行，点击后整行换成输入框。
 *
 * 值以单行省略展示：条目宽度有限，长文本折行会把相邻条目挤出视野，
 * 编辑时再由输入框完整呈现。空值单独用弱化文案标注，避免与「标签本身」混淆。
 *
 * @param editing 该条目是否处于编辑态，由调用方保证同一时刻只有一条为真
 * @param onStartEdit 点击展示态时进入编辑态的请求
 * @param onEditDone 输入框按「完成」键后结束编辑态
 */
@Composable
internal fun MetadataEntry(
    label: String,
    value: String,
    editing: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onEditDone: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.metadata_field_empty),
    singleLine: Boolean = true,
    showDivider: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (editing) {
            EntryTextField(
                value = value,
                enabled = enabled,
                singleLine = singleLine,
                placeholder = placeholder,
                onValueChange = onValueChange,
                onEditDone = onEditDone,
            )
        } else {
            EntryDisplayRow(
                label = label,
                value = value,
                enabled = enabled,
                placeholder = placeholder,
                onClick = onStartEdit,
            )
        }
        if (showDivider) EntryDivider()
    }
}

/**
 * 条目的只读展示行：标签 + 值两行，点击进入编辑态。
 *
 * 抽出来供基本信息字段与歌词原文行共用，避免两处各写一套展示样式。
 */
@Composable
internal fun EntryDisplayRow(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.metadata_field_empty),
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ENTRY_HORIZONTAL_PADDING, vertical = 12.dp),
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value.ifBlank { placeholder },
            fontSize = ENTRY_VALUE_FONT_SIZE,
            color = if (value.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * 条目输入框：进入编辑态即取焦并弹出键盘，按「完成」键收起。
 *
 * 不按失焦退出编辑态：取焦是异步的，首次组合时 Compose 会先派发一次未聚焦回调，
 * 而切换条目时上一轮尚未派发完的失焦回调又会落到新节点上 —— 两者都会把刚打开的输入框
 * 立刻关掉，且难以可靠区分。编辑态的收起改由「完成」键与点击其他条目承担，
 * 这两种操作都是用户的明确意图，不依赖焦点事件时序。
 *
 * 边框用 BasicTextField 自绘 1dp 描边：OutlinedTextField 的聚焦描边固定为 2dp，
 * 无法通过参数调细，与「细边框」的诉求不符。
 *
 * @param singleLine 单行条目以「完成」键收尾；多行条目保留换行键，由点击其他条目退出
 * @param maxLines singleLine 为 false 时的最大行数
 */
@Composable
internal fun EntryTextField(
    value: String,
    enabled: Boolean,
    singleLine: Boolean,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onEditDone: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = ENTRY_VALUE_FONT_SIZE,
    maxLines: Int = 1,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    val textColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else maxLines,
        textStyle = TextStyle(fontSize = fontSize, color = textColor),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onEditDone() }),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ENTRY_HORIZONTAL_PADDING, vertical = 8.dp)
            .focusRequester(focusRequester)
            .border(
                border = BorderStroke(ENTRY_FIELD_BORDER_WIDTH, MaterialTheme.colorScheme.outline),
                shape = ENTRY_FIELD_SHAPE,
            )
            .padding(horizontal = ENTRY_FIELD_CONTENT_PADDING, vertical = ENTRY_FIELD_CONTENT_PADDING),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = fontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                innerTextField()
            }
        },
    )
}

// 条目分隔线：紧贴上一行内容，左侧与文字对齐，比给每行加卡片更省纵向空间
@Composable
internal fun EntryDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ENTRY_HORIZONTAL_PADDING)
            .height(1.dp)
            .background(dividerColor()),
    )
}

@Composable
private fun dividerColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)

// 表单顶部说明：提示条目的编辑与保存方式，避免用户寻找不存在的保存按钮
@Composable
internal fun MetadataFormHint(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
    )
}

// 保存结果提示：自动保存后短暂展示，成功与失败用不同颜色区分
@Composable
internal fun MetadataStatusText(
    message: String?,
    messageIsError: Boolean,
    modifier: Modifier = Modifier,
) {
    if (message == null) return
    Text(
        text = message,
        fontSize = 12.sp,
        color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        fontWeight = if (messageIsError) FontWeight.Normal else FontWeight.Medium,
        modifier = modifier.padding(start = 4.dp, top = 12.dp),
    )
}
