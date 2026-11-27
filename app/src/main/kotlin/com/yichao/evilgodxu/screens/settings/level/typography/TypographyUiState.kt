package com.yichao.evilgodxu.screens.settings.level.typography

import com.yichao.evilgodxu.data.settings.LandscapeLyricLayoutParams
import com.yichao.evilgodxu.data.settings.LyricLayoutDefaults
import com.yichao.evilgodxu.data.settings.LyricLayoutParams

// 竖屏、横屏两场景各自调节
data class TypographyUiState(
    val homePortrait: LyricLayoutParams = LyricLayoutDefaults.HOME_PORTRAIT_LAYOUT,
    val homeLandscape: LandscapeLyricLayoutParams = LyricLayoutDefaults.LANDSCAPE_LAYOUT,
)
