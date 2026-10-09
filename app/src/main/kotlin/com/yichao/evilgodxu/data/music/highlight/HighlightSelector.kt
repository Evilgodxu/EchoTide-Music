package com.yichao.evilgodxu.data.music.highlight

import com.yichao.evilgodxu.data.music.analysis.EnergyEnvelope
import com.yichao.evilgodxu.data.music.model.LyricLine

/**
 * 副歌决策：把歌词侧的候选与音频侧的能量包络合成一个片段。
 *
 * 两路证据分工明确，不互相取代：
 *
 * - **歌词重复**给出结构与时间边界（[HighlightLocator]），是主判据 —— 副歌是整曲重复最多的段落，
 *   且只有它能精确到「哪一句」。
 * - **音频能量**给出强度（[EnergyEnvelope]），是补充判据 —— 用于在多个并列候选之间择优
 *   （主歌也可能重复，但副歌通常更响、更亮），以及在歌词给不出候选时独立定位高潮
 *   （歌词无时间轴、或副歌不重复的叙事类歌曲）。
 *
 * 全部为**纯函数**：不读盘、不解码、无副作用，输出只由入参决定，故可直接单元测试。
 * 音频解码与调用时机由 [HighlightScanner] 掌握（只在歌词不足以决断时才解码）。
 */
internal object HighlightSelector {

    // 合成权重：歌词是主判据，音频只在同分候选间形成可见的区分度
    private const val LYRIC_WEIGHT = 0.6
    private const val AUDIO_WEIGHT = 0.4

    // 音频兜底窗口的标称时长，取 30–45 秒的中值；随后按端点吸附微调
    private const val TARGET_AUDIO_MS = 35_000L

    // 起音跃升在兜底得分中的权重：副歌多半在能量陡升处进入
    private const val ONSET_WEIGHT = 0.35

    // 起音跃升的比较跨度（前后各约 0.5 秒）
    private const val ONSET_SPAN_FRAMES = 5

    // 端点吸附在目标位置附近搜索的范围（约 ±2 秒）
    private const val SNAP_RADIUS_FRAMES = 20

    /**
     * 选出最终片段。
     *
     * @param candidates 歌词侧候选（按可信度降序），可为空
     * @param envelope 音频能量包络；未解码时为 null，此时退化为纯歌词判据
     * @param lines 曲目歌词，用于把音频兜底区间吸附到歌词行边界（无时间轴时不吸附）
     * @return 片段；实在无法定位时返回 null
     */
    fun select(
        candidates: List<HighlightLocator.Candidate>,
        envelope: EnergyEnvelope?,
        lines: List<LyricLine>,
    ): Highlight? {
        if (candidates.isEmpty()) {
            // 歌词给不出候选：音频能量兜底。有歌词时间轴就吸附到行边界，保证首句完整、末句唱完
            val available = envelope ?: return null
            val raw = audioFallback(available) ?: return null
            return HighlightLocator.segmentAt(lines, raw.startMs, available.durationMs) ?: raw
        }
        if (envelope == null) return candidates.first().toHighlight()
        val strongest = candidates.first().score.takeIf { it > 0.0 } ?: 1.0
        val loudest = candidates
            .maxOf { meanLoudness(envelope, it.startMs, it.endMs) }
            .coerceAtLeast(1e-3)
        val best = candidates.maxByOrNull { candidate ->
            val lyric = candidate.score / strongest
            val audio = meanLoudness(envelope, candidate.startMs, candidate.endMs) / loudest
            LYRIC_WEIGHT * lyric + AUDIO_WEIGHT * audio
        } ?: return candidates.first().toHighlight()
        return best.toHighlight()
    }

    /**
     * 仅凭能量包络定位高潮：最响且伴随明显能量跃升的一段。
     *
     * 这是 RefraiD / pychorus 一类副歌检测在剥离重复性判据后的**强度近似** —— 流行歌的副歌
     * 通常是全曲最响、配器最密的段落。逐起点滑动固定长度窗口，取「窗口平均响度 + 起音跃升」
     * 最高者，再把首尾吸附到邻近的响度谷（乐句间的安静处），使切点落在乐句边界而非半句上。
     *
     * 时长受 30–45 秒约束；整曲短于下限、或无法解出有效帧时返回 null。
     */
    fun audioFallback(envelope: EnergyEnvelope): Highlight? {
        val frameMs = envelope.frameMs
        if (frameMs <= 0L) return null
        val minFrames = (HighlightLocator.MIN_SEGMENT_MS / frameMs).toInt()
        val maxFrames = (HighlightLocator.MAX_SEGMENT_MS / frameMs).toInt()
        if (envelope.frameCount <= minFrames) return null

        val targetFrames = (TARGET_AUDIO_MS / frameMs).toInt().coerceIn(minFrames, maxFrames)
        val lastStart = envelope.frameCount - targetFrames
        if (lastStart < 0) return null

        var bestStart = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (start in 0..lastStart) {
            val loudness = meanRange(envelope.loudness, start, start + targetFrames)
            val onset = onsetBoost(envelope.loudness, start)
            val score = loudness + ONSET_WEIGHT * onset
            if (score > bestScore) {
                bestScore = score
                bestStart = start
            }
        }
        if (bestStart < 0) return null

        val startFrame = snapToValley(envelope.loudness, bestStart)
            .coerceAtMost(envelope.frameCount - minFrames)
        val lowerEnd = startFrame + minFrames
        val upperEnd = minOf(startFrame + maxFrames, envelope.frameCount)
        if (upperEnd < lowerEnd) return null
        val endFrame = snapToValley(envelope.loudness, bestStart + targetFrames)
            .coerceIn(lowerEnd, upperEnd)
        val startMs = startFrame.toLong() * frameMs
        val endMs = endFrame.toLong() * frameMs
        if (endMs - startMs < HighlightLocator.MIN_SEGMENT_MS) return null
        return Highlight(startMs, endMs)
    }

    // 区间平均响度，用于比较候选之间的强度
    private fun meanLoudness(envelope: EnergyEnvelope, startMs: Long, endMs: Long): Double {
        val from = envelope.frameAt(startMs)
        val to = envelope.frameAt(endMs).coerceAtLeast(from + 1)
        return meanRange(envelope.loudness, from, to)
    }

    // 均值采样：区间越界一律收窄到有效范围，空区间返回 0
    private fun meanRange(values: FloatArray, from: Int, to: Int): Double {
        val start = from.coerceIn(0, values.size)
        val end = to.coerceIn(0, values.size)
        if (end <= start) return 0.0
        var sum = 0.0
        for (i in start until end) sum += values[i]
        return sum / (end - start)
    }

    // 起音跃升：窗口起点的「后一小段」比「前一小段」响多少，取 0..1
    private fun onsetBoost(loudness: FloatArray, index: Int): Double {
        val before = meanRange(loudness, index - ONSET_SPAN_FRAMES, index)
        val after = meanRange(loudness, index, index + ONSET_SPAN_FRAMES)
        return (after - before).coerceIn(0.0, 1.0)
    }

    // 把位置吸附到附近响度最低的一帧（乐句之间的安静处）
    private fun snapToValley(values: FloatArray, index: Int): Int {
        if (values.isEmpty()) return 0
        val center = index.coerceIn(0, values.size - 1)
        val from = (center - SNAP_RADIUS_FRAMES).coerceAtLeast(0)
        val to = (center + SNAP_RADIUS_FRAMES).coerceAtMost(values.size - 1)
        var best = center
        for (i in from..to) if (values[i] < values[best]) best = i
        return best
    }
}
