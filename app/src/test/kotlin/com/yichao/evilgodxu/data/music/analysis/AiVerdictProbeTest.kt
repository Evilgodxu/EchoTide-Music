package com.yichao.evilgodxu.data.music.analysis

import com.yichao.evilgodxu.data.music.model.MusicTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 以真实音频的平均功率谱复核两路判定，覆盖三种输入：
 *  1. 整曲摘要——[SpectrogramDecoder] 喂给 [SpectralDecoder.Accumulator] 的口径；
 *  2. 分段摘要——[SpectralDecoder.decodeTrack] 三段探测窗的口径，曲库分析与 AI 歌单走这条；
 *  3. 死区加抖动后的整曲摘要——设备端浮点累加与解码残余会让截止上方的死区带起伏，
 *     用于守住截止估计不被逐 bin 抖动带偏。
 *
 * 数据由离线 Welch 分析（4096 点 FFT、Hann 窗、50% 重叠）导出，与运行时口径一致，
 * 目的是把判定逻辑与设备端解码链路分开验证。
 */
class AiVerdictProbeTest {

    private fun loadSummary(resource: String): SpectralDecoder.DecodeSummary {
        val lines = javaClass.classLoader!!
            .getResourceAsStream(resource)!!
            .bufferedReader()
            .readLines()
        val raw = Base64.getDecoder().decode(lines[3].trim())
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val powerSum = FloatArray(raw.size / 4) { buffer.float }
        return SpectralDecoder.DecodeSummary(
            powerSum = powerSum,
            blocks = lines[0].trim().toInt(),
            sampleRate = 48_000,
            channels = 2,
            stereoCorrelation = lines[1].trim().toFloat(),
            stereoCorrSamples = lines[2].trim().toLong(),
        )
    }

    private fun localFlacTrack() = MusicTrack(
        id = 1L,
        path = "/storage/emulated/0/Music/Let Me Go(共创版) - 罐装毕加索.flac",
        audioUri = "",
        title = "Let Me Go",
        artist = "罐装毕加索",
        duration = 124_444L,
        albumId = 0L,
    )

    private fun assertBothVerdictsHit(summary: SpectralDecoder.DecodeSummary, label: String) {
        val fake = FakeLosslessAnalyzer.verdictFromSummary(summary)
        val ai = AiMusicAnalyzer.verdictFromSummary(localFlacTrack(), summary)
        println("[$label] powerSum=${summary.powerSum.size} blocks=${summary.blocks} " +
            "corr=${summary.stereoCorrelation} -> 音质异常=$fake AI=$ai")
        assertTrue("[$label] 音质异常应命中（AI 补充证据路径的前置条件）", fake)
        assertTrue("[$label] AI 合成应命中", ai)
    }

    @Test
    fun wholeTrackSummaryIsFlagged() =
        assertBothVerdictsHit(loadSummary("ai_verdict_summary.txt"), "整曲")

    @Test
    fun segmentedSummaryIsFlagged() =
        assertBothVerdictsHit(loadSummary("ai_verdict_summary_segmented.txt"), "分段")

    @Test
    fun wholeTrackSummarySurvivesDeadZoneRipple() {
        val base = loadSummary("ai_verdict_summary.txt")
        // 截止上方（约 18.8kHz 起）叠加 ±3.5dB 随机起伏：实测该量级已足以让逐 bin 的
        // 截止估计跳到奈奎斯特附近，带宽平均的估计必须不受其影响
        val random = Random(20260927L)
        val powerSum = base.powerSum.copyOf()
        val deadFromBin = (18_800f / (48_000f / SpectralDecoder.FFT_SIZE)).toInt()
        for (i in deadFromBin until powerSum.size) {
            val rippleDb = (random.nextFloat() * 7f) - 3.5f
            powerSum[i] *= Math.pow(10.0, rippleDb / 10.0).toFloat()
        }
        val ripple = SpectralDecoder.DecodeSummary(
            powerSum = powerSum,
            blocks = base.blocks,
            sampleRate = base.sampleRate,
            channels = base.channels,
            stereoCorrelation = base.stereoCorrelation,
            stereoCorrSamples = base.stereoCorrSamples,
        )
        assertBothVerdictsHit(ripple, "整曲+死区抖动")
    }
}
