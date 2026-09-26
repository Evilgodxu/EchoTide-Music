package com.yichao.evilgodxu.ui.component.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R

/**
 * 逐字对齐进度对话框。
 *
 * 对齐耗时随歌词行数增长，故对话框收起后任务继续在后台执行；文案由 [LyricsTaskProgressDialog] 统一呈现。
 */
@Composable
internal fun LyricsAlignDialog(
    visible: Boolean,
    progress: Pair<Int, Int>?,
    onCollapse: () -> Unit,
) {
    val statusText = if (progress == null) {
        stringResource(R.string.music_panel_word_align_decoding)
    } else {
        stringResource(R.string.music_panel_word_align_progress, progress.first, progress.second)
    }
    LyricsTaskProgressDialog(
        visible = visible,
        title = stringResource(R.string.music_panel_word_align_title),
        statusText = statusText,
        fraction = progress?.let { if (it.second > 0) it.first / it.second.toFloat() else 0f },
        actionText = stringResource(R.string.music_panel_word_align_background),
        onCollapse = onCollapse,
    )
}
