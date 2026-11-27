package com.yichao.evilgodxu.screens.spectrum.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 刻度与频带共用映射，须对称 */
class FrequencyAxisScaleTest {

    private companion object {
        const val EPS = 1e-3f
        // 往返对数/指数，顶端误差 1e-7
        const val INVERSE_EPS = 0.05f
        const val SAMPLE_RATE = 44_100
    }

    private val scale = FrequencyAxisScale.ofSampleRate(SAMPLE_RATE)

    @Test
    fun `范围从可听下限到奈奎斯特`() {
        assertEquals(20f, scale.minHz, EPS)
        assertEquals(SAMPLE_RATE / 2f, scale.maxHz, EPS)
    }

    @Test
    fun `两端点映射到坐标边缘`() {
        assertEquals(0f, scale.fractionOf(scale.minHz), EPS)
        assertEquals(1f, scale.fractionOf(scale.maxHz), EPS)
    }

    @Test
    fun `越界频率钳到边缘`() {
        // 越界取值收敛到端点
        assertEquals(0f, scale.fractionOf(1f), EPS)
        assertEquals(1f, scale.fractionOf(1_000_000f), EPS)
        assertEquals(0f, scale.fractionOf(0.001f), EPS)
    }

    @Test
    fun `比例与频率互为反算`() {
        for (hertz in listOf(20f, 100f, 440f, 1_000f, 10_000f, 22_050f)) {
            assertEquals("$hertz Hz", hertz, scale.hertzAt(scale.fractionOf(hertz)), INVERSE_EPS)
        }
    }

    @Test
    fun `几何中点落在轴中点`() {
        // 几何中点落在纵向 0.5
        val middle = kotlin.math.sqrt(scale.minHz * scale.maxHz)
        assertEquals(0.5f, scale.fractionOf(middle), EPS)
    }

    @Test
    fun `每个倍频程占相同比例`() {
        // 每倍频程占相同纵向长度
        val oneOctave = scale.fractionOf(2_000f) - scale.fractionOf(1_000f)
        val anotherOctave = scale.fractionOf(8_000f) - scale.fractionOf(4_000f)
        assertEquals(oneOctave, anotherOctave, EPS)
        assertTrue(oneOctave > 0f)
    }

    @Test
    fun `退化范围不做除零`() {
        // 对数为 0 时须兜底防 NaN
        val degenerate = FrequencyAxisScale(20f, 20f)
        assertEquals(0f, degenerate.fractionOf(20f), EPS)
        assertEquals(20f, degenerate.hertzAt(0f), EPS)
        assertTrue(degenerate.fractionOf(100f).isFinite())
    }
}
