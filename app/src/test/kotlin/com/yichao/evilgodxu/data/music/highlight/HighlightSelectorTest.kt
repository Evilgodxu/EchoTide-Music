package com.yichao.evilgodxu.data.music.highlight

import com.yichao.evilgodxu.data.music.analysis.EnergyEnvelope
import com.yichao.evilgodxu.data.music.model.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音频融合决策复核。
 *
 * 决策与兜底都是纯函数，故用构造出的能量包络直接驱动，不涉及解码：
 * 验证「无歌词候选时能量兜底」「多候选时音频择优」「长度约束」三条主干。
 */
class HighlightSelectorTest {

    private val frameMs = 100L

    // 构造能量包络：默认响度 0.15，给定区间抬到 0.9（模拟副歌比主歌更响）
    private fun envelope(frameCount: Int, loud: IntRange? = null): EnergyEnvelope {
        val loudness = FloatArray(frameCount) { if (loud != null && it in loud) 0.9f else 0.15f }
        val brightness = FloatArray(frameCount) { 0.5f }
        return EnergyEnvelope(loudness, brightness, frameMs)
    }

    private fun candidate(startMs: Long, endMs: Long, repeats: Int, score: Double) =
        HighlightLocator.Candidate(startMs, endMs, repeats, score)

    @Test
    fun audioFallbackFindsLoudestRegion() {
        // 240 秒，副歌在 120–156 秒
        val envelope = envelope(2_400, loud = 1_200 until 1_560)
        val highlight = HighlightSelector.audioFallback(envelope)
        assertNotNull("能量包络应能独立定位高潮", highlight)
        highlight!!
        assertTrue("时长应落在 30–45 秒", highlight.durationMs in 30_000L..45_000L)
        assertTrue("应落在最响区间附近", highlight.startMs <= 122_000L)
        assertTrue("应覆盖最响区间末段", highlight.endMs >= 150_000L)
    }

    @Test
    fun audioFallbackReturnsNullForTooShortTrack() {
        // 10 秒的音频容不下 30–45 秒片段
        assertNull(HighlightSelector.audioFallback(envelope(100)))
    }

    @Test
    fun selectWithNoEnvelopeKeepsLyricTopCandidate() {
        val candidates = listOf(
            candidate(20_000L, 50_000L, repeats = 2, score = 10.0),
            candidate(60_000L, 90_000L, repeats = 2, score = 9.0),
        )
        val highlight = HighlightSelector.select(candidates, null, emptyList())
        assertEquals(20_000L, highlight!!.startMs)
    }

    @Test
    fun selectPrefersCandidateThatIsLouderInAudio() {
        // 第二候选歌词得分略低，但音频明显更响（副歌多半更响），应被选中
        val candidates = listOf(
            candidate(20_000L, 50_000L, repeats = 2, score = 10.0),
            candidate(60_000L, 90_000L, repeats = 2, score = 9.0),
        )
        val envelope = envelope(1_200, loud = 600 until 900)
        val highlight = HighlightSelector.select(candidates, envelope, emptyList())
        // 端点会被吸附到邻近的响度谷，故只校验落在更响的那个候选区间内
        assertTrue("应选中音频更响的候选", highlight!!.startMs in 58_000L..61_000L)
    }

    @Test
    fun selectSnapsBoundariesToLoudnessValley() {
        // 副歌 60–90 秒，其前一带有更安静的间隙：起点应吸到间隙里，而非留在骤响的边缘
        val loudness = FloatArray(1_200) { 0.15f }
        for (i in 600 until 900) loudness[i] = 0.9f
        for (i in 570 until 595) loudness[i] = 0.02f
        val envelope = EnergyEnvelope(loudness, FloatArray(1_200) { 0.5f }, frameMs)
        val highlight = HighlightSelector.select(
            listOf(candidate(60_000L, 90_000L, repeats = 2, score = 10.0)),
            envelope,
            emptyList(),
        )
        assertEquals("起点应吸附到安静间隙", 59_400L, highlight!!.startMs)
        assertTrue("时长仍须落在 30–45 秒", highlight.durationMs in 30_000L..45_000L)
    }

    @Test
    fun selectRescuesSegmentFromAudioAndSnapsToLyricLine() {
        // 歌词给不出重复候选：用能量兜底定位，并吸附到最近歌词行，保证首句完整
        val lines = (0..16).map { LyricLine(timeMs = it * 10_000L, text = "行$it") }
        val envelope = envelope(1_700, loud = 1_000 until 1_350)
        val highlight = HighlightSelector.select(emptyList(), envelope, lines)
        assertNotNull("音频兜底应给出片段", highlight)
        highlight!!
        assertEquals("起点应吸附到 100 秒的歌词行", 100_000L, highlight.startMs)
        assertTrue("时长应落在 30–45 秒", highlight.durationMs in 30_000L..45_000L)
    }

    @Test
    fun selectReturnsNullWhenNeitherLyricsNorAudioCanLocate() {
        assertNull(HighlightSelector.select(emptyList(), null, emptyList()))
    }
}
