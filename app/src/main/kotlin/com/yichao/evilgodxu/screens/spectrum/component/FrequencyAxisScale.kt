package com.yichao.evilgodxu.screens.spectrum.component

import kotlin.math.exp
import kotlin.math.ln

// 下限 20Hz：对数轴不能取 0
private const val MIN_FREQUENCY_HZ = 20f

// 跨度下限，避免除零
private const val MIN_LOG_SPAN = 1e-3f

// 对数刻度：0 贴底、1 贴顶，超出收敛到端点
internal class FrequencyAxisScale(
    val minHz: Float,
    val maxHz: Float,
) {
    private val logMin = ln(minHz)
    private val logSpan = (ln(maxHz) - logMin).coerceAtLeast(MIN_LOG_SPAN)

    // 频率 → 纵向占比（自下而上）
    fun fractionOf(hertz: Float): Float = ((ln(hertz) - logMin) / logSpan).coerceIn(0f, 1f)

    // 纵向占比 → 频率
    fun hertzAt(fraction: Float): Float = exp(logMin + logSpan * fraction.coerceIn(0f, 1f))

    companion object {
        // 上限为奈奎斯特频率
        fun ofSampleRate(sampleRate: Int): FrequencyAxisScale =
            FrequencyAxisScale(MIN_FREQUENCY_HZ, sampleRate / 2f)
    }
}
