package com.yichao.evilgodxu.screens.metadata.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.ui.icons.AppIcons

// 歌词输入框最少展示行数：给整篇 LRC 一个可读的初始高度
private const val LYRICS_MIN_LINES = 6

/**
 * 歌词分组：折叠时只占一行（概览 + 展开箭头），展开后给出多行输入框。
 *
 * 折叠是默认态：整篇 LRC 可达数百行，常驻展开会把保存入口推到很远处。
 * 概览按解析出的歌词行统计而非文本行数 —— 用户关心的是「有没有歌词」，
 * 而不是原始文本里有多少个换行；解析不出行时也顺带暴露了格式有问题。
 */
@Composable
internal fun LyricsSection(
    lyrics: String,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onLyricsChange: (String) -> Unit,
) {
    val lineCount = remember(lyrics) {
        if (lyrics.isBlank()) 0 else MusicMetadataCache.parseLyricsText(lyrics).size
    }
    MetadataSection(title = stringResource(R.string.metadata_section_lyrics)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onExpandedChange(!expanded) }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (lineCount > 0) {
                    pluralStringResource(R.plurals.metadata_lyrics_line_count, lineCount, lineCount)
                } else {
                    stringResource(R.string.metadata_lyrics_empty)
                },
                fontSize = 14.sp,
                color = if (lineCount > 0) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) AppIcons.KeyboardArrowUp else AppIcons.ArrowDropDown,
                contentDescription = stringResource(
                    if (expanded) R.string.metadata_lyrics_collapse else R.string.metadata_lyrics_expand
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            MetadataTextField(
                label = stringResource(R.string.metadata_field_lyrics),
                value = lyrics,
                onValueChange = onLyricsChange,
                enabled = enabled,
                singleLine = false,
                minLines = LYRICS_MIN_LINES,
            )
            MetadataFieldHint(stringResource(R.string.metadata_field_lyrics_hint))
        }
    }
}
