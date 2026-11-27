package com.yichao.evilgodxu.ui.component.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R

/**
 * 自动补译进度：收起后任务继续在后台
 */
@Composable
internal fun LyricsTranslateDialog(
    visible: Boolean,
    progress: Pair<Int, Int>?,
    onCollapse: () -> Unit,
) {
    LyricsTaskProgressDialog(
        visible = visible,
        title = stringResource(R.string.music_panel_auto_translate_title),
        // 按批返回，进度以已提交行数计
        statusText = if (progress == null) {
            stringResource(R.string.music_panel_auto_translate_pending)
        } else {
            stringResource(R.string.music_panel_auto_translate_progress, progress.first, progress.second)
        },
        fraction = progress?.let { if (it.second > 0) it.first / it.second.toFloat() else 0f },
        actionText = stringResource(R.string.music_panel_auto_translate_background),
        onCollapse = onCollapse,
    )
}
