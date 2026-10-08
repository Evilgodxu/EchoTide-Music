package com.yichao.evilgodxu.ui.component

import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.api.MusicQuality

// 音质档位的展示文案：Hi-Res 与无损合并为「无损」，与解析时无损档自动优先的约定一致
internal fun qualityLabelRes(quality: MusicQuality): Int = when (quality) {
    MusicQuality.HI_RES,
    MusicQuality.LOSSLESS -> R.string.music_quality_lossless
    MusicQuality.HIGH -> R.string.music_quality_high
    MusicQuality.STANDARD -> R.string.music_quality_standard
}
