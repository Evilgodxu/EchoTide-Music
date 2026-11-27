package com.yichao.evilgodxu.data.music.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 候选链顶端 Hi-Res，顺序错则回退错档 */
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
        // 候选链须覆盖四档且不重复
        MusicQuality.entries.forEach { quality ->
            val candidates = quality.adaptiveCandidates()
            assertEquals("${quality}候选链未覆盖全部档位", MusicQuality.entries.size, candidates.toSet().size)
            assertTrue("${quality}候选链未降到标准档", MusicQuality.STANDARD in candidates)
        }
    }

    @Test
    fun `高解析档不可由用户选择`() {
        // Hi-Res 仅解析层级，不可选
        assertFalse(MusicQuality.HI_RES.userSelectable)
        assertTrue(MusicQuality.LOSSLESS.userSelectable)
        assertTrue(MusicQuality.HIGH.userSelectable)
        assertTrue(MusicQuality.STANDARD.userSelectable)
    }
}
