package com.yichao.evilgodxu.data.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 共用入窗规则，两段各成一窗 */
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
    )

    // 均值只取驻留，容量不参与
    @Test
    fun `单次读数按驻留时长取平均`() {
        val sampler = OutputLatencySampler(window = 3)
        sampler.sampleProvided(reading(10f))
        sampler.sampleProvided(reading(20f))
        val averaged = sampler.sampleProvided(reading(30f))

        assertEquals(20f, averaged.trackResidentMs!!, 0.001f)
        assertEquals("容量不参与平均", 4800, averaged.trackBufferFrames)
        assertEquals(100f, averaged.trackBufferMs, 0.001f)
    }

    // 只取最近几次，均值跟手
    @Test
    fun `窗口丢弃最旧的样本`() {
        val sampler = OutputLatencySampler(window = 2)
        sampler.sampleProvided(reading(0f))
        sampler.sampleProvided(reading(10f))
        val averaged = sampler.sampleProvided(reading(20f))

        assertEquals(15f, averaged.trackResidentMs!!, 0.001f)
    }

    // 测不到就不出均值，不拿驻留顶替
    @Test
    fun `换曲后不可测时保持无值`() {
        val sampler = OutputLatencySampler(window = 3)
        val averaged = sampler.sampleProvided(reading(12f))

        assertNull(averaged.afterTrackMs)
        assertEquals(12f, averaged.trackResidentMs!!, 0.001f)
    }

    // 各成一窗，互不影响
    @Test
    fun `各分段分别取平均`() {
        val sampler = OutputLatencySampler(window = 3)
        sampler.sampleProvided(reading(residentMs = 4f, afterTrackMs = 6f))
        val averaged = sampler.sampleProvided(reading(residentMs = 6f, afterTrackMs = 8f))

        assertEquals(5f, averaged.trackResidentMs!!, 0.001f)
        assertEquals(7f, averaged.afterTrackMs!!, 0.001f)
    }
}
