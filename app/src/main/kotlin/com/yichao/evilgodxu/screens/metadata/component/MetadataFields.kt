package com.yichao.evilgodxu.screens.metadata.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.screens.metadata.MetadataEditTarget
import com.yichao.evilgodxu.ui.component.menuEdgePositionProvider
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlin.math.roundToInt

// 字号层级：翻译行略小
private val LYRIC_ORIGINAL_FONT_SIZE = 15.sp
private val LYRIC_TRANSLATION_FONT_SIZE = 13.sp

// 全文编辑字号略小，一屏容纳更多行
private val LYRIC_WHOLE_FONT_SIZE = 13.sp

// 切换图标 20dp，不撑高头部
private val LYRIC_MODE_TOGGLE_ICON_SIZE = 20.dp

// 原文行最多 4 行，便于完整核对
private const val LYRIC_RAW_MAX_LINES = 4

// 空白翻译行保留点击高度
private val LYRIC_TRANSLATION_MIN_HEIGHT = 30.dp

// 组间 6dp，区分行分组
private val LYRIC_GROUP_GAP = 6.dp

// 菜单上移 32dp，避开手指
private val LYRIC_WHOLE_MENU_UP_SHIFT = 32.dp

/** 歌词分组：逐行编辑（原文 + 翻译）或全文编辑，可切换 */
@Composable
internal fun LyricsSection(
    lines: List<LyricLine>,
    unparsable: Boolean,
    editing: MetadataEditTarget?,
    lyricLineDraft: String?,
    wholeMode: Boolean,
    enabled: Boolean,
    translatingLine: Int?,
    onRawChange: (String) -> Unit,
    onTranslationChange: (Int, String) -> Unit,
    onTranslateLine: (Int) -> Unit,
    onCopyWhole: () -> Unit,
    onWholeModeToggle: () -> Unit,
    onStartEdit: (MetadataEditTarget) -> Unit,
    onEditDone: () -> Unit,
) {
    MetadataSection(title = stringResource(R.string.metadata_section_lyrics)) {
        if (lines.isEmpty()) {
            Text(
                text = stringResource(
                    if (unparsable) R.string.metadata_lyrics_invalid else R.string.metadata_lyrics_empty
                ),
                fontSize = 14.sp,
                color = if (unparsable) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = ROW_HORIZONTAL_PADDING, top = 4.dp, bottom = 4.dp),
            )
            return@MetadataSection
        }
        LyricsHeader(
            lineCount = lines.size,
            wholeMode = wholeMode,
            enabled = enabled,
            onToggle = onWholeModeToggle,
        )
        if (wholeMode) {
            if (editing == MetadataEditTarget.LyricsWhole) {
                EntryTextField(
                    value = lyricLineDraft.orEmpty(),
                    singleLine = false,
                    placeholder = "",
                    onValueChange = onRawChange,
                    onEditDone = onEditDone,
                    fontSize = LYRIC_WHOLE_FONT_SIZE,
                    maxLines = Int.MAX_VALUE,
                )
            } else {
                LyricsWholeCard(
                    lines = lines,
                    enabled = enabled,
                    onClick = { onStartEdit(MetadataEditTarget.LyricsWhole) },
                    onCopy = onCopyWhole,
                )
            }
            return@MetadataSection
        }
        lines.forEachIndexed { index, line ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (index == 0) 0.dp else LYRIC_GROUP_GAP),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (editing == MetadataEditTarget.LyricLineAt(index)) {
                    EntryTextField(
                        value = lyricLineDraft.orEmpty(),
                        singleLine = false,
                        placeholder = "",
                        onValueChange = onRawChange,
                        onEditDone = onEditDone,
                        fontSize = LYRIC_ORIGINAL_FONT_SIZE,
                        maxLines = LYRIC_RAW_MAX_LINES,
                    )
                } else {
                    LyricOriginalRow(
                        text = line.text,
                        enabled = enabled,
                        onClick = { onStartEdit(MetadataEditTarget.LyricLineAt(index)) },
                    )
                }
                if (editing == MetadataEditTarget.LyricTranslationAt(index)) {
                    EntryTextField(
                        value = line.translation.orEmpty(),
                        singleLine = true,
                        placeholder = stringResource(R.string.metadata_lyrics_translation_hint),
                        onValueChange = { onTranslationChange(index, it) },
                        onEditDone = onEditDone,
                        fontSize = LYRIC_TRANSLATION_FONT_SIZE,
                    )
                } else {
                    LyricTranslationRow(
                        translation = line.translation,
                        translating = translatingLine == index,
                        enabled = enabled,
                        // 补译中不弹菜单，接口限流
                        menuEnabled = translatingLine == null,
                        onClick = { onStartEdit(MetadataEditTarget.LyricTranslationAt(index)) },
                        onTranslate = { onTranslateLine(index) },
                    )
                }
            }
        }
    }
}

