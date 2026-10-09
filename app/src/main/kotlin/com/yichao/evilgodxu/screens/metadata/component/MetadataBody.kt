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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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

// 行内容与文本对齐用的水平内边距：行底色自带 10dp 内缩，文本位置与此一致
internal val ROW_HORIZONTAL_PADDING = 10.dp

// 输入框细边框：聚焦与否都取同一宽度，避免默认描边在聚焦时变粗
private val ENTRY_FIELD_SHAPE = RoundedCornerShape(8.dp)
private val ENTRY_FIELD_BORDER_WIDTH = 1.dp
private val ENTRY_FIELD_CONTENT_PADDING = 10.dp

// 值文本默认字号：编辑态与展示态保持一致
private val ENTRY_VALUE_FONT_SIZE = 15.sp

// 行/块的圆角形状。底色在各自的容器里取，与播放列表「当前曲目」行同款（见下）
private val ROW_SHAPE = RoundedCornerShape(10.dp)

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
 * 字段行容器：整行弱化底色 + 行内边距，可选整行点击与长按。
 *
 * 底色取「播放列表当前曲目」同款——主题色淡染，页内条目与列表选中项保持同一套视觉语言。
 * 单独抽出使展示行与输入控件在各分组里保持同一外观与内缩。
 *
 * @param onLongClick 长按入口（如翻译行的操作菜单）；为 null 时该行只响应点击
 */
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

/**
 * 成块内容的容器：与字段行同一套底色，供整篇歌词卡片等纵向成块内容复用。
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
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), ROW_SHAPE)
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
 * @param enabled 仅约束展示态的点击入口；编辑态输入框不受此开关影响（见 [EntryTextField]）
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
 * 条目输入框：进入编辑态即取焦并弹出键盘，按「完成」键或点击其它区域退出编辑态。
 *
 * 不按失焦退出编辑态：取焦是异步的，首次组合时 Compose 会先派发一次未聚焦回调，
 * 而切换条目时上一轮尚未派发完的失焦回调又会落到新节点上 —— 两者都会把刚打开的输入框
 * 立刻关掉，且难以可靠区分。编辑态的收起改由「完成」键与点击其他条目、点击空白区承担，
 * 这些都是用户的明确意图，不依赖焦点事件时序。
 *
 * 键盘收起不结束编辑态：系统返回键收起键盘后输入框保持编辑态与焦点，用户得以继续核对
 * 刚输入的内容，确认无误再退出编辑。把「收起键盘」当作编辑完成，会让用户来不及复核。
 *
 * 输入框不提供禁用开关，任何时刻都可编辑：禁用取焦中的输入框会使该节点变为不可聚焦，
 * 系统随即清掉焦点并结束输入会话（Compose 以 Modifier.focusable(enabled) 承载输入框的焦点），
 * 键盘随之收起且不会自行恢复 —— 而自动落盘恰好发生在输入停顿之后，正是「清空字段准备
 * 重新输入」的间隙。落盘期间的新输入由状态持有者合并进下一批写入，无需在界面层拦截。
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
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        runCatching { bringIntoViewRequester.bringIntoView() }
    }
    // 键盘弹出会压缩可用高度，取焦那一刻的高度还不是最终高度，故在键盘可见后再请求一次，
    // 使输入框整体滚到键盘之上而不是被下缘截断
    LaunchedEffect(imeVisible) {
        if (imeVisible) runCatching { bringIntoViewRequester.bringIntoView() }
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else maxLines,
        textStyle = TextStyle(fontSize = fontSize, color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onEditDone() }),
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

// 保存结果提示：自动保存后短暂展示，成功与失败用不同颜色区分。
//
// 无提示时渲染空文案占住这一行：提示在「输入清空提示、落盘重新提示」之间反复出现与消失，
// 若它的高度跟着变化，下方整片表单（含用户正在输入的输入框）会随每次落盘上下跳动。
// 限制为单行同样是为了固定高度 —— 失败文案较长，换行会让提示区从一行变两行
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
