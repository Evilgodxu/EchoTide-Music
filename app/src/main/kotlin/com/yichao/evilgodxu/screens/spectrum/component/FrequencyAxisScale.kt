package com.yichao.evilgodxu.screens.spectrum.component

import kotlin.math.exp
import kotlin.math.ln

// 频率轴下限：对数轴表示不了 0Hz，且音频能量几乎不落在 20Hz 以下
private const val MIN_FREQUENCY_HZ = 20f

// 对数跨度下限：采样率异常（低到与下限重合）时避免除零
private const val MIN_LOG_SPAN = 1e-3f

// 频率轴刻度映射：频率 ↔ 绘图区纵向占比（0 贴底、1 贴顶）。
// 左侧频率刻度与图内频带共用同一实例换算纵向位置，两条信息因此一一对齐。
// 取对数刻度——等频程在图上等距，低频段被展开、高频段被压缩，与听感的频率分辨率一致。
// 量程外的取值收敛到端点，避免越界刻度画到绘图区之外
internal class FrequencyAxisScale(
    val minHz: Float,
    val maxHz: Float,
) {
    private val logMin = ln(minHz)
    private val logSpan = (ln(maxHz) - logMin).coerceAtLeast(MIN_LOG_SPAN)

    // 频率 → 纵向占比（自下而上）
    fun fractionOf(hertz: Float): Float = ((ln(hertz) - logMin) / logSpan).coerceIn(0f, 1f)

    // 纵向占比 → 频率（自下而上）：图内每一行覆盖的频率区间据此反算
    fun hertzAt(fraction: Float): Float = exp(logMin + logSpan * fraction.coerceIn(0f, 1f))

    companion object {
        // 按采样率构造：量程上限为奈奎斯特频率
        fun ofSampleRate(sampleRate: Int): FrequencyAxisScale =
            FrequencyAxisScale(MIN_FREQUENCY_HZ, sampleRate / 2f)
    }
}
