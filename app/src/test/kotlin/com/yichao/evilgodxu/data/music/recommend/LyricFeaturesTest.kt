package com.yichao.evilgodxu.data.music.recommend

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 歌词清洗复核：剔除制作信息行与空行。
 */
class LyricFeaturesTest {

    @Test
    fun `清洗歌词丢弃元数据与空行`() {
        val cleaned = LyricFeatures.cleanLyrics(listOf("作词：甲", "  ", "夜色真好", "制作人：乙"))
        assertEquals(listOf("夜色真好"), cleaned)
    }

    @Test
    fun `清洗歌词修剪剩余行`() {
        assertEquals(listOf("夜色真好"), LyricFeatures.cleanLyrics("  夜色真好  \n"))
    }
}