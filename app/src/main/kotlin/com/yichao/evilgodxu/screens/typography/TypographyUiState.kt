package com.yichao.evilgodxu.screens.typography

import com.yichao.evilgodxu.data.settings.LandscapeLyricLayoutParams
import com.yichao.evilgodxu.data.settings.LyricLayoutDefaults
import com.yichao.evilgodxu.data.settings.LyricLayoutParams

// 播放页排版设置页状态：首页竖屏与横屏两个显示场景独立调节（迷你播放器为固定排布的紧凑条，不参与）
data class TypographyUiState(
    val homePortrait: LyricLayoutParams = LyricLayoutDefaults.HOME_PORTRAIT_LAYOUT,
    val homeLandscape: LandscapeLyricLayoutParams = LyricLayoutDefaults.LANDSCAPE_LAYOUT,
)
