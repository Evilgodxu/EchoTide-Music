package com.yichao.evilgodxu.screens.typography

import com.yichao.evilgodxu.data.settings.LandscapeLyricLayoutParams
import com.yichao.evilgodxu.data.settings.LyricLayoutDefaults
import com.yichao.evilgodxu.data.settings.LyricLayoutParams

// 歌词排版设置页状态：三个显示场景独立调节，迷你播放器固定跑马灯不参与
data class TypographyUiState(
    val musicPanel: LyricLayoutParams = LyricLayoutParams(
        LyricLayoutDefaults.MUSIC_PANEL_FONT_SIZE_SP,
        LyricLayoutDefaults.MUSIC_PANEL_VISIBLE_LINES,
        LyricLayoutDefaults.LINE_SPACING_DEFAULT_DP,
    ),
    val homePortrait: LyricLayoutParams = LyricLayoutParams(
        LyricLayoutDefaults.HOME_PORTRAIT_FONT_SIZE_SP,
        LyricLayoutDefaults.HOME_PORTRAIT_VISIBLE_LINES,
        LyricLayoutDefaults.LINE_SPACING_DEFAULT_DP,
    ),
    val homeLandscape: LandscapeLyricLayoutParams = LandscapeLyricLayoutParams(
        LyricLayoutDefaults.LANDSCAPE_FONT_SIZE_SP,
        LyricLayoutDefaults.LANDSCAPE_VISIBLE_LINES,
        LyricLayoutDefaults.LINE_SPACING_DEFAULT_DP,
        LyricLayoutDefaults.LANDSCAPE_3D_INTENSITY,
    ),
)