package com.yichao.evilgodxu.ui.component.player

import com.yichao.evilgodxu.data.music.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐字歌词时序复核：词终点兜底与行内点亮比例。
 *
 * 迷你条与完整播放器的歌词点亮共用这组换算，比例一旦与词时序脱钩，点亮边缘就会
 * 落后或超前于实际演唱位置 —— 词长短不一或词间留有空隙时，按行时长均分必然错位。
 */
class LyricLineTimingTest {

    private companion object {
        const val EPS = 1e-4f

        // 末词没有下一行时的行终点兜底值
        const val NEXT_LINE_MS = 9_000L
    }

    // 四字歌词：每词各占 1s 且词间留 1s 空隙，用于验证空隙期间点亮边缘停住
    private val gappedWords = listOf(
        LyricWord(startMs = 0L, durationMs = 1_000L, text = "你"),
        LyricWord(startMs = 2_000L, durationMs = 1_000L, text = "好"),
        LyricWord(startMs = 4_000L, durationMs = 1_000L, text = "世"),
        LyricWord(startMs = 5_000L, durationMs = 1_000L, text = "界"),
    )

    @Test
    fun wordEndsPreferOwnDuration() {
        assertEquals(listOf(1_000L, 3_000L, 5_000L, 6_000L), lyricWordEnds(gappedWords, NEXT_LINE_MS))
    }

    @Test
    fun zeroDurationWordsEndAtTheNextWordStart() {
        // 增强 LRC 只给字起点，末词以下一行起点收尾，否则末词没有可用区间
        val words = listOf(
            LyricWord(startMs = 0L, durationMs = 0L, text = "你"),
            LyricWord(startMs = 1_000L, durationMs = 0L, text = "好"),
        )
        assertEquals(listOf(1_000L, NEXT_LINE_MS), lyricWordEnds(words, NEXT_LINE_MS))
    }

    @Test
    fun fillFractionStartsEmptyAndEndsFull() {
        val ends = lyricWordEnds(gappedWords, NEXT_LINE_MS)
        assertEquals(0f, lyricWordFillFraction(gappedWords, ends, 0L), EPS)
        assertEquals(1f, lyricWordFillFraction(gappedWords, ends, 6_000L), EPS)
        // 演唱结束后继续播放既不越界也不回落
        assertEquals(1f, lyricWordFillFraction(gappedWords, ends, 30_000L), EPS)
    }

    @Test
    fun fillFractionAdvancesWithinTheCurrentWord() {
        val ends = lyricWordEnds(gappedWords, NEXT_LINE_MS)
        // 首词唱到一半：4 个字中点亮 0.5 个
        assertEquals(0.125f, lyricWordFillFraction(gappedWords, ends, 500L), EPS)
        // 第三词唱到一半：前两词整词计入，再加 0.5 个
        assertEquals(0.625f, lyricWordFillFraction(gappedWords, ends, 4_500L), EPS)
    }

    @Test
    fun fillFractionHoldsDuringWordGaps() {
        val ends = lyricWordEnds(gappedWords, NEXT_LINE_MS)
        val atGapStart = lyricWordFillFraction(gappedWords, ends, 1_500L)
        val atGapEnd = lyricWordFillFraction(gappedWords, ends, 1_999L)
        assertEquals(0.25f, atGapStart, EPS)
        assertEquals(atGapStart, atGapEnd, EPS)
    }

    @Test
    fun fillFractionNeverDecreasesAcrossTheLine() {
        val ends = lyricWordEnds(gappedWords, NEXT_LINE_MS)
        var previous = -1f
        for (positionMs in 0L..7_000L step 50L) {
            val fraction = lyricWordFillFraction(gappedWords, ends, positionMs)
            assertTrue("位置 $positionMs ms 点亮比例回落", fraction >= previous)
            previous = fraction
        }
    }

    @Test
    fun zeroLengthWordIsNeitherSkippedNorDividedByZero() {
        // 词时长为 0 且没有下一行兜底时区间被撑到 1ms：不能产生 NaN，也不能一开唱就整词点亮
        val word = listOf(LyricWord(startMs = 1_000L, durationMs = 0L, text = "啊"))
        val ends = lyricWordEnds(word, 1_000L)
        assertEquals(0f, lyricWordFillFraction(word, ends, 1_000L), EPS)
        assertEquals(1f, lyricWordFillFraction(word, ends, 1_001L), EPS)
    }

    @Test
    fun wordsWithoutTextYieldNoFill() {
        assertEquals(0f, lyricWordFillFraction(emptyList(), emptyList(), 5_000L), EPS)
        val blank = listOf(LyricWord(startMs = 0L, durationMs = 1_000L, text = ""))
        assertEquals(0f, lyricWordFillFraction(blank, lyricWordEnds(blank, NEXT_LINE_MS), 500L), EPS)
    }
}
