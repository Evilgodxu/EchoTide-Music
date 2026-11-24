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

/**
 * 参考响度的书写格式。
 *
 * 本应用按 EBU R128 自算增益，Reference Loudness 因此写 `LUFS` 而非 ReplayGain 1.0 时代的
 * `89.0 dB`——两者是同一基准的不同刻度，写错刻度会让按 dB 读标签的工具算偏约 0.3 dB。
 */
internal fun formatReplayGainReferenceLoudness(lufs: Double): String =
    String.format(Locale.US, "%.2f LUFS", lufs)
