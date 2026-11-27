package com.yichao.evilgodxu.ui.component.player

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 逐字歌词时序：词终点兜底、行尾提前量与点亮比例 */
class LyricLineTimingTest {

    private companion object {
        const val EPS = 1e-4f

        // 末词没有下一行时的行终点兜底值
        const val NEXT_LINE_MS = 9_000L

        // 末词延伸到句尾时的行尾
        const val TAIL_LINE_END_MS = 5_000L
    }

    // 每词 1s、词间空 1s，验证空隙停住
    private val gappedWords = listOf(
        LyricWord(startMs = 0L, durationMs = 1_000L, text = "你"),
        LyricWord(startMs = 2_000L, durationMs = 1_000L, text = "好"),
        LyricWord(startMs = 4_000L, durationMs = 1_000L, text = "世"),
        LyricWord(startMs = 5_000L, durationMs = 1_000L, text = "界"),
    )

    // 末词延到行尾 5s，验证提前量
    private val tailReachingWords = listOf(
        LyricWord(startMs = 0L, durationMs = 1_000L, text = "你"),
        LyricWord(startMs = 1_000L, durationMs = 1_000L, text = "好"),
        LyricWord(startMs = 2_000L, durationMs = 3_000L, text = "世"),
    )

    @Test
    fun `字结束时间优先取自身时长`() {
        assertEquals(listOf(1_000L, 3_000L, 5_000L, 6_000L), lyricWordEnds(gappedWords, NEXT_LINE_MS))
    }

    @Test
    fun `零时长字在下一字起点结束`() {
        // 只给起点时末词以下一行收尾
        val words = listOf(
            LyricWord(startMs = 0L, durationMs = 0L, text = "你"),
            LyricWord(startMs = 1_000L, durationMs = 0L, text = "好"),
        )
        assertEquals(listOf(1_000L, NEXT_LINE_MS), lyricWordEnds(words, NEXT_LINE_MS))
    }

    @Test
    fun `填充比例由零到满`() {
        val ends = lyricWordEnds(gappedWords, NEXT_LINE_MS)
        assertEquals(0f, lyricWordFillFraction(gappedWords, ends, 0L), EPS)
        assertEquals(1f, lyricWordFillFraction(gappedWords, ends, 6_000L), EPS)
        // 播放结束后比例不越界
        assertEquals(1f, lyricWordFillFraction(gappedWords, ends, 30_000L), EPS)
    }

    @Test
    fun `填充比例在当前字内推进`() {
        val ends = lyricWordEnds(gappedWords, NEXT_LINE_MS)
        // 首词唱到一半：4 个字中点亮 0.5 个
        assertEquals(0.125f, lyricWordFillFraction(gappedWords, ends, 500L), EPS)
        // 第三词半程：前两词整计加 0.5
        assertEquals(0.625f, lyricWordFillFraction(gappedWords, ends, 4_500L), EPS)
    }

    @Test
    fun `字间空隙时填充比例保持`() {
        val ends = lyricWordEnds(gappedWords, NEXT_LINE_MS)
        val atGapStart = lyricWordFillFraction(gappedWords, ends, 1_500L)
        val atGapEnd = lyricWordFillFraction(gappedWords, ends, 1_999L)
        assertEquals(0.25f, atGapStart, EPS)
        assertEquals(atGapStart, atGapEnd, EPS)
    }

    @Test
    fun `整行内填充比例不回退`() {
        val ends = lyricWordEnds(gappedWords, NEXT_LINE_MS)
        var previous = -1f
        for (positionMs in 0L..7_000L step 50L) {
            val fraction = lyricWordFillFraction(gappedWords, ends, positionMs)
            assertTrue("位置 $positionMs ms 点亮比例回落", fraction >= previous)
            previous = fraction
        }
    }

    @Test
    fun `零长度字既不跳过也不除零`() {
        // 零时长区间撑到 1ms，防 NaN
        val word = listOf(LyricWord(startMs = 1_000L, durationMs = 0L, text = "啊"))
        val ends = lyricWordEnds(word, 1_000L)
        assertEquals(0f, lyricWordFillFraction(word, ends, 1_000L), EPS)
        assertEquals(1f, lyricWordFillFraction(word, ends, 1_001L), EPS)
    }

    @Test
    fun `无文本的字不产生填充`() {
        assertEquals(0f, lyricWordFillFraction(emptyList(), emptyList(), 5_000L), EPS)
        val blank = listOf(LyricWord(startMs = 0L, durationMs = 1_000L, text = ""))
        assertEquals(0f, lyricWordFillFraction(blank, lyricWordEnds(blank, NEXT_LINE_MS), 500L), EPS)
    }

    @Test
    fun `默认字尾不带收尾提前量`() {
        // 不传提前量时原样保留
        assertEquals(listOf(1_000L, 2_000L, 5_000L), lyricWordEnds(tailReachingWords, TAIL_LINE_END_MS))
    }

