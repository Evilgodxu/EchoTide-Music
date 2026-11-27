package com.yichao.evilgodxu.data.music.highlight

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.LyricWord
import com.yichao.evilgodxu.data.music.model.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 候选取最后一遍完整副歌，收尾落行边界 */
class HighlightLocatorTest {

    private fun timed(vararg pairs: Pair<Long, String>): List<LyricLine> =
        pairs.map { (timeMs, text) -> LyricLine(timeMs = timeMs, text = text) }

    private fun List<HighlightLocator.Candidate>.top(): HighlightLocator.Candidate? = firstOrNull()

    @Test
    fun `候选取最后一段完整副歌`() {
        // 副歌重复三遍，取末遍
        val lines = timed(
            5_000L to "主歌一", 9_000L to "主歌二", 13_000L to "主歌三", 17_000L to "主歌四",
            22_000L to "副歌甲", 26_000L to "副歌乙", 30_000L to "副歌丙", 34_000L to "副歌丁",
            39_000L to "桥段一", 43_000L to "桥段二",
            48_000L to "副歌甲", 52_000L to "副歌乙", 56_000L to "副歌丙", 60_000L to "副歌丁",
            66_000L to "副歌甲", 70_000L to "副歌乙", 74_000L to "副歌丙", 78_000L to "副歌丁",
            84_000L to "尾声一", 88_000L to "尾声二", 92_000L to "尾声三", 96_000L to "尾声四",
        )

        val candidates = HighlightLocator.candidates(lines, 120_000L)
        val top = candidates.top()
        assertNotNull("重复三次的副歌段应产出候选", top)
        top!!
        assertEquals("应取最后一遍完整副歌", 66_000L, top.startMs)
        assertTrue("候选时长应落在 30–45 秒", top.durationMs in 30_000L..45_000L)
        assertTrue("副歌反复三次的重复证据应被记录", top.repeats >= 3)
    }

    @Test
    fun `候选结果确定`() {
        val lines = timed(
            4_000L to "甲一", 8_000L to "甲二", 12_000L to "乙一", 16_000L to "乙二",
            21_000L to "副歌一", 25_000L to "副歌二", 30_000L to "丙一", 34_000L to "丙二",
            40_000L to "副歌一", 44_000L to "副歌二", 50_000L to "副歌一", 54_000L to "副歌二",
            64_000L to "戊一", 68_000L to "戊二", 72_000L to "戊三",
        )
        val first = HighlightLocator.candidates(lines, 100_000L)
        val second = HighlightLocator.candidates(lines, 100_000L)
        assertEquals("同一份歌词与时长必须得到同一组候选", first, second)
    }

    @Test
    fun `时间轴相同而歌词改变时指纹变化`() {
        // 正文全换时指纹须变，否则旧结论压住新词
        fun track(text: (Int) -> String): MusicTrack = MusicTrack(
            id = 1L,
            path = "",
            audioUri = "",
            title = "曲目",
            artist = "歌手",
            albumId = 0L,
            duration = 120_000L,
            lyricLines = (0 until 20).map { index ->
                LyricLine(timeMs = 5_000L + index * 4_000L, text = text(index))
            },
        )
        val old = HighlightStore.fingerprintOf(track { "旧词$it" })
        val new = HighlightStore.fingerprintOf(track { "新词$it" })
        assertNotEquals("正文改变后指纹必须改变", old, new)
    }

    @Test
    fun `无重复段落时无候选`() {
        val lines = timed(
            5_000L to "一", 10_000L to "二", 15_000L to "三", 20_000L to "四",
            25_000L to "五", 30_000L to "六", 35_000L to "七", 40_000L to "八",
            45_000L to "九", 50_000L to "十",
        )
        assertTrue("没有重复段落时应无候选", HighlightLocator.candidates(lines, 120_000L).isEmpty())
        assertTrue("歌词带时间轴", HighlightLocator.hasUsableTimeline(lines))
    }

