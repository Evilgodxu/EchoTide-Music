package com.yichao.evilgodxu.ui.component

import androidx.compose.ui.graphics.vector.ImageVector
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.applyPlaybackMode
import com.yichao.evilgodxu.ui.icons.AppIcons

// 菜单呈现顺序，不影响以 ordinal 落盘
internal val playModeMenuOrder: List<PlayMode> =
    listOf(PlayMode.RepeatAll, PlayMode.RepeatOne, PlayMode.Shuffle, PlayMode.Highlight)

// 播放模式图标，多处共用
internal fun playModeIcon(mode: PlayMode): ImageVector = when (mode) {
    PlayMode.RepeatAll -> AppIcons.Repeat
    PlayMode.RepeatOne -> AppIcons.RepeatOne
    PlayMode.Shuffle -> AppIcons.Shuffle
    PlayMode.Highlight -> AppIcons.Bolt
}

// 播放模式名称资源
internal fun playModeLabelRes(mode: PlayMode): Int = when (mode) {
    PlayMode.RepeatAll -> R.string.music_panel_play_mode_repeat_all
    PlayMode.RepeatOne -> R.string.music_panel_play_mode_repeat_one
    PlayMode.Shuffle -> R.string.music_panel_play_mode_shuffle
    PlayMode.Highlight -> R.string.music_panel_play_mode_highlight
}

// 更新状态、同步控制器并落盘
internal fun selectPlayMode(playbackState: MusicPlaybackState, mode: PlayMode) {
    playbackState.setPlayMode(mode)
    playbackState.mediaController?.let { controller ->
        applyPlaybackMode(controller, playbackState.playMode)
    }
    playbackState.persistState()
}
