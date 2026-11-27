package com.yichao.evilgodxu.data.music.highlight

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.recommend.LyricFeatures

/**
 * 副歌片段，绝对时间半开区间 [startMs, endMs)
 * 与歌词轴同坐标系；裁剪后进度以片段为原点，偏移只在播放层换算
 */
data class Highlight(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * 副歌定位：纯歌词分析，不碰音频
 * 重复结构筛候选，再按位置/时长/密度/停顿/结尾打分
 * 取最后一遍完整副歌，行数多者优先
 */
internal object HighlightLocator {

    // 目标时长 30–45s，逐行补到下限
    const val MIN_SEGMENT_MS = 30_000L
    const val MAX_SEGMENT_MS = 45_000L

    // 至少重复 2 次才算候选
    private const val MIN_REPEAT = 2

    // 窗口 2–6 行；单行不成段，过长会超出时长上限
    private const val MIN_WINDOW_LINES = 2
    private const val MAX_WINDOW_LINES = 6

    // 带轴歌词少于 6 行不定位
    private const val MIN_TIMED_LINES = 6

    // 候选上限
    private const val MAX_CANDIDATES = 4

    // 重叠超 50% 视为同段，只留高分
    private const val OVERLAP_REJECT_RATIO = 0.5

    // 五项权重，和为 1
    private const val POSITION_WEIGHT = 0.15
    private const val DURATION_WEIGHT = 0.20
    private const val DENSITY_WEIGHT = 0.30
    private const val PAUSE_WEIGHT = 0.15
    private const val ENDING_WEIGHT = 0.20

    // 密度：每秒 0.6 行满分，0.15 行以下为 0
    private const val DENSITY_FULL_PER_SEC = 0.6
    private const val DENSITY_ZERO_PER_SEC = 0.15

    // 停顿：≤2s 满分，≥10s 记 0
    private const val PAUSE_FULL_MS = 2_000.0
    private const val PAUSE_ZERO_MS = 10_000.0

    // 行数降序，再得分降序，再取靠后
    private val CANDIDATE_ORDER: Comparator<Candidate> =
        compareByDescending<Candidate> { it.lineCount }
            .thenByDescending { it.score }
            .thenByDescending { it.startMs }

    /** 副歌候选：重复行块收成的片段与得分 */
    data class Candidate(
        val startMs: Long,
        val endMs: Long,
        val repeats: Int,
        val lineCount: Int,
        val score: Double,
    ) {
        val durationMs: Long get() = endMs - startMs

        fun toHighlight(): Highlight = Highlight(startMs, endMs)
    }

    /** 时间轴是否可用，用于区分无语料与确无副歌 */
    fun hasUsableTimeline(lines: List<LyricLine>): Boolean = timedLyricLines(lines).size >= MIN_WINDOW_LINES

    /** 返回按优先级降序的副歌候选；时间轴无效返回空 */
    fun candidates(lines: List<LyricLine>, durationMs: Long): List<Candidate> {
        // 时长不足 15s 直接无候选
        if (durationMs < MIN_SEGMENT_MS / 2) return emptyList()

        val timed = timedLyricLines(lines)
        if (timed.size < MIN_TIMED_LINES) return emptyList()
        val texts = timed.map { normalize(it.text) }
        val maxWidth = minOf(MAX_WINDOW_LINES, texts.size / MIN_REPEAT)
        if (maxWidth < MIN_WINDOW_LINES) return emptyList()

        val raw = ArrayList<Candidate>()
        for (width in MIN_WINDOW_LINES..maxWidth) {
            // 键为行拼接，同键下标即各次出现
            val occurrences = HashMap<String, MutableList<Int>>()
            for (start in 0..texts.size - width) {
                occurrences.getOrPut(buildKey(texts, start, width)) { mutableListOf() } += start
            }
            occurrences.values.forEach { starts ->
                if (starts.size < MIN_REPEAT) return@forEach
                buildCandidate(timed, starts, width, durationMs)?.let { raw += it }
            }
        }
        if (raw.isEmpty()) return emptyList()

        // 宽窗覆盖窄窗，重叠过多者丢弃
        val kept = ArrayList<Candidate>()
        raw.sortedWith(CANDIDATE_ORDER).forEach { candidate ->
            if (kept.none { overlapRatio(it, candidate) >= OVERLAP_REJECT_RATIO }) kept += candidate
        }
        return kept.take(MAX_CANDIDATES)
    }

    /**
     * 把行块收成片段，只取最后一遍完整出现
     * 收尾停在整行之后，末遍过短则退回上一遍
     */
    private fun buildCandidate(
        timed: List<LyricLine>,
        starts: List<Int>,
        width: Int,
        durationMs: Long,
    ): Candidate? {
        for (index in starts.indices.reversed()) {
            val startIndex = starts[index]
            val endIndex = endLineIndex(timed, startIndex, width, durationMs) ?: continue
            val startMs = timed[startIndex].timeMs
            val endMs = lineEndMs(timed, endIndex, durationMs)
                .coerceAtMost(durationMs)
                .coerceAtMost(startMs + MAX_SEGMENT_MS)
            if (endMs - startMs < MIN_SEGMENT_MS / 2) continue
            return Candidate(
                startMs = startMs,
                endMs = endMs,
                repeats = starts.size,
                lineCount = endIndex - startIndex + 1,
                score = scoreOf(
                    timed = timed,
                    startIndex = startIndex,
                    endIndex = endIndex,
                    firstOccurrenceStart = starts.first(),
                    totalLines = timed.size,
                    durationMs = durationMs,
                    startMs = startMs,
                    endMs = endMs,
                ),
            )
        }
        return null
    }

    /** 收尾：越限回缩，再逐行补到下限 */
    private fun endLineIndex(
        timed: List<LyricLine>,
        startIndex: Int,
        width: Int,
        durationMs: Long,
    ): Int? {
        var lastLine = startIndex + width - 1
        if (lastLine >= timed.size) return null
        val startMs = timed[startIndex].timeMs
        while (lastLine > startIndex &&
            lineEndMs(timed, lastLine, durationMs) - startMs > MAX_SEGMENT_MS
        ) {
            lastLine--
        }
        while (lineEndMs(timed, lastLine, durationMs) - startMs < MIN_SEGMENT_MS &&
            lastLine + 1 < timed.size
        ) {
            if (lineEndMs(timed, lastLine + 1, durationMs) - startMs > MAX_SEGMENT_MS) break
            lastLine++
        }
        return lastLine
    }

    /** 得分 0..1：首现位置、时长、密度、停顿、结尾加权 */
    private fun scoreOf(
        timed: List<LyricLine>,
        startIndex: Int,
        endIndex: Int,
        firstOccurrenceStart: Int,
        totalLines: Int,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
    ): Double {
        val duration = endMs - startMs
        val position = if (totalLines <= 0) 0.0 else firstOccurrenceStart.toDouble() / totalLines
        val durationFit = when {
            duration in MIN_SEGMENT_MS..MAX_SEGMENT_MS -> 1.0
            duration >= MIN_SEGMENT_MS / 2 -> 0.6
            else -> 0.2
        }
        val seconds = (duration / 1000.0).coerceAtLeast(1.0)
        val density = normalize(
            (endIndex - startIndex + 1) / seconds,
            DENSITY_ZERO_PER_SEC,
            DENSITY_FULL_PER_SEC,
        )
        val pause = 1.0 - normalize(
            maxGapMs(timed, startIndex, endIndex).toDouble(),
            PAUSE_FULL_MS,
            PAUSE_ZERO_MS,
        )
        val ending = if (durationMs <= 0L) 0.0 else (endMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
        return position * POSITION_WEIGHT +
            durationFit * DURATION_WEIGHT +
            density * DENSITY_WEIGHT +
            pause * PAUSE_WEIGHT +
            ending * ENDING_WEIGHT
    }

    private fun maxGapMs(timed: List<LyricLine>, from: Int, to: Int): Long {
        var maxGap = 0L
        for (i in from until to) {
            val gap = timed[i + 1].timeMs - timed[i].timeMs
            if (gap > maxGap) maxGap = gap
        }
        return maxGap
    }

    private fun normalize(value: Double, zero: Double, full: Double): Double {
        if (full <= zero) return 0.0
        return ((value - zero) / (full - zero)).coerceIn(0.0, 1.0)
    }

    // 收敛同一段落的重复列举
    private fun overlapRatio(a: Candidate, b: Candidate): Double {
        val overlap = (minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)).coerceAtLeast(0L)
        val shorter = minOf(a.durationMs, b.durationMs).coerceAtLeast(1L)
        return overlap.toDouble() / shorter
    }

    /** 有效时间轴且非制作信息行；同时间戳只取首行 */
    private fun timedLyricLines(lines: List<LyricLine>): List<LyricLine> = lines
        .filter { it.timeMs > 0 && it.text.isNotBlank() }
        .sortedBy { it.timeMs }
        .distinctBy { it.timeMs }
        .filter { LyricFeatures.cleanLyrics(listOf(it.text)).isNotEmpty() }

    // 去空白与大小写，使重复行能对上
    private fun normalize(text: String): String =
        text.trim().lowercase().replace(WHITESPACE, "")

    // 用不会出现的分隔符拼接，避免假重合
    private fun buildKey(texts: List<String>, start: Int, width: Int): String {
        val builder = StringBuilder()
        for (index in start until start + width) {
            if (index > start) builder.append(KEY_SEPARATOR)
            builder.append(texts[index])
        }
        return builder.toString()
    }

    /** 行结束点取后继行起点与逐字终点的较大者，封顶曲末 */
    private fun lineEndMs(timed: List<LyricLine>, lineIndex: Int, durationMs: Long): Long {
        val nextStart = timed.getOrNull(lineIndex + 1)?.timeMs ?: durationMs
        val wordEnd = timed[lineIndex].words.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
        return maxOf(nextStart, wordEnd).coerceAtMost(durationMs)
    }

    private val WHITESPACE = Regex("\\s+")

    // 歌词中不会出现的控制字符
    private const val KEY_SEPARATOR = "\u0001"
}
