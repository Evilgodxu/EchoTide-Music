package com.yichao.evilgodxu.data.music.highlight

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 副歌候选定位复核（纯歌词）。
 *
 * 定位是纯函数，冷启动还原片段内进度依赖它的确定性，故除结果正确外还须验证「同输入同输出」，
 * 以及候选落在 30–45 秒、取最后一遍完整副歌、收尾落在歌词行边界三条主干。
 */
class HighlightLocatorTest {

    private fun timed(vararg pairs: Pair<Long, String>): List<LyricLine> =
        pairs.map { (timeMs, text) -> LyricLine(timeMs = timeMs, text = text) }

    private fun List<HighlightLocator.Candidate>.top(): HighlightLocator.Candidate? = firstOrNull()

    @Test
    fun candidatesTakeLastCompleteChorus() {
        // 同一副歌出现三遍，末遍之后还有一小段尾声：应取末遍，而非首遍
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
    fun candidatesAreDeterministic() {
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
    fun candidatesEmptyWhenNoRepeat() {
        val lines = timed(
            5_000L to "一", 10_000L to "二", 15_000L to "三", 20_000L to "四",
            25_000L to "五", 30_000L to "六", 35_000L to "七", 40_000L to "八",
            45_000L to "九", 50_000L to "十",
        )
        assertTrue("没有重复段落时应无候选", HighlightLocator.candidates(lines, 120_000L).isEmpty())
        assertTrue("歌词带时间轴", HighlightLocator.hasUsableTimeline(lines))
    }

    @Test
    fun candidatesEmptyForUntimedLyrics() {
        // 无时间轴歌词（全部落在 0）：行数再多也不产出候选，且判为「时间轴不可用」
        val lines = (1..20).map { LyricLine(timeMs = 0L, text = "副歌第 $it 行") }
        assertTrue(HighlightLocator.candidates(lines, 180_000L).isEmpty())
        assertFalse(HighlightLocator.hasUsableTimeline(lines))
    }

    @Test
    fun candidatesEmptyWhenTrackTooShortToBeMeaningful() {
        // 整曲短于片段下限的一半：取哪一段都短到没有意义，不产出候选
        val lines = timed(
            1_000L to "甲", 2_000L to "乙", 3_000L to "丙", 4_000L to "丁",
            5_000L to "甲", 6_000L to "乙", 7_000L to "丙", 8_000L to "丁",
            9_000L to "戊", 10_000L to "己",
        )
        assertTrue(HighlightLocator.candidates(lines, 12_000L).isEmpty())
    }

    @Test
    fun shortTrackStillYieldsItsOwnCandidate() {
        // 曲长在「下限一半」与「下限」之间：接受较短片段 ——
        // 把能唱的部分唱完，比硬凑满 30 秒更贴合听感
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
    fun candidateEndsOnLyricBoundaryInsteadOfHardCut() {
        // 收尾必须落在某一行的时间点上：最后一行会完整唱完，而不是被时长上限切在半句上
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
    fun candidateNeverExceedsMaxDurationWhenBlockItselfIsLong() {
        // 重复块自身就长过上限：必须向回收缩到 45 秒以内，而不是把整块原样返回
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
    fun candidateEndCoversLastWordThatOutlastsNextLine() {
        // 末字演唱终点越过后继行起点：收尾应取字终点，保证末句完整唱完，而不是在字中间截断。
        // 各遍副歌之间夹一段互不重复的桥段，避免跨段落的假重复干扰定位。
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
                // 末字延后到 180 秒，晚于后继行起点（176 秒）
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
    fun candidatesIgnoreMetadataLines() {
        // 制作信息行整段重复，不得被当成副歌：候选应落在真正的副歌上
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
    fun sparseCandidateLosesToDenseChorus() {
        // 重复但稀松的段落（行间长间隔、收不出几行）不得胜过一个密集的副歌段
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
