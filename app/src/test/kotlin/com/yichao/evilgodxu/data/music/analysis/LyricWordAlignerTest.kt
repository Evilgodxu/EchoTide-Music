package com.yichao.evilgodxu.data.music.analysis

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐字对齐引擎复核。
 *
 * 引擎的结果是直接写回歌词的逐字时间轴，故「不确定时放弃」比「随便给一套数字」重要：
 * 静音、纯标点、音频过短都必须返回空，由上层保持行级歌词。
 */
class LyricWordAlignerTest {

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val SEGMENT_MS = 2_000L
        // 20ms 网格 + 行时间戳锚定误差，合成信号下按两三个网格的余量判定
        const val TOLERANCE_MS = 250L
    }

    private val aligner = LyricWordAligner()

    @Test
    fun silenceYieldsNoWordTimings() {
        // 无起音可依：写出逐字时间只会得到一套必然错误的高亮
        val segment = PcmSegment(ShortArray(SAMPLE_RATE * 2), ShortArray(SAMPLE_RATE * 2), 0L)
        assertTrue(aligner.alignLine(segment, 200L, 1_200L, "窗外的麻雀").isEmpty())
    }

    @Test
    fun punctuationOnlyLineHasNothingToAlign() {
        val segment = bursts(intArrayOf(200, 400, 600, 800, 1_000))
        assertTrue(aligner.alignLine(segment, 200L, 1_200L, "……").isEmpty())
    }

    @Test
    fun segmentShorterThanOneAnalysisWindowIsRejected() {
        val segment = PcmSegment(ShortArray(256), ShortArray(256), 0L)
        assertTrue(aligner.alignLine(segment, 0L, 1_000L, "窗外的麻雀").isEmpty())
    }

    @Test
    fun syllableBurstsAreAlignedInSingingOrder() {
        val text = "窗外的麻雀"
        val onsets = intArrayOf(200, 400, 600, 800, 1_000)
        val words = aligner.alignLine(bursts(onsets), 200L, 1_200L, text)

        assertEquals("未按字数切出逐字单元：${words.map { it.text }}", text.length, words.size)
        assertEquals("逐字文本拼接后须还原整行：$words", text, words.joinToString("") { it.text })
        val starts = words.map { it.startMs }
        assertEquals("逐字起点须单调推进：$starts", starts.sorted(), starts)

        onsets.forEachIndexed { index, expected ->
            val actual = words[index].startMs
            assertTrue(
                "第 $index 字起点 $actual 偏离真值 $expected 过远（全部起点 $starts）",
                abs(actual - expected) <= TOLERANCE_MS,
            )
        }
    }

    /** 合成音节串：在给定毫秒位置各放一段宽带噪声爆发，模拟被伴奏包住的起音 */
    private fun bursts(onsetsMs: IntArray): PcmSegment {
        val length = (SAMPLE_RATE * SEGMENT_MS / 1000).toInt()
        val left = ShortArray(length)
        val right = ShortArray(length)
        val random = Random(7)
        onsetsMs.forEach { onset ->
            val from = onset * SAMPLE_RATE / 1000
            val to = minOf(length, from + 120 * SAMPLE_RATE / 1000)
            for (i in from until to) {
                // 前段陡起、后段缓收，使起音落在爆发点上
                val envelope = 1.0 - (i - from).toDouble() / (to - from)
                val sample = (random.nextDouble() * 2 - 1) * envelope * 0.6 * Short.MAX_VALUE
                left[i] = sample.toInt().toShort()
                right[i] = sample.toInt().toShort()
            }
        }
        return PcmSegment(left, right, 0L)
    }
}
