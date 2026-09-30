package com.yichao.evilgodxu.screens.metadata.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.screens.metadata.MetadataEditTarget

// 原文与翻译的字号层级：翻译行更小，从视觉上从属于原文行
private val LYRIC_ORIGINAL_FONT_SIZE = 15.sp
private val LYRIC_TRANSLATION_FONT_SIZE = 13.sp

// 原文行编辑框的最大行数：整行增强 LRC(含逐字标签)较长，允许折行以便完整核对
private const val LYRIC_RAW_MAX_LINES = 4

// 无翻译时的空白行仍保留可点击高度，点击即进入「添加翻译」
private val LYRIC_TRANSLATION_MIN_HEIGHT = 30.dp

// 同一行歌词的原文行与翻译行紧邻，行与行之间留出更大间隔以形成分组
private val LYRIC_GROUP_GAP = 6.dp

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
                modifier = Modifier.padding(start = ROW_HORIZONTAL_PADDING, top = 4.dp, bottom = 4.dp),
            )
            return@MetadataSection
        }
        LyricLineCount(lines.size)
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
                        enabled = enabled,
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
                        enabled = enabled,
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

// 行数概览：让用户先知道整篇有多少行，再决定是否逐行查看
@Composable
private fun LyricLineCount(count: Int) {
    Text(
        text = pluralStringResource(R.plurals.metadata_lyrics_line_count, count, count),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = ROW_HORIZONTAL_PADDING),
    )
}
