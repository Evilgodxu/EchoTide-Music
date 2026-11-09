package com.yichao.evilgodxu.ui.component.player

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐字歌词时序复核：词终点兜底、末词行尾提前量与行内点亮比例。
 *
 * 迷你条与完整播放器的歌词点亮共用这组换算（含行尾提前量），比例一旦与词时序脱钩，
 * 点亮边缘就会落后或超前于实际演唱位置 —— 词长短不一或词间留有空隙时，按行时长均分必然错位。
 */
class LyricLineTimingTest {

    private companion object {
        const val EPS = 1e-4f

        // 末词没有下一行时的行终点兜底值
        const val NEXT_LINE_MS = 9_000L

        // 末词延伸到句尾时该行的行尾（下一行起点）
        const val TAIL_LINE_END_MS = 5_000L
    }

    // 四字歌词：每词各占 1s 且词间留 1s 空隙，用于验证空隙期间点亮边缘停住
    private val gappedWords = listOf(
        LyricWord(startMs = 0L, durationMs = 1_000L, text = "你"),
        LyricWord(startMs = 2_000L, durationMs = 1_000L, text = "好"),
        LyricWord(startMs = 4_000L, durationMs = 1_000L, text = "世"),
        LyricWord(startMs = 5_000L, durationMs = 1_000L, text = "界"),
    )

    // 末词被逐字时序延伸到句尾：终点 5s 恰为下一行起点，用于验证行尾提前量
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
        // 增强 LRC 只给字起点，末词以下一行起点收尾，否则末词没有可用区间
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
        // 演唱结束后继续播放既不越界也不回落
        assertEquals(1f, lyricWordFillFraction(gappedWords, ends, 30_000L), EPS)
    }

    @Test
    fun `填充比例在当前字内推进`() {
        val ends = lyricWordEnds(gappedWords, NEXT_LINE_MS)
        // 首词唱到一半：4 个字中点亮 0.5 个
        assertEquals(0.125f, lyricWordFillFraction(gappedWords, ends, 500L), EPS)
        // 第三词唱到一半：前两词整词计入，再加 0.5 个
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
        // 词时长为 0 且没有下一行兜底时区间被撑到 1ms：不能产生 NaN，也不能一开唱就整词点亮
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
        // 不传提前量时逐字时序原样保留：调用方按需自行决定是否留行尾余量
        assertEquals(listOf(1_000L, 2_000L, 5_000L), lyricWordEnds(tailReachingWords, TAIL_LINE_END_MS))
    }

    @Test
    fun `收尾提前量为滚动过渡留出时间`() {
        val plainLine = LyricLine(timeMs = 0L, text = "歌词")
        // 行不短于两倍过渡时长：整行在滚动开始前一个过渡时长完成点亮
        assertEquals(400L, lyricEndLeadMs(plainLine, 5_000L))
        assertEquals(400L, lyricEndLeadMs(plainLine, 2_000L))
        assertEquals(400L, lyricEndLeadMs(plainLine, 1_000L))
        // 短于两倍过渡时长：余量至多占半行，整行仍有完整的点亮时间
        assertEquals(250L, lyricEndLeadMs(plainLine, 500L))
        // 行短到没有余量可言时不预留
        assertEquals(0L, lyricEndLeadMs(plainLine, 1L))
    }

    @Test
    fun `收尾提前量始终留出正填充时间`() {
        // 按字/按词均分都要除以「行时长 − 提前量」：提前量必须严格小于行时长
        val line = LyricLine(timeMs = 0L, text = "歌词")
        for (duration in 1L..10_000L) {
            assertTrue("行时长 $duration ms 的提前量吃光了点亮时长", lyricEndLeadMs(line, duration) < duration)
        }
    }

    @Test
    fun `整行收尾提前量同样缩短末字`() {
        // 各处歌词渲染同一口径：行级提前量直接喂给逐字末词
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
        // 增强 LRC 只给字起点：末字按前面各字的平均字时长估出收尾，不再被拉长到行尾
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
        // 平均字时长 1s、系数 1.2：末字在 3s 起唱后 1.2s 收尾，远早于 9s 的下一行起点
        assertEquals(4_200L, ends.last())
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_200L), ends)
    }

    @Test
    fun `显式末字结束早于截止时保留原值`() {
        // 末词自身时长已给出且早于滚动余量：保持原终点，不向行尾拉长也不提前截断
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
        // 末词在切行前 400ms 已整行点亮：切行那一刻不再有半个字刚亮起
        assertEquals(1f, lyricWordFillFraction(tailReachingWords, ends, 4_600L), EPS)
        // 其余词的起止时间不受提前量影响
        assertEquals(
            lyricWordEnds(tailReachingWords, TAIL_LINE_END_MS).dropLast(1),
            ends.dropLast(1),
        )
    }

    @Test
    fun `尾部提前量作用于末字的回落结束时间`() {
        // 增强 LRC 的末词以下一行起点收尾：同样要在切行前完成点亮
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
        // 末词贴着行尾起唱：挤出提前量会把它压成瞬间点亮，此时保留原始终点
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
        // 提前量大于行时长等极端输入下终点仍不早于起点，保证逐词区间有效
        val words = tailReachingWords
        for (lead in 0L..TAIL_LINE_END_MS) {
            val ends = lyricWordEnds(words, TAIL_LINE_END_MS, tailLeadMs = lead)
            ends.forEachIndexed { index, end ->
                assertTrue("提前量 $lead ms 下第 $index 个词终点早于起点", end >= words[index].startMs)
            }
        }
    }
}