    @Test
    fun `无时间轴歌词无候选`() {
        // 全为 0 时无候选且判时间轴不可用
        val lines = (1..20).map { LyricLine(timeMs = 0L, text = "副歌第 $it 行") }
        assertTrue(HighlightLocator.candidates(lines, 180_000L).isEmpty())
        assertFalse(HighlightLocator.hasUsableTimeline(lines))
    }

    @Test
    fun `曲目过短时无候选`() {
        // 短于下限一半时无候选
        val lines = timed(
            1_000L to "甲", 2_000L to "乙", 3_000L to "丙", 4_000L to "丁",
            5_000L to "甲", 6_000L to "乙", 7_000L to "丙", 8_000L to "丁",
            9_000L to "戊", 10_000L to "己",
        )
        assertTrue(HighlightLocator.candidates(lines, 12_000L).isEmpty())
    }

    @Test
    fun `短曲目仍产出自身候选`() {
        // 曲长不足下限时接受较短片段
        val lines = timed(
            1_000L to "甲", 3_000L to "乙", 5_000L to "丙", 7_000L to "丁",
            9_000L to "甲", 11_000L to "乙", 13_000L to "丙", 15_000L to "丁",
            17_000L to "戊", 19_000L to "己",
        )
        val top = HighlightLocator.candidates(lines, 20_000L).top()
        assertNotNull("曲长足以容纳片段时应给出候选", top)
        assertTrue("候选不得短于下限的一半", top!!.durationMs >= 15_000L)
    }

    @Test
    fun `候选在歌词边界收尾而非硬切`() {
        // 收尾落在行时间点，不切半句
        val lines = timed(
            4_000L to "甲一", 8_000L to "甲二", 12_000L to "乙一", 16_000L to "乙二",
            21_000L to "副歌一", 25_000L to "副歌二", 30_000L to "丙一", 34_000L to "丙二",
            40_000L to "副歌一", 44_000L to "副歌二", 50_000L to "副歌一", 54_000L to "副歌二",
            61_000L to "戊一", 66_000L to "戊二",
        )
        val top = HighlightLocator.candidates(lines, 90_000L).top()
        assertNotNull(top)
        top!!
        val boundaries = lines.map { it.timeMs } + 90_000L
        assertTrue(
            "收尾 ${top.endMs} 必须落在歌词行边界上",
            top.endMs in boundaries,
        )
        assertTrue("候选时长仍应落在目标区间", top.durationMs in 30_000L..45_000L)
    }

    @Test
    fun `段落本身超长时候选不超时长上限`() {
        // 超长段落向回收缩至 45 秒内
        val lines = timed(
            0L to "主歌一", 4_000L to "主歌二", 8_000L to "主歌三", 12_000L to "主歌四",
            20_000L to "高潮一", 28_000L to "高潮二", 36_000L to "高潮三",
            44_000L to "高潮四", 52_000L to "高潮五", 60_000L to "高潮六",
            68_000L to "桥段一", 72_000L to "桥段二",
            100_000L to "高潮一", 108_000L to "高潮二", 116_000L to "高潮三",
            124_000L to "高潮四", 132_000L to "高潮五", 140_000L to "高潮六",
            150_000L to "尾声一", 155_000L to "尾声二",
        )
        val top = HighlightLocator.candidates(lines, 180_000L).top()
        assertNotNull(top)
        top!!
        assertTrue(
            "候选时长 ${top.durationMs} 不得超过上限",
            top.durationMs <= HighlightLocator.MAX_SEGMENT_MS,
        )
        assertTrue("候选时长不得低于下限", top.durationMs >= HighlightLocator.MIN_SEGMENT_MS)
    }

