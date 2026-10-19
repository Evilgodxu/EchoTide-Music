package com.yichao.evilgodxu.data.music.analysis

import com.yichao.evilgodxu.data.music.playback.AudioSignalPathFormat

// 无损格式集合：命中的格式已无需再升级
private val LOSSLESS_FORMATS = setOf(
    "FLAC", "WAV", "WAVE", "ALAC", "PCM",
)

// 格式名未知时不视为无损
internal fun isLosslessFormat(format: AudioSignalPathFormat): Boolean =
    format.format?.let { isLosslessFormatName(it.removePrefix("audio/")) } == true

internal fun isLosslessFormatName(name: String): Boolean {
    val normalized = name.uppercase().trim()
    return normalized in LOSSLESS_FORMATS || normalized.endsWith("LOSSLESS")
}
