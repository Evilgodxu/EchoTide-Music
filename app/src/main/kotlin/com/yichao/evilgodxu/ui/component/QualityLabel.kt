package com.yichao.evilgodxu.ui.component

import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.api.MusicQuality

// Hi-Res 与无损合并显示为「无损」
internal fun qualityLabelRes(quality: MusicQuality): Int = when (quality) {
    MusicQuality.HI_RES,
    MusicQuality.LOSSLESS -> R.string.music_quality_lossless
    MusicQuality.HIGH -> R.string.music_quality_high
    MusicQuality.STANDARD -> R.string.music_quality_standard
}
