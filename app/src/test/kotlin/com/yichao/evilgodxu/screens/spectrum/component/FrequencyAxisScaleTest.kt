package com.yichao.evilgodxu.screens.spectrum.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 频率轴刻度复核：频率 ↔ 绘图区纵向占比。
 *
 * 左侧频率刻度与图内频带共用同一实例换算纵向位置，映射一旦不对称，
 * 刻度标注的频率就与图上色带所指的频率错位。
 */
class FrequencyAxisScaleTest {

    private companion object {
        const val EPS = 1e-3f
        // 往返换算经过一次对数与一次指数，量程顶端按单精度有约 1e-7 的相对误差
        const val INVERSE_EPS = 0.05f
        const val SAMPLE_RATE = 44_100
    }

    private val scale = FrequencyAxisScale.ofSampleRate(SAMPLE_RATE)

    @Test
    fun rangeSpansFromAudibleFloorToNyquist() {
        assertEquals(20f, scale.minHz, EPS)
        assertEquals(SAMPLE_RATE / 2f, scale.maxHz, EPS)
    }

    @Test
    fun endpointsMapToScaleEdges() {
        assertEquals(0f, scale.fractionOf(scale.minHz), EPS)
        assertEquals(1f, scale.fractionOf(scale.maxHz), EPS)
    }

    @Test
    fun outOfRangeFrequenciesClampToEdges() {
        // 量程外的取值收敛到端点，避免越界刻度被画到绘图区之外
        assertEquals(0f, scale.fractionOf(1f), EPS)
        assertEquals(1f, scale.fractionOf(1_000_000f), EPS)
        assertEquals(0f, scale.fractionOf(0.001f), EPS)
    }

    @Test
    fun fractionAndHertzAreMutuallyInverse() {
        for (hertz in listOf(20f, 100f, 440f, 1_000f, 10_000f, 22_050f)) {
            assertEquals("$hertz Hz", hertz, scale.hertzAt(scale.fractionOf(hertz)), INVERSE_EPS)
        }
    }

    @Test
    fun geometricMidpointSitsAtTheMiddleOfTheAxis() {
        // 等频程在图上等距：量程的几何中点落在纵向 0.5 处
        val middle = kotlin.math.sqrt(scale.minHz * scale.maxHz)
        assertEquals(0.5f, scale.fractionOf(middle), EPS)
    }

    @Test
    fun octaveSpansEqualFractions() {
        // 每倍频程占据相同的纵向长度，低频段因此被展开
        val oneOctave = scale.fractionOf(2_000f) - scale.fractionOf(1_000f)
        val anotherOctave = scale.fractionOf(8_000f) - scale.fractionOf(4_000f)
        assertEquals(oneOctave, anotherOctave, EPS)
        assertTrue(oneOctave > 0f)
    }

    @Test
    fun degenerateRangeDoesNotDivideByZero() {
        // 采样率低到与下限重合时对数跨度为 0，须有下限兜底而不是产生 NaN
        val degenerate = FrequencyAxisScale(20f, 20f)
        assertEquals(0f, degenerate.fractionOf(20f), EPS)
        assertEquals(20f, degenerate.hertzAt(0f), EPS)
        assertTrue(degenerate.fractionOf(100f).isFinite())
    }
}
