package com.yichao.evilgodxu.screens.metadata.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.LyricLine
import java.util.Locale

/**
 * 歌词分组：每行歌词是一条独立条目，点击后只改该行。
 *
 * 以行为编辑单位而非整篇文本：歌词行各有自己的时间戳，整篇改写会让用户在数百行文本里
 * 定位一行；逐行编辑则把「改哪一行」交给点击位置表达，行序与时间戳由行本身携带。
 *
 * 行首展示时间戳：它是行与行之间唯一的结构差异，也是用户判断「改对了哪一行」的依据。
 */
@Composable
internal fun LyricsSection(
    lines: List<LyricLine>,
    unparsable: Boolean,
    editingIndex: Int?,
    enabled: Boolean,
    onLineChange: (Int, String) -> Unit,
    onStartEdit: (Int) -> Unit,
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
            MetadataEntry(
                label = formatTimestamp(line.timeMs),
                value = line.text,
                editing = editingIndex == index,
                enabled = enabled,
                onValueChange = { onLineChange(index, it) },
                onStartEdit = { onStartEdit(index) },
                onEditDone = onEditDone,
                showDivider = index != lines.lastIndex,
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
