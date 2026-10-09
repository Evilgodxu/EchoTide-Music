package com.yichao.evilgodxu.data.music.highlight

import com.yichao.evilgodxu.data.music.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 副歌定位复核。
 *
 * 定位是纯函数，冷启动还原片段内进度依赖它的确定性，故除结果正确外还须验证「同输入同输出」。
 */
class HighlightLocatorTest {

    private fun timed(vararg pairs: Pair<Long, String>): List<LyricLine> =
        pairs.map { (timeMs, text) -> LyricLine(timeMs = timeMs, text = text) }

    @Test
    fun locateFindsRepeatedChorusBlock() {
        val lines = timed(
            5_000L to "主歌第一行",
            9_000L to "主歌第二行",
            13_000L to "主歌第三行",
            17_000L to "主歌第四行",
            22_000L to "副歌甲",
            26_000L to "副歌乙",
            30_000L to "副歌丙",
            34_000L to "副歌丁",
            39_000L to "桥段第一行",
            43_000L to "桥段第二行",
            48_000L to "副歌甲",
            52_000L to "副歌乙",
            56_000L to "副歌丙",
            60_000L to "副歌丁",
            66_000L to "副歌甲",
            70_000L to "副歌乙",
            74_000L to "副歌丙",
            78_000L to "副歌丁",
            85_000L to "尾声",
            90_000L to "尾声二",
        )

        val highlight = HighlightLocator.locate(lines, 120_000L)
        assertNotNull("重复四次的副歌段应被定位", highlight)
        highlight!!
        assertTrue("片段应自副歌首次出现处开始", highlight.startMs in 18_000L..22_000L)
        assertTrue("片段时长应落在 30–45 秒", highlight.durationMs in 30_000L..45_000L)
    }

    @Test
    fun locateIsDeterministic() {
        val lines = timed(
            4_000L to "甲一", 8_000L to "甲二", 12_000L to "乙一", 16_000L to "乙二",
            21_000L to "副歌一", 25_000L to "副歌二", 30_000L to "丙一", 34_000L to "丙二",
            40_000L to "副歌一", 44_000L to "副歌二", 50_000L to "副歌一", 54_000L to "副歌二",
            60_000L to "戊一", 65_000L to "戊二",
        )
        val first = HighlightLocator.locate(lines, 100_000L)
        val second = HighlightLocator.locate(lines, 100_000L)
        assertEquals("同一份歌词与时长必须得到同一区间", first, second)
    }

    @Test
    fun locateReturnsNullWhenNoRepeat() {
        val lines = timed(
            5_000L to "一", 10_000L to "二", 15_000L to "三", 20_000L to "四",
            25_000L to "五", 30_000L to "六", 35_000L to "七", 40_000L to "八",
            45_000L to "九", 50_000L to "十",
        )
        assertNull("没有重复段落时不可定位", HighlightLocator.locate(lines, 120_000L))
    }

    @Test
    fun locateReturnsNullForUntimedLyrics() {
        // 无时间轴歌词（全部落在 0）：行数再多也不可定位
        val lines = (1..20).map { LyricLine(timeMs = 0L, text = "副歌第 $it 行") }
        assertNull(HighlightLocator.locate(lines, 180_000L))
    }

    @Test
    fun locateReturnsNullWhenTrackTooShortToBeMeaningful() {
        // 整曲短于片段下限的一半：取哪一段都短到没有意义，判不可定位
        val lines = timed(
            1_000L to "甲", 2_000L to "乙", 3_000L to "丙", 4_000L to "丁",
            5_000L to "甲", 6_000L to "乙", 7_000L to "丙", 8_000L to "丁",
            9_000L to "戊", 10_000L to "己",
        )
        assertNull("整曲不足片段下限一半时不可定位", HighlightLocator.locate(lines, 12_000L))
    }

    @Test
    fun shortTrackStillYieldsItsOwnSegment() {
        // 曲长在「下限一半」与「下限」之间：接受较短片段 ——
        // 把能唱的部分唱完，比硬凑满 30 秒更贴合听感
        val lines = timed(
            1_000L to "甲", 3_000L to "乙", 5_000L to "丙", 7_000L to "丁",
            9_000L to "甲", 11_000L to "乙", 13_000L to "丙", 15_000L to "丁",
            17_000L to "戊", 19_000L to "己",
        )
        val highlight = HighlightLocator.locate(lines, 20_000L)
        assertNotNull("曲长足以容纳片段时应给出片段", highlight)
        assertTrue("片段不得短于下限的一半", highlight!!.durationMs >= 15_000L)
    }

    @Test
    fun segmentKeepsChorusStartAndRunsToTrackEnd() {
        // 副歌靠近曲末：起点仍落在副歌上（不回退去凑时长），一直唱到曲末 ——
        // 此时把最后几句唱完比凑满下限更贴合听感
        val lines = timed(
            5_000L to "主歌一", 9_000L to "主歌二", 13_000L to "主歌三", 17_000L to "主歌四",
            21_000L to "主歌五", 25_000L to "主歌六", 29_000L to "主歌七", 33_000L to "主歌八",
            40_000L to "副歌甲", 44_000L to "副歌乙", 48_000L to "副歌丙",
            54_000L to "副歌甲", 58_000L to "副歌乙", 62_000L to "副歌丙",
        )
        val highlight = HighlightLocator.locate(lines, 66_000L)
        assertNotNull(highlight)
        highlight!!
        assertEquals("起点应落在副歌首行", 40_000L, highlight.startMs)
        assertEquals("应一直唱到曲末，而不是提前切走", 66_000L, highlight.endMs)
    }

    @Test
    fun segmentEndsOnLyricBoundaryInsteadOfHardCut() {
        // 收尾必须落在某一行的时间点上：最后一行会完整唱完，而不是被时长上限切在半句上
        val lines = timed(
            4_000L to "甲一", 8_000L to "甲二", 12_000L to "乙一", 16_000L to "乙二",
            21_000L to "副歌一", 25_000L to "副歌二", 30_000L to "丙一", 34_000L to "丙二",
            40_000L to "副歌一", 44_000L to "副歌二", 50_000L to "副歌一", 54_000L to "副歌二",
            61_000L to "戊一", 66_000L to "戊二",
        )
        val highlight = HighlightLocator.locate(lines, 90_000L)
        assertNotNull(highlight)
        highlight!!
        val boundaries = lines.map { it.timeMs } + 90_000L
        assertTrue(
            "收尾 ${highlight.endMs} 必须落在歌词行边界上",
            highlight.endMs in boundaries,
        )
        assertTrue("片段时长仍应落在目标区间", highlight.durationMs in 30_000L..45_000L)
    }

    @Test
    fun locateIgnoresMetadataLines() {
        // 制作信息行整段重复，不得被当成副歌：定位结果应落在真正的副歌上
        val lines = timed(
            1_000L to "作词：甲", 2_000L to "作曲：乙", 3_000L to "编曲：丙", 4_000L to "制作人：丁",
            10_000L to "主歌一", 15_000L to "主歌二", 20_000L to "主歌三", 25_000L to "主歌四",
            32_000L to "高潮甲", 36_000L to "高潮乙", 40_000L to "高潮丙", 44_000L to "高潮丁",
            50_000L to "间奏一", 54_000L to "间奏二",
            60_000L to "高潮甲", 64_000L to "高潮乙", 68_000L to "高潮丙", 72_000L to "高潮丁",
        )
        val highlight = HighlightLocator.locate(lines, 90_000L)
        assertNotNull(highlight)
        highlight!!
        assertTrue("片段起点不应落在曲首的制作信息行上", highlight.startMs >= 25_000L)
    }
}
