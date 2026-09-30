package com.yichao.evilgodxu.screens.metadata.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R

// 行内容与文本对齐用的水平内边距：行底色自带 10dp 内缩，文本位置与此一致
internal val ROW_HORIZONTAL_PADDING = 10.dp

// 输入框细边框：聚焦与否都取同一宽度，避免默认描边在聚焦时变粗
private val ENTRY_FIELD_SHAPE = RoundedCornerShape(8.dp)
private val ENTRY_FIELD_BORDER_WIDTH = 1.dp
private val ENTRY_FIELD_CONTENT_PADDING = 10.dp

// 值文本默认字号：编辑态与展示态保持一致
private val ENTRY_VALUE_FONT_SIZE = 15.sp

// 行的弱化圆角底色：与音频信息弹窗一致，用淡色块区分单行而不引入外层大卡片
private val ROW_SHAPE = RoundedCornerShape(10.dp)
private const val ROW_BACKGROUND_ALPHA = 0.45f

/**
 * 表单分组：小标题 + 字段行，不套外层卡片。
 *
 * 展示方式与音频信息弹窗一致 —— 分组只以标题区分，行自身带弱化底色，
 * 避免大卡片把整组内容再包一层背景。
 */
@Composable
internal fun MetadataSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

/**
 * 字段行容器：弱化圆角底色 + 行内边距，可选整行点击。
 *
 * 单独抽出使展示行与输入控件在各分组里保持同一外观与内缩。
 */
@Composable
internal fun MetadataRowContainer(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val base = modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ROW_BACKGROUND_ALPHA), ROW_SHAPE)
    val interactive = if (onClick != null) base.clickable(enabled = enabled, onClick = onClick) else base
    Row(
        modifier = interactive.padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 成块内容的容器：与字段行同一套弱化底色，供整篇歌词卡片等纵向成块内容复用。
 */
@Composable
internal fun MetadataBlockContainer(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ROW_BACKGROUND_ALPHA), ROW_SHAPE)
    val interactive = if (onClick != null) base.clickable(enabled = enabled, onClick = onClick) else base
    Column(
        modifier = interactive.padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/**
 * 可编辑条目：展示态为「标签 + 值」一行（左标签右取值），点击后换成输入框。
 *
 * 空值用弱化文案标注，避免与「标签本身」混淆。
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
) {
    if (editing) {
        EntryTextField(
            value = value,
            enabled = enabled,
            singleLine = singleLine,
            placeholder = placeholder,
            onValueChange = onValueChange,
            onEditDone = onEditDone,
            modifier = modifier,
        )
    } else {
        EntryDisplayRow(
            label = label,
            value = value,
            enabled = enabled,
            onClick = onStartEdit,
            modifier = modifier,
            placeholder = placeholder,
        )
    }
}

// 只读展示行：左侧字段名弱化，右侧取值，与音频信息弹窗的字段行同构
@Composable
internal fun EntryDisplayRow(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.metadata_field_empty),
) {
    MetadataRowContainer(modifier = modifier, enabled = enabled, onClick = onClick) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.weight(0.9f),
        )
        Text(
            text = value.ifBlank { placeholder },
            color = if (value.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontSize = ENTRY_VALUE_FONT_SIZE,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1.3f)
                .padding(start = 8.dp),
        )
    }
}

/**
 * 条目输入框：进入编辑态即取焦并弹出键盘，按「完成」键收起。
 *
 * 不按失焦退出编辑态：取焦是异步的，首次组合时 Compose 会先派发一次未聚焦回调，
 * 而切换条目时上一轮尚未派发完的失焦回调又会落到新节点上 —— 两者都会把刚打开的输入框
 * 立刻关掉，且难以可靠区分。编辑态的收起改由「完成」键、点击其他条目与键盘收起承担，
 * 这些都是用户的明确意图，不依赖焦点事件时序。
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
            .focusRequester(focusRequester)
            .border(
                border = BorderStroke(ENTRY_FIELD_BORDER_WIDTH, MaterialTheme.colorScheme.outline),
                shape = ENTRY_FIELD_SHAPE,
            )
            .padding(
                horizontal = ENTRY_FIELD_CONTENT_PADDING,
                vertical = ENTRY_FIELD_CONTENT_PADDING,
            ),
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

// 表单顶部说明：提示条目的编辑与保存方式，避免用户寻找不存在的保存按钮
@Composable
internal fun MetadataFormHint(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
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
        modifier = modifier.padding(top = 12.dp),
    )
}
