package com.yichao.evilgodxu.screens.metadata.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import java.util.Locale

// 原文与翻译的字号层级：翻译行更小，从视觉上从属于原文行
private val LYRIC_ORIGINAL_FONT_SIZE = 15.sp
private val LYRIC_TRANSLATION_FONT_SIZE = 13.sp

// 原文行编辑框的最大行数：整行增强 LRC(含逐字标签)较长，允许折行以便完整核对
private const val LYRIC_RAW_MAX_LINES = 4

// 无翻译时的空白行仍保留可点击高度，点击即进入「添加翻译」
private val LYRIC_TRANSLATION_MIN_HEIGHT = 22.dp

/**
 * 歌词分组：每行歌词由「原文行 + 翻译行」两条独立条目组成，各自进入编辑态。
 *
 * 以行为编辑单位而非整篇文本：歌词行各有自己的时间戳，整篇改写会让用户在数百行文本里
 * 定位一行；逐行编辑则把「改哪一行」交给点击位置表达，行序与时间戳由行本身携带。
 *
 * 原文行内联编辑完整增强 LRC（行时间戳 + 逐字标签 + 文本），能力对齐首页歌词编辑模块；
 * 翻译行独立编辑，留空即清除，二者互不干扰。
 */
@Composable
internal fun LyricsSection(
    lines: List<LyricLine>,
    unparsable: Boolean,
    editing: MetadataEditTarget?,
    lyricLineDraft: String?,
    enabled: Boolean,
    onRawChange: (String) -> Unit,
    onTranslationChange: (Int, String) -> Unit,
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
                modifier = Modifier.padding(horizontal = ENTRY_HORIZONTAL_PADDING, vertical = 14.dp),
            )
            return@MetadataSection
        }
        LyricLineCount(lines.size)
        lines.forEachIndexed { index, line ->
            LyricLineEntry(
                line = line,
                editingRaw = editing == MetadataEditTarget.LyricLineAt(index),
                rawDraft = lyricLineDraft.orEmpty(),
                editingTranslation = editing == MetadataEditTarget.LyricTranslationAt(index),
                enabled = enabled,
                onRawChange = onRawChange,
                onTranslationChange = { onTranslationChange(index, it) },
                onStartRawEdit = { onStartEdit(MetadataEditTarget.LyricLineAt(index)) },
                onStartTranslationEdit = { onStartEdit(MetadataEditTarget.LyricTranslationAt(index)) },
                onEditDone = onEditDone,
                showDivider = index != lines.lastIndex,
            )
        }
    }
}

/**
 * 单行歌词条目：上方原文行，下方翻译行。
 *
 * 原文行展示态显示时间戳 + 文本，编辑态换成完整增强 LRC；
 * 翻译行始终占位（无翻译时是空白行），点击进入编辑视为添加，留空提交即清除。
 */
@Composable
private fun LyricLineEntry(
    line: LyricLine,
    editingRaw: Boolean,
    rawDraft: String,
    editingTranslation: Boolean,
    enabled: Boolean,
    onRawChange: (String) -> Unit,
    onTranslationChange: (String) -> Unit,
    onStartRawEdit: () -> Unit,
    onStartTranslationEdit: () -> Unit,
    onEditDone: () -> Unit,
    showDivider: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (editingRaw) {
            EntryTextField(
                value = rawDraft,
                enabled = enabled,
                singleLine = false,
                placeholder = "",
                onValueChange = onRawChange,
                onEditDone = onEditDone,
                fontSize = LYRIC_ORIGINAL_FONT_SIZE,
                maxLines = LYRIC_RAW_MAX_LINES,
            )
        } else {
            EntryDisplayRow(
                label = formatTimestamp(line.timeMs),
                value = line.text,
                enabled = enabled,
                onClick = onStartRawEdit,
            )
        }
        if (editingTranslation) {
            EntryTextField(
                value = line.translation.orEmpty(),
                enabled = enabled,
                singleLine = true,
                placeholder = stringResource(R.string.metadata_lyrics_translation_hint),
                onValueChange = onTranslationChange,
                onEditDone = onEditDone,
                fontSize = LYRIC_TRANSLATION_FONT_SIZE,
            )
        } else {
            LyricTranslationRow(
                translation = line.translation,
                enabled = enabled,
                onClick = onStartTranslationEdit,
            )
        }
        if (showDivider) EntryDivider()
    }
}

// 翻译展示行：原文行下方的次级信息，小字号；无翻译时保留空白行以便点击进入添加
@Composable
private fun LyricTranslationRow(
    translation: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ENTRY_HORIZONTAL_PADDING)
            .padding(bottom = 10.dp)
            .heightIn(min = LYRIC_TRANSLATION_MIN_HEIGHT),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (!translation.isNullOrBlank()) {
            Text(
                text = translation,
                fontSize = LYRIC_TRANSLATION_FONT_SIZE,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// 行数概览：让用户先知道整篇有多少行，再决定是否逐行查看
@Composable
private fun LyricLineCount(count: Int) {
    Text(
        text = pluralStringResource(R.plurals.metadata_lyrics_line_count, count, count),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ENTRY_HORIZONTAL_PADDING, top = 12.dp),
    )
}

// 时间戳展示：[mm:ss.xxx]，与 LRC 文本中的写法一致，便于用户核对行位置。
// 显式指定 Locale.ROOT：按默认区域格式化会在部分区域产出非 ASCII 数字，与 LRC 时间戳对不上
private fun formatTimestamp(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    val minutes = safe / 60_000
    val seconds = safe % 60_000 / 1000
    val millis = safe % 1000
    return String.format(Locale.ROOT, "[%02d:%02d.%03d]", minutes, seconds, millis)
}
