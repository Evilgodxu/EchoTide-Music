package com.yichao.evilgodxu.data.music.metadata

import java.util.Locale

/**
 * ReplayGain 标签值的书写格式。
 *
 * 增益带 ` dB` 单位、峰值保留六位小数，与 foobar2000 / Quod Libet 写出的取值一致 ——
 * 这两个字段会被其它播放器读取，格式不一致时对方的解析器可能整条忽略。
 * 固定 [Locale.US]：小数点在部分区域设置下是逗号，写进标签就成了另一个数。
 */
internal fun formatReplayGainDb(gainDb: Double): String =
    String.format(Locale.US, "%.2f dB", gainDb)

internal fun formatReplayGainPeak(peak: Double): String =
    String.format(Locale.US, "%.6f", peak)
