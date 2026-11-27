package com.yichao.evilgodxu.screens.metadata.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
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
import com.yichao.evilgodxu.ui.component.rememberSyncedTextFieldState

// 行水平内边距 10dp，与行底色对齐
internal val ROW_HORIZONTAL_PADDING = 10.dp

// 细边框：聚焦与否同宽
private val ENTRY_FIELD_SHAPE = RoundedCornerShape(8.dp)
private val ENTRY_FIELD_BORDER_WIDTH = 1.dp
private val ENTRY_FIELD_CONTENT_PADDING = 10.dp

// 值字号，编辑与展示一致
private val ENTRY_VALUE_FONT_SIZE = 15.sp

// 行/块圆角，同播放列表当前曲目
private val ROW_SHAPE = RoundedCornerShape(10.dp)

// 动作按钮点击区圆角
private val ACTION_SHAPE = RoundedCornerShape(8.dp)

// 动作按钮触控 28dp，图标 16dp
private val ACTION_TOUCH_SIZE = 28.dp
private val ACTION_ICON_SIZE = 16.dp

/** 表单分组：标题 + 字段行，不套外层卡片 */
@Composable
internal fun MetadataSection(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            action?.invoke()
        }
        content()
    }
}

/** 分组标题动作按钮：执行中显示进度并禁用 */
@Composable
internal fun MetadataSectionAction(
    icon: ImageVector,
    contentDescription: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(ACTION_TOUCH_SIZE)
            .clip(ACTION_SHAPE)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ACTION_ICON_SIZE),
            )
        }
    }
}

/** 字段行容器：弱化底色，可选点击与长按 */
@Composable
internal fun MetadataRowContainer(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val base = modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), ROW_SHAPE)
    val interactive = when {
        onLongClick != null -> base.combinedClickable(
            enabled = enabled,
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
        onClick != null -> base.clickable(enabled = enabled, onClick = onClick)
        else -> base
    }
    Row(
        modifier = interactive.padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 成块内容容器：与字段行同底色，供整篇歌词复用 */
@Composable
internal fun MetadataBlockContainer(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), ROW_SHAPE)
    val interactive = when {
        onLongClick != null -> base.combinedClickable(
            enabled = enabled,
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick,
        )
        onClick != null -> base.clickable(enabled = enabled, onClick = onClick)
        else -> base
    }
    Column(
        modifier = interactive.padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/** 可编辑条目：展示态点击后换输入框，空值用弱化文案 */
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

// 只读展示行：左标签右取值
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

/** 输入框：取焦后按「完成」/点击别处退出，1dp 边框 */
@Composable
internal fun EntryTextField(
    value: String,
    singleLine: Boolean,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onEditDone: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = ENTRY_VALUE_FONT_SIZE,
    maxLines: Int = 1,
) {
    val focusRequester = remember { FocusRequester() }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val textState = rememberSyncedTextFieldState(value, onValueChange)
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        runCatching { bringIntoViewRequester.bringIntoView() }
    }
    // 键盘可见后再滚动一次，避免被下缘截断
    LaunchedEffect(imeVisible) {
        if (imeVisible) runCatching { bringIntoViewRequester.bringIntoView() }
    }
    BasicTextField(
        state = textState,
        textStyle = TextStyle(fontSize = fontSize, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
        onKeyboardAction = KeyboardActionHandler { onEditDone() },
        lineLimits = if (singleLine) {
            TextFieldLineLimits.SingleLine
        } else {
            TextFieldLineLimits.MultiLine(maxHeightInLines = maxLines)
        },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .bringIntoViewRequester(bringIntoViewRequester)
            .border(
                border = BorderStroke(ENTRY_FIELD_BORDER_WIDTH, MaterialTheme.colorScheme.outline),
                shape = ENTRY_FIELD_SHAPE,
            )
            .padding(
                horizontal = ENTRY_FIELD_CONTENT_PADDING,
                vertical = ENTRY_FIELD_CONTENT_PADDING,
            ),
        decorator = TextFieldDecorator { innerTextField ->
            Box {
                if (textState.text.isEmpty() && placeholder.isNotEmpty()) {
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

// 顶部说明：提示自动保存
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

// 保存提示：固定单行高度，避免表单跳动
@Composable
internal fun MetadataStatusText(
    message: String?,
    messageIsError: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = message.orEmpty(),
        fontSize = 12.sp,
        color = when {
            message == null -> Color.Transparent
            messageIsError -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        fontWeight = if (message != null && !messageIsError) FontWeight.Medium else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(top = 12.dp),
    )
}
