package com.yichao.evilgodxu.data.music.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音质档位复核：自适应候选顺序决定解析失败时向哪一档回退。
 *
 * 候选链顶端恒为 Hi-Res，母带等平台升频音质不参与匹配 ——
 * 顺序错一档就会在无损可用时播到有损，或在有损可用时反复解析失败。
 */
class MusicQualityTest {

    @Test
    fun `无损档位从高解析开始逐级下降`() {
        val expected = listOf(MusicQuality.HI_RES, MusicQuality.LOSSLESS, MusicQuality.HIGH, MusicQuality.STANDARD)
        assertEquals(expected, MusicQuality.HI_RES.adaptiveCandidates())
        assertEquals(expected, MusicQuality.LOSSLESS.adaptiveCandidates())
    }

    @Test
    fun `高品质先取自身再升到无损`() {
        assertEquals(
            listOf(MusicQuality.HIGH, MusicQuality.LOSSLESS, MusicQuality.HI_RES, MusicQuality.STANDARD),
            MusicQuality.HIGH.adaptiveCandidates(),
        )
    }

    @Test
    fun `标准音质单调递增`() {
        assertEquals(
            listOf(MusicQuality.STANDARD, MusicQuality.HIGH, MusicQuality.LOSSLESS, MusicQuality.HI_RES),
            MusicQuality.STANDARD.adaptiveCandidates(),
        )
    }

    @Test
    fun `每个候选链都以最低档收尾`() {
        // 全部档位都不可用才判定失败，故候选链必须覆盖全部四档且不重复
        MusicQuality.entries.forEach { quality ->
            val candidates = quality.adaptiveCandidates()
            assertEquals("${quality}候选链未覆盖全部档位", MusicQuality.entries.size, candidates.toSet().size)
            assertTrue("${quality}候选链未降到标准档", MusicQuality.STANDARD in candidates)
        }
    }

    @Test
    fun `高解析档不可由用户选择`() {
        // Hi-Res 只作为无损档内的解析层级，不作为用户可选档位
        assertFalse(MusicQuality.HI_RES.userSelectable)
        assertTrue(MusicQuality.LOSSLESS.userSelectable)
        assertTrue(MusicQuality.HIGH.userSelectable)
        assertTrue(MusicQuality.STANDARD.userSelectable)
    }
}
