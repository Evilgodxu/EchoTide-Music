package com.yichao.evilgodxu.screens.metadata.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.screens.metadata.MetadataEditTarget
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
 * 能力对齐首页歌词编辑模块；翻译行独立编辑，留空即清除，二者互不干扰。
 *
 * 全文编辑：整篇以增强 LRC 文本一次性编辑，适合批量改写或整体替换。进入全文模式只切换展示形态，
 * 默认呈现整篇歌词卡片；点击卡片才建立编辑态并弹出键盘，点击别处即回到卡片展示（键盘收起不结束
 * 编辑态，用户可继续核对），不会退回逐行 —— 模式只由切换按钮结束。
 */
@Composable
internal fun LyricsSection(
    lines: List<LyricLine>,
    unparsable: Boolean,
    editing: MetadataEditTarget?,
    lyricLineDraft: String?,
    wholeMode: Boolean,
    enabled: Boolean,
    onRawChange: (String) -> Unit,
    onTranslationChange: (Int, String) -> Unit,
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
                        enabled = enabled,
                        onClick = { onStartEdit(MetadataEditTarget.LyricTranslationAt(index)) },
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

// 翻译展示行：原文行下方的次级信息，小字号；无翻译时保留空白行以便点击进入添加
@Composable
private fun LyricTranslationRow(
    translation: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    MetadataRowContainer(
        modifier = Modifier.heightIn(min = LYRIC_TRANSLATION_MIN_HEIGHT),
        enabled = enabled,
        onClick = onClick,
    ) {
        Text(
            text = translation.orEmpty(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = LYRIC_TRANSLATION_FONT_SIZE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
