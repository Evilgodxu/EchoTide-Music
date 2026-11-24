package com.yichao.evilgodxu.data.music.metadata

import java.util.Locale
import kotlin.math.pow

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

/** 播放侧施加一次增益的结果：[linear] 是乘进音量的系数，[limited] 表示曾按峰值限幅 */
internal data class ReplayGainGain(val linear: Float, val limited: Boolean)

/**
 * 从标签原文取增益（dB）。缺失或不是数字时返回 null —— 调用方据此把「本曲无标签」与「0 dB」区分开，
 * 前者不施加、也不在界面显示假值。
 */
internal fun parseReplayGainDb(raw: String?): Double? = raw?.numericPrefix()?.toDoubleOrNull()

/** 从标签原文取线性峰值；非正数视同缺失 */
internal fun parseReplayGainPeak(raw: String?): Double? =
    raw?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }

/**
 * 把 dB 换算成施加用的线性系数，并按峰值限幅。
 *
 * 限幅的必要性：标签里的增益是对着 -18 LUFS 的目标算的，正增益叠在本身就接近满幅的曲目上会削波。
 * 峰值缺失时不限幅——那种曲目本来就无从判断余量，按标签原值施加比擅自压一半更诚实。
 */
internal fun replayGainLinear(gainDb: Double, peak: Double?): ReplayGainGain {
    val linear = 10.0.pow(gainDb / 20.0)
    if (peak == null) return ReplayGainGain(linear.toFloat(), false)
    val ceiling = 1.0 / peak
    return if (linear > ceiling) ReplayGainGain(ceiling.toFloat(), true) else ReplayGainGain(linear.toFloat(), false)
}

// 标签值形如 "-7.60 dB" / "89.0 dB"：取开头的数值部分，单位与空白一并丢弃
private fun String.numericPrefix(): String? {
    val trimmed = trim()
    val end = trimmed.indexOfFirst { it !in "+-.0123456789" }
    val head = (if (end < 0) trimmed else trimmed.take(end)).trimEnd('.')
    return head.takeIf { it.isNotEmpty() && it != "+" && it != "-" && it != "." }
}