// 头部：行数概览 + 模式切换
@Composable
private fun LyricsHeader(
    lineCount: Int,
    wholeMode: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ROW_HORIZONTAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = pluralStringResource(R.plurals.metadata_lyrics_line_count, lineCount, lineCount),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 图标标识当前模式
        Icon(
            imageVector = if (wholeMode) AppIcons.ChecklistRtl else AppIcons.EditNote,
            contentDescription = stringResource(
                if (wholeMode) R.string.metadata_lyrics_whole_edit else R.string.metadata_lyrics_line_edit
            ),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onToggle)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .size(LYRIC_MODE_TOGGLE_ICON_SIZE),
        )
    }
}

// 全文卡片：点击整篇编辑，长按复制全文
@Composable
private fun LyricsWholeCard(
    lines: List<LyricLine>,
    enabled: Boolean,
    onClick: () -> Unit,
    onCopy: () -> Unit,
) {
    var menuVisible by remember { mutableStateOf(false) }
    // 菜单锚在按下点上方，避开手指与屏外
    var pressOffset by remember { mutableStateOf(Offset.Zero) }
    Box {
        MetadataBlockContainer(
            modifier = Modifier.pointerInput(Unit) {
                // 只记录按下位置，不消费事件
                awaitEachGesture {
                    pressOffset = awaitFirstDown(requireUnconsumed = false).position
                }
            },
            enabled = enabled,
            onClick = onClick,
            onLongClick = { menuVisible = true },
        ) {
            lines.forEach { line ->
                Text(
                    text = line.text,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = LYRIC_WHOLE_FONT_SIZE,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (menuVisible) {
            // 零尺寸锚点，只借坐标
            Box(
                Modifier.offset {
                    IntOffset(
                        pressOffset.x.roundToInt(),
                        pressOffset.y.roundToInt() - LYRIC_WHOLE_MENU_UP_SHIFT.roundToPx(),
                    )
                },
            ) {
                LyricsWholeMenu(
                    onCopy = {
                        menuVisible = false
                        onCopy()
                    },
                    onDismiss = { menuVisible = false },
                )
            }
        }
    }
}

// 原文行只展示文本
@Composable
private fun LyricOriginalRow(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    MetadataRowContainer(enabled = enabled, onClick = onClick) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = LYRIC_ORIGINAL_FONT_SIZE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// 翻译行：小字号，长按就地补译
@Composable
private fun LyricTranslationRow(
    translation: String?,
    translating: Boolean,
    enabled: Boolean,
    menuEnabled: Boolean,
    onClick: () -> Unit,
    onTranslate: () -> Unit,
) {
    var menuVisible by remember { mutableStateOf(false) }
    // Box 收窄菜单锚定范围到本行
    Box {
        MetadataRowContainer(
            modifier = Modifier.heightIn(min = LYRIC_TRANSLATION_MIN_HEIGHT),
            enabled = enabled,
            onClick = onClick,
            onLongClick = if (menuEnabled) ({ menuVisible = true }) else null,
        ) {
            Text(
                // 补译中显示占位文案
                text = if (translating) {
                    stringResource(R.string.metadata_lyrics_translating)
                } else {
                    translation.orEmpty()
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = LYRIC_TRANSLATION_FONT_SIZE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (menuVisible) {
            LyricTranslationMenu(
                onTranslate = {
                    menuVisible = false
                    onTranslate()
                },
                onDismiss = { menuVisible = false },
            )
        }
    }
}

// 翻译行菜单：仅自动补译
@Composable
private fun LyricTranslationMenu(
    onTranslate: () -> Unit,
    onDismiss: () -> Unit,
) {
    LyricsPopupMenu(onDismiss = onDismiss) {
        LyricMenuItem(
            text = stringResource(R.string.music_panel_auto_translate),
            onClick = onTranslate,
        )
    }
}

// 全文菜单：仅复制全文
@Composable
private fun LyricsWholeMenu(
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 向上展开，贴锚点上沿
    LyricsPopupMenu(atTop = true, onDismiss = onDismiss) {
        LyricMenuItem(
            text = stringResource(R.string.metadata_lyrics_copy_whole),
            onClick = onCopy,
        )
    }
}

// 长按菜单外观与定位；atTop 为向上展开
@Composable
private fun LyricsPopupMenu(
    onDismiss: () -> Unit,
    atTop: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Popup(
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
        onDismissRequest = onDismiss,
        popupPositionProvider = menuEdgePositionProvider(atTop = atTop),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 4.dp,
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.Center,
                content = content,
            )
        }
    }
}

// 菜单项：文字居中
@Composable
private fun LyricMenuItem(text: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.Transparent,
        onClick = onClick,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}