    @Test
    fun `收尾提前量为滚动过渡留出时间`() {
        val plainLine = LyricLine(timeMs = 0L, text = "歌词")
        // 行长为两倍过渡时，提前一个过渡
        assertEquals(400L, lyricEndLeadMs(plainLine, 5_000L))
        assertEquals(400L, lyricEndLeadMs(plainLine, 2_000L))
        assertEquals(400L, lyricEndLeadMs(plainLine, 1_000L))
        // 短行余量至多占半行
        assertEquals(250L, lyricEndLeadMs(plainLine, 500L))
        // 行短到没有余量可言时不预留
        assertEquals(0L, lyricEndLeadMs(plainLine, 1L))
    }

    @Test
    fun `收尾提前量始终留出正填充时间`() {
        // 提前量须严格小于行时长
        val line = LyricLine(timeMs = 0L, text = "歌词")
        for (duration in 1L..10_000L) {
            assertTrue("行时长 $duration ms 的提前量吃光了点亮时长", lyricEndLeadMs(line, duration) < duration)
        }
    }

    @Test
    fun `整行收尾提前量同样缩短末字`() {
        // 行级提前量直接喂给末词
        val line = LyricLine(timeMs = 0L, text = "你好世", words = tailReachingWords)
        val ends = lyricWordEnds(
            tailReachingWords,
            TAIL_LINE_END_MS,
            tailLeadMs = lyricEndLeadMs(line, TAIL_LINE_END_MS),
        )
        assertEquals(TAIL_LINE_END_MS - 400L, ends.last())
        assertEquals(1f, lyricWordFillFraction(tailReachingWords, ends, TAIL_LINE_END_MS - 400L), EPS)
    }

    @Test
    fun `只给起点时末字按自身节奏而非行尾`() {
        // 只给起点时末字按平均字时长收尾
        val words = listOf(
            LyricWord(startMs = 0L, durationMs = 0L, text = "你"),
            LyricWord(startMs = 1_000L, durationMs = 0L, text = "好"),
            LyricWord(startMs = 2_000L, durationMs = 0L, text = "世"),
            LyricWord(startMs = 3_000L, durationMs = 0L, text = "界"),
        )
        val line = LyricLine(timeMs = 0L, text = "你好世界", words = words)
        val ends = lyricWordEnds(
            words,
            NEXT_LINE_MS,
            tailLeadMs = lyricEndLeadMs(line, NEXT_LINE_MS),
        )
        // 1s×1.2，末字 4.2s 收尾
        assertEquals(4_200L, ends.last())
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_200L), ends)
    }

    @Test
    fun `显式末字结束早于截止时保留原值`() {
        // 显式终点早于余量时保持原值
        val words = listOf(
            LyricWord(startMs = 0L, durationMs = 1_000L, text = "你"),
            LyricWord(startMs = 1_000L, durationMs = 1_000L, text = "好"),
        )
        val line = LyricLine(timeMs = 0L, text = "你好", words = words)
        val ends = lyricWordEnds(
            words,
            NEXT_LINE_MS,
            tailLeadMs = lyricEndLeadMs(line, NEXT_LINE_MS),
        )
        assertEquals(listOf(1_000L, 2_000L), ends)
    }

    @Test
    fun `尾部提前量让末字早于行尾结束`() {
        val ends = lyricWordEnds(tailReachingWords, TAIL_LINE_END_MS, tailLeadMs = 400L)
        assertEquals(listOf(1_000L, 2_000L, 4_600L), ends)
        // 提前 400ms 点亮，切行不闪半字
        assertEquals(1f, lyricWordFillFraction(tailReachingWords, ends, 4_600L), EPS)
        // 其余词的起止时间不受提前量影响
        assertEquals(
            lyricWordEnds(tailReachingWords, TAIL_LINE_END_MS).dropLast(1),
            ends.dropLast(1),
        )
    }

    @Test
    fun `尾部提前量作用于末字的回落结束时间`() {
        // 以下一行起点收尾，也须提前点亮
        val words = listOf(
            LyricWord(startMs = 0L, durationMs = 0L, text = "你"),
            LyricWord(startMs = 3_000L, durationMs = 0L, text = "好"),
        )
        assertEquals(
            listOf(3_000L, TAIL_LINE_END_MS - 400L),
            lyricWordEnds(words, TAIL_LINE_END_MS, tailLeadMs = 400L),
        )
    }

    @Test
    fun `末字没有余量时丢弃尾部提前量`() {
        // 贴行尾起唱时保留原始终点
        val words = listOf(
            LyricWord(startMs = 0L, durationMs = 4_800L, text = "啊"),
            LyricWord(startMs = 4_900L, durationMs = 100L, text = "呀"),
        )
        assertEquals(
            listOf(4_800L, TAIL_LINE_END_MS),
            lyricWordEnds(words, TAIL_LINE_END_MS, tailLeadMs = 400L),
        )
    }

    @Test
    fun `尾部提前量不会让结束早于开始`() {
        // 终点不得早于起点
        val words = tailReachingWords
        for (lead in 0L..TAIL_LINE_END_MS) {
            val ends = lyricWordEnds(words, TAIL_LINE_END_MS, tailLeadMs = lead)
            ends.forEachIndexed { index, end ->
                assertTrue("提前量 $lead ms 下第 $index 个词终点早于起点", end >= words[index].startMs)
            }
        }
    }
}
