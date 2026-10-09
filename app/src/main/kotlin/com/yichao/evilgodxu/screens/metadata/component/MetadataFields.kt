package com.yichao.evilgodxu.screens.metadata.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.screens.metadata.MetadataEditTarget
import com.yichao.evilgodxu.ui.component.menuEdgePositionProvider
import com.yichao.evilgodxu.ui.icons.AppIcons

// 原文与翻译的字号层级：翻译行更小，从视觉上从属于原文行
private val LYRIC_ORIGINAL_FONT_SIZE = 15.sp
private val LYRIC_TRANSLATION_FONT_SIZE = 13.sp

// 全文编辑框的字号：整篇文本较长，取行内字号略小以便一屏容纳更多行
private val LYRIC_WHOLE_FONT_SIZE = 13.sp

// 模式切换图标：与头部 12sp 的行数概览同一视觉量级，不撑高头部
private val LYRIC_MODE_TOGGLE_ICON_SIZE = 20.dp

// 单行原文编辑框的最大行数：整行增强 LRC(含逐字标签)较长，允许折行以便完整核对
private const val LYRIC_RAW_MAX_LINES = 4

// 无翻译时的空白行仍保留可点击高度，点击即进入「添加翻译」
private val LYRIC_TRANSLATION_MIN_HEIGHT = 30.dp

// 同一行歌词的原文行与翻译行紧邻，行与行之间留出更大间隔以形成分组
private val LYRIC_GROUP_GAP = 6.dp

/**
 * 歌词分组：默认逐行编辑，可切换到全文编辑。
 *
 * 逐行编辑：每行由「原文行 + 翻译行」两条独立条目组成，各自进入编辑态。以行为编辑单位而非
 * 整篇文本，是因为歌词行各有自己的时间戳，整篇改写会让用户在数百行文本里定位一行；逐行编辑
 * 把「改哪一行」交给点击位置表达。原文行内联编辑完整增强 LRC（行时间戳 + 逐字标签 + 文本），
 * 能力对齐首页歌词编辑模块；翻译行独立编辑，留空即清除，二者互不干扰。翻译行另增长按菜单
 * 提供自动补译，把「补哪一行」同样交给长按位置表达。
 *
 * 全文编辑：整篇以增强 LRC 文本一次性编辑，适合批量改写或整体替换。进入全文模式只切换展示形态，
 * 默认呈现整篇歌词卡片；点击卡片才建立编辑态并弹出键盘，点击别处即回到卡片展示（键盘收起不结束
 * 编辑态，用户可继续核对），不会退回逐行 —— 模式只由切换按钮结束。
 *
 * @param translatingLine 正在补译的行下标，非空时该行显示补译中占位，且不再弹出补译菜单
 */
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
                        // 补译进行中不再弹出菜单：翻译接口按 IP 限流，不允许并发发起
                        menuEnabled = translatingLine == null,
                        onClick = { onStartEdit(MetadataEditTarget.LyricTranslationAt(index)) },
                        onTranslate = { onTranslateLine(index) },
                    )
                }
            }
        }
    }
}

// 歌词区头部：左侧行数概览，右侧全文/逐行编辑切换按钮
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
        // 切换按钮用图标标识当前模式：逐行编辑是逐条改字，全文编辑是整篇列表核对
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

// 全文卡片：整篇歌词装在同一张卡里逐行呈现，点击后进入整篇文本编辑。
// 默认只做展示，避免整篇区一直停留在编辑态的观感
@Composable
private fun LyricsWholeCard(
    lines: List<LyricLine>,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    MetadataBlockContainer(enabled = enabled, onClick = onClick) {
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
}

// 原文行展示：只呈现歌词文本，时间戳只在进入编辑态后的原始文本里出现
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

// 翻译展示行：原文行下方的次级信息，小字号；无翻译时保留空白行以便点击进入添加。
// 长按可在本行就地发起自动补译，把尚无译文的行补齐
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
    // 菜单以本行为锚点：套一层 Box 使浮层的锚定范围收在这一行上，而不是整个歌词分组
    Box {
        MetadataRowContainer(
            modifier = Modifier.heightIn(min = LYRIC_TRANSLATION_MIN_HEIGHT),
            enabled = enabled,
            onClick = onClick,
            onLongClick = if (menuEnabled) ({ menuVisible = true }) else null,
        ) {
            Text(
                // 补译期间以占位文案顶上译文位置：请求要等接口返回，长按后若界面毫无变化
                // 会被误认为没生效
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

// 翻译行长按菜单：只有「自动补译」一项 —— 长按已把目标行限定在这一行上，
// 菜单无需二级结构，也不必再给整篇补译留入口（首页长按菜单已有）。
// 外观与定位沿用本页封面菜单与首页歌词菜单的同一套浮层样式
@Composable
private fun LyricTranslationMenu(
    onTranslate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
        onDismissRequest = onDismiss,
        popupPositionProvider = menuEdgePositionProvider(atTop = false),
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
            ) {
                LyricMenuItem(
                    text = stringResource(R.string.music_panel_auto_translate),
                    onClick = onTranslate,
                )
            }
        }
    }
}

// 菜单项：文字居中，与同页封面菜单项同一外观
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
