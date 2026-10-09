package com.yichao.evilgodxu.ui.component

import androidx.compose.ui.graphics.vector.ImageVector
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.applyPlaybackMode
import com.yichao.evilgodxu.ui.icons.AppIcons

// 播放模式在向上展开菜单中的呈现顺序：与既有循环切换顺序一致，当前模式与相邻项衔接自然。
// 顺序只影响展示，不影响 PlayMode 以 ordinal 落盘的持久化约定（见 PlayMode 枚举注释）
internal val playModeMenuOrder: List<PlayMode> =
    listOf(PlayMode.RepeatAll, PlayMode.RepeatOne, PlayMode.Shuffle, PlayMode.Highlight)

// 播放模式图标：三处播放模式控件共用，避免同一映射多处重复演化
internal fun playModeIcon(mode: PlayMode): ImageVector = when (mode) {
    PlayMode.RepeatAll -> AppIcons.Repeat
    PlayMode.RepeatOne -> AppIcons.RepeatOne
    PlayMode.Shuffle -> AppIcons.Shuffle
    PlayMode.Highlight -> AppIcons.Bolt
}

// 播放模式名称资源：向上展开菜单中每项图标所需的无障碍描述
internal fun playModeLabelRes(mode: PlayMode): Int = when (mode) {
    PlayMode.RepeatAll -> R.string.music_panel_play_mode_repeat_all
    PlayMode.RepeatOne -> R.string.music_panel_play_mode_repeat_one
    PlayMode.Shuffle -> R.string.music_panel_play_mode_shuffle
    PlayMode.Highlight -> R.string.music_panel_play_mode_highlight
}

// 应用所选播放模式：更新状态、同步媒体控制器、落盘。三处播放模式控件共用同一套切换逻辑
internal fun selectPlayMode(playbackState: MusicPlaybackState, mode: PlayMode) {
    playbackState.setPlayMode(mode)
    playbackState.mediaController?.let { controller ->
        applyPlaybackMode(controller, playbackState.playMode)
    }
    playbackState.persistState()
}
