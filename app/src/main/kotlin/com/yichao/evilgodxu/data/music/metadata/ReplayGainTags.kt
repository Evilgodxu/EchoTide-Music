package com.yichao.evilgodxu.data.music.metadata

import java.util.Locale
import kotlin.math.pow

/** 增益带 dB、峰值六位小数；固定 Locale.US 防小数点变逗号 */
internal fun formatReplayGainDb(gainDb: Double): String =
    String.format(Locale.US, "%.2f dB", gainDb)

internal fun formatReplayGainPeak(peak: Double): String =
    String.format(Locale.US, "%.6f", peak)

/** 参考响度用 LUFS，非 ReplayGain 的 dB 刻度 */
internal fun formatReplayGainReferenceLoudness(lufs: Double): String =
    String.format(Locale.US, "%.2f LUFS", lufs)

/** 增益结果：linear 为音量系数，limited 表示曾限幅 */
internal data class ReplayGainGain(val linear: Float, val limited: Boolean)

/** 取标签增益 dB；缺失或非数字返回 null，与 0 dB 区分 */
internal fun parseReplayGainDb(raw: String?): Double? = raw?.numericPrefix()?.toDoubleOrNull()

/** 从标签原文取线性峰值；非正数视同缺失 */
internal fun parseReplayGainPeak(raw: String?): Double? =
    raw?.trim()?.toDoubleOrNull()?.takeIf { it > 0.0 }

/** dB 转线性系数并按峰值限幅；峰值缺失不限幅 */
internal fun replayGainLinear(gainDb: Double, peak: Double?): ReplayGainGain {
    val linear = 10.0.pow(gainDb / 20.0)
    if (peak == null) return ReplayGainGain(linear.toFloat(), false)
    val ceiling = 1.0 / peak
    return if (linear > ceiling) ReplayGainGain(ceiling.toFloat(), true) else ReplayGainGain(linear.toFloat(), false)
}

// 取标签开头的数值，丢弃单位
private fun String.numericPrefix(): String? {
    val trimmed = trim()
    val end = trimmed.indexOfFirst { it !in "+-.0123456789" }
    val head = (if (end < 0) trimmed else trimmed.take(end)).trimEnd('.')
    return head.takeIf { it.isNotEmpty() && it != "+" && it != "-" && it != "." }
}
