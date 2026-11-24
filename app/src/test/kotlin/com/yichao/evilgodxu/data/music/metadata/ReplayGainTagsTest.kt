package com.yichao.evilgodxu.data.music.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ReplayGain 标签的读取口径：单位剥离、缺项判定与峰值限幅。
 *
 * 只测纯换算——读文件与写容器不在这里；这里的每条断言都对应播放侧会看到的取值。
 */
class ReplayGainTagsTest {

    @Test
    fun `解析带负号与单位的增益`() {
        assertEquals(-7.6, parseReplayGainDb("-7.60 dB")!!, 0.0001)
    }

    @Test
    fun `解析带正号的增益`() {
        assertEquals(2.0, parseReplayGainDb("+2.00 dB")!!, 0.0001)
    }

    @Test
    fun `无标签或无法解析时返回空`() {
        assertNull(parseReplayGainDb(null))
        assertNull(parseReplayGainDb(""))
        assertNull(parseReplayGainDb("dB"))
    }

    @Test
    fun `峰值缺失或非正数视为空`() {
        assertNull(parseReplayGainPeak(null))
        assertNull(parseReplayGainPeak("0"))
        assertEquals(0.98, parseReplayGainPeak("0.98")!!, 0.0001)
    }

    @Test
    fun `正增益超过峰值余量时按峰值限幅`() {
        val result = replayGainLinear(gainDb = 6.0, peak = 0.9)
        assertTrue(result.limited)
        assertEquals(1.0 / 0.9, result.linear.toDouble(), 0.0001)
    }

    @Test
    fun `负增益不会触发限幅`() {
        val result = replayGainLinear(gainDb = -6.0, peak = 0.99)
        assertFalse(result.limited)
        assertEquals(0.501187, result.linear.toDouble(), 0.0001)
    }

    @Test
    fun `峰值缺失时按标签原值施加`() {
        val result = replayGainLinear(gainDb = 6.0, peak = null)
        assertFalse(result.limited)
        assertEquals(1.995262, result.linear.toDouble(), 0.0001)
    }
}