    @Test
    fun `候选结尾覆盖跨行的末字`() {
        // 收尾取末字终点，不截断
        // 副歌间夹不重复桥段，避免假重复
        val lines = listOf(
            LyricLine(timeMs = 0L, text = "主歌一"),
            LyricLine(timeMs = 10_000L, text = "主歌二"),
            LyricLine(timeMs = 20_000L, text = "主歌三"),
            LyricLine(timeMs = 30_000L, text = "主歌四"),
            LyricLine(timeMs = 40_000L, text = "主歌五"),
            LyricLine(timeMs = 50_000L, text = "主歌六"),
            LyricLine(timeMs = 60_000L, text = "副歌一"),
            LyricLine(timeMs = 68_000L, text = "副歌二"),
            LyricLine(timeMs = 76_000L, text = "副歌三"),
            LyricLine(timeMs = 84_000L, text = "副歌四"),
            LyricLine(timeMs = 92_000L, text = "桥段一"),
            LyricLine(timeMs = 100_000L, text = "副歌一"),
            LyricLine(timeMs = 108_000L, text = "副歌二"),
            LyricLine(timeMs = 116_000L, text = "副歌三"),
            LyricLine(timeMs = 124_000L, text = "副歌四"),
            LyricLine(timeMs = 132_000L, text = "桥段二"),
            LyricLine(timeMs = 140_000L, text = "副歌一"),
            LyricLine(timeMs = 148_000L, text = "副歌二"),
            LyricLine(timeMs = 156_000L, text = "副歌三"),
            LyricLine(
                timeMs = 164_000L,
                text = "副歌四",
                // 末字到 180 秒，晚于下一行 176 秒
                words = listOf(LyricWord(164_000L, 16_000L, "副歌四")),
            ),
            LyricLine(timeMs = 176_000L, text = "尾声一"),
            LyricLine(timeMs = 180_000L, text = "尾声二"),
        )
        val top = HighlightLocator.candidates(lines, 200_000L).top()
        assertNotNull(top)
        assertEquals("收尾应覆盖末字终点 180 秒", 180_000L, top!!.endMs)
        assertTrue("候选起点应为最后一遍副歌", top.startMs >= 140_000L)
        assertTrue("候选时长应在目标区间", top.durationMs in 30_000L..45_000L)
    }

    @Test
    fun `候选忽略元数据行`() {
        // 制作信息重复不得当作副歌
        val lines = timed(
            1_000L to "作词：甲", 2_000L to "作曲：乙", 3_000L to "编曲：丙", 4_000L to "制作人：丁",
            10_000L to "主歌一", 15_000L to "主歌二", 20_000L to "主歌三", 25_000L to "主歌四",
            32_000L to "高潮甲", 36_000L to "高潮乙", 40_000L to "高潮丙", 44_000L to "高潮丁",
            50_000L to "间奏一", 54_000L to "间奏二",
            60_000L to "高潮甲", 64_000L to "高潮乙", 68_000L to "高潮丙", 72_000L to "高潮丁",
        )
        val top = HighlightLocator.candidates(lines, 90_000L).top()
        assertNotNull(top)
        assertTrue("候选起点不应落在曲首的制作信息行上", top!!.startMs >= 25_000L)
    }

    @Test
    fun `稀疏候选让位于密集副歌`() {
        // 稀松段落不得胜过密集副歌
        val lines = timed(
            0L to "密一", 2_000L to "密二", 4_000L to "密三", 6_000L to "密四",
            8_000L to "密五", 10_000L to "密六",
            30_000L to "密一", 32_000L to "密二", 34_000L to "密三", 36_000L to "密四",
            38_000L to "密五", 40_000L to "密六",
            60_000L to "密一", 62_000L to "密二", 64_000L to "密三", 66_000L to "密四",
            68_000L to "密五", 70_000L to "密六",
            100_000L to "稀一", 130_000L to "稀二",
            160_000L to "稀一", 190_000L to "稀二",
        )
        val candidates = HighlightLocator.candidates(lines, 220_000L)
        val top = candidates.top()
        assertNotNull(top)
        assertEquals("应选中密集段的最后一遍", 60_000L, top!!.startMs)
        assertTrue("首位候选必是歌词行数最多者", top.lineCount == candidates.maxOf { it.lineCount })
        assertTrue("稀松段落应落选在首位之后", candidates.drop(1).any { it.startMs >= 100_000L })
    }
}
