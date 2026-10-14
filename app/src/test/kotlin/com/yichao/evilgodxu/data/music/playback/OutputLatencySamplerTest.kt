package com.yichao.evilgodxu.data.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 延迟采样器的滑动平均。
 *
 * 独占路径没有音频轨可读，读数由写出侧直接给出（[OutputLatencySampler.sampleProvided]），
 * 与平台那条路共用同一套入窗规则——面板在两条路上看到的均值含义因此一致。
 */
class OutputLatencySamplerTest {

    private fun reading(
        residentMs: Float?,
        afterTrackMs: Float? = null,
        bufferFrames: Int = 4800,
    ) = OutputLatencyReading(
        trackBufferFrames = bufferFrames,
        trackBufferMs = 100f,
        afterTrackMs = afterTrackMs,
        trackResidentMs = residentMs,
        fullChainMs = null,
    )

    // 独占链路上只有驻留可测：均值取窗口内的驻留，容量是链路属性不参与平均
    @Test
    fun providedReadingAveragesTheResidency() {
        val sampler = OutputLatencySampler(window = 3)
        sampler.sampleProvided(reading(10f))
        sampler.sampleProvided(reading(20f))
        val averaged = sampler.sampleProvided(reading(30f))

        assertEquals(20f, averaged.trackResidentMs!!, 0.001f)
        assertEquals("容量不参与平均", 4800, averaged.trackBufferFrames)
        assertEquals(100f, averaged.trackBufferMs, 0.001f)
    }

    // 窗口只覆盖最近几次：更早的样本被挤出，均值随之跟手
    @Test
    fun windowDropsTheOldestSamples() {
        val sampler = OutputLatencySampler(window = 2)
        sampler.sampleProvided(reading(0f))
        sampler.sampleProvided(reading(10f))
        val averaged = sampler.sampleProvided(reading(20f))

        assertEquals(15f, averaged.trackResidentMs!!, 0.001f)
    }

    // 「缓冲之后」那一段在独占链路上测不到，全链路由两段相加得出，缺一段就不该产出——
    // 把它当成 0 会把「测不到」显示成「很短」
    @Test
    fun fullChainIsAbsentWhenTheAfterBufferSegmentIsUnmeasurable() {
        val sampler = OutputLatencySampler(window = 3)
        val averaged = sampler.sampleProvided(reading(12f))

        assertNull(averaged.afterTrackMs)
        assertNull(averaged.fullChainMs)
    }

    // 两段都测得到时，全链路取两段均值之和：面板上三行因此恒能对上
    @Test
    fun fullChainIsTheSumOfBothAveragedSegments() {
        val sampler = OutputLatencySampler(window = 3)
        sampler.sampleProvided(reading(residentMs = 4f, afterTrackMs = 6f))
        val averaged = sampler.sampleProvided(reading(residentMs = 6f, afterTrackMs = 8f))

        assertEquals(5f, averaged.trackResidentMs!!, 0.001f)
        assertEquals(7f, averaged.afterTrackMs!!, 0.001f)
        assertEquals(12f, averaged.fullChainMs!!, 0.001f)
    }
}
