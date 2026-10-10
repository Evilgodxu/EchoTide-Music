package com.yichao.evilgodxu.data.music.highlight

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.recommend.LyricFeatures

/**
 * 副歌（高潮）片段：播放裁剪所用的绝对时间区间，半开区间 [startMs, endMs)。
 *
 * 时间戳为曲目内的绝对时间，与歌词时间轴同一坐标系 —— 裁剪生效后播放器的进度值以片段为原点，
 * 二者之间需要一次偏移换算，换算只在播放状态层做一处。
 */
data class Highlight(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * 副歌定位：**纯歌词分析**，不依赖音频、不解码，只用歌词文本与时间戳定出高潮片段。
 *
 * 四步：
 *
 * 1. **重复结构**筛出副歌候选 —— 副歌是整曲重复次数最多的段落，在歌词上表现为同一批行反复出现。
 *    只出现一次的行块不成候选。
 * 2. **位置、时长、密度、停顿、结尾权重**给候选打分 —— 首现越晚、时长越贴合 30–45 秒、行越密、
 *    行间停顿越短、越靠近曲末的块越像副歌。
 * 3. **取最后一遍完整副歌** —— 每个候选只收其最后一次出现：末遍副歌通常是全曲落点所在，
 *    且收尾落在歌词行边界上，末句能完整唱完；末遍短到收不出片段时退回上一遍。
 * 4. **不取歌词行数少的片段** —— 候选按片段内行数降序排列，行数相同再比得分，于是「高潮一定是
 *    30–45 秒内歌词行数最多的那一段」这一判据直接落在排序首位上，稀疏段落自然落选。
 *
 * 定位是**纯函数**：同一份歌词与时长必得同一结果。仅在后台扫描时调用（见 [HighlightScanner]），
 * 结果由 [HighlightStore] 落盘；播放路径只查表，从不调用本函数。
 */
internal object HighlightLocator {

    // 目标片段时长：先纳入整个副歌块，再逐行补到下限；上限用「再补一行就会超出」判定
    const val MIN_SEGMENT_MS = 30_000L
    const val MAX_SEGMENT_MS = 45_000L

    // 认定为副歌所需的重复次数：一次前段 + 至少一次复现
    private const val MIN_REPEAT = 2

    // 参与匹配的连续行窗口长度范围。单行不成段（避免把反复出现的语气短句当副歌），
    // 上限取 6 行 —— 再长会把「主歌+副歌」整段纳入，且窗口越宽越容易越过片段时长上限
    private const val MIN_WINDOW_LINES = 2
    private const val MAX_WINDOW_LINES = 6

    // 带时间轴歌词的最少行数：不足时视为「歌词不足以定位」，不强行给出候选
    private const val MIN_TIMED_LINES = 6

    // 候选列表上限：并列的重复块最多保留这么多个
    private const val MAX_CANDIDATES = 4

    // 候选之间重叠超过此比例即视为同一段落的重复列举，只留高分者
    private const val OVERLAP_REJECT_RATIO = 0.5

    // 打分权重（五项之和为 1）
    private const val POSITION_WEIGHT = 0.15
    private const val DURATION_WEIGHT = 0.20
    private const val DENSITY_WEIGHT = 0.30
    private const val PAUSE_WEIGHT = 0.15
    private const val ENDING_WEIGHT = 0.20

    // 密度参考：每秒 0.6 行记满分，0.15 行/秒及以下记 0（流行歌副歌大致落在每 2–5 秒一行）
    private const val DENSITY_FULL_PER_SEC = 0.6
    private const val DENSITY_ZERO_PER_SEC = 0.15

    // 停顿参考：行间最大间隔 2 秒及以内记满分，10 秒及以上记 0
    private const val PAUSE_FULL_MS = 2_000.0
    private const val PAUSE_ZERO_MS = 10_000.0

    // 排序：先按片段内行数降序（「歌词段落最多」），行数相同再按得分降序，仍相同取更靠后者
    private val CANDIDATE_ORDER: Comparator<Candidate> =
        compareByDescending<Candidate> { it.lineCount }
            .thenByDescending { it.score }
            .thenByDescending { it.startMs }

    /**
     * 副歌候选：一段重复出现的行块收成的片段，附重复次数、片段内歌词行数与可信度得分。
     *
     * [repeats] 与 [lineCount] 分开保留：[repeats] 是「这是不是副歌」的重复证据，
     * [lineCount] 是「这段够不够密」的排他判据（见 [CANDIDATE_ORDER]）。
     */
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

    /**
     * 该曲歌词是否带可用时间轴。
     *
     * 上层据此区分两种「没有候选」：时间轴不可用只是歌词不足以定位（整曲播放，不跳过），
     * 时间轴可用却收不出片段才可能是真的没有副歌。
     */
    fun hasUsableTimeline(lines: List<LyricLine>): Boolean = timedLyricLines(lines).size >= MIN_WINDOW_LINES

    /**
     * 找出全部副歌候选，按 [CANDIDATE_ORDER] 降序 —— 首位即「30–45 秒内歌词行数最多」的片段。
     *
     * @param lines 曲目歌词（含时间轴）。时间轴无效时返回空列表
     * @param durationMs 曲目总时长，用于片段边界约束与结尾权重
     */
    fun candidates(lines: List<LyricLine>, durationMs: Long): List<Candidate> {
        // 整曲不足片段下限的一半：无论取哪一段都短到没有意义，直接判无候选
        if (durationMs < MIN_SEGMENT_MS / 2) return emptyList()

        val timed = timedLyricLines(lines)
        if (timed.size < MIN_TIMED_LINES) return emptyList()
        val texts = timed.map { normalize(it.text) }
        val maxWidth = minOf(MAX_WINDOW_LINES, texts.size / MIN_REPEAT)
        if (maxWidth < MIN_WINDOW_LINES) return emptyList()

        val raw = ArrayList<Candidate>()
        for (width in MIN_WINDOW_LINES..maxWidth) {
            // 键 = 窗口内各行拼接；同键的起始下标即该行块的各次出现
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

        // 宽窗口会把窄窗口的子块一并覆盖，同一段落因而被列举多次：按密度降序保留，重叠过多的丢弃
        val kept = ArrayList<Candidate>()
        raw.sortedWith(CANDIDATE_ORDER).forEach { candidate ->
            if (kept.none { overlapRatio(it, candidate) >= OVERLAP_REJECT_RATIO }) kept += candidate
        }
        return kept.take(MAX_CANDIDATES)
    }

    /**
     * 把某个重复行块收成片段，**只取其最后一次能成段的出现** —— 这就是「最后一遍完整副歌」。
     *
     * 起点取该次出现的首行（副歌从第一句听起）；结束行先取块末行，块本身越过上限时向回缩，
     * 再逐行向后补到时长下限，上限用「再补一行就会超出」判定 —— 收尾因而总停在某一行唱完之后，
     * 而不是被上限硬切在半句上。
     *
     * 末遍短到收不出片段（如曲末只剩半段副歌）时退回上一遍；都收不出则返回 null。
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

    /**
     * 从 [startIndex] 起收尾：先纳入 width 行的整块，块越过上限时向回缩到能容纳的行，
     * 再逐行向后补到时长下限，上限用「再补一行就会超出」判定。块超出歌词末尾时返回 null。
     */
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

    /**
     * 候选得分：位置、时长、密度、停顿、结尾权重五项归一后加权求和，落在 0..1。
     *
     * - **位置**：块首现得越晚越像副歌（先听到主歌是流行歌的常态）；
     * - **时长**：越贴合 30–45 秒越好，凑不进下限的降权；
     * - **密度**：片段内每秒唱出的行数越多越好（「高潮是歌词段落最多的一段」）；
     * - **停顿**：行间最大间隔越短越好（长间隔多是纯器乐段，不宜当副歌）；
     * - **结尾**：片段收在越靠近曲末处越好（末遍副歌是整曲落点）。
     */
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

    // 片段内相邻歌词行的最大间隔
    private fun maxGapMs(timed: List<LyricLine>, from: Int, to: Int): Long {
        var maxGap = 0L
        for (i in from until to) {
            val gap = timed[i + 1].timeMs - timed[i].timeMs
            if (gap > maxGap) maxGap = gap
        }
        return maxGap
    }

    // 把值从 [zero, full] 线性映射到 0..1
    private fun normalize(value: Double, zero: Double, full: Double): Double {
        if (full <= zero) return 0.0
        return ((value - zero) / (full - zero)).coerceIn(0.0, 1.0)
    }

    // 两候选的重叠占较短者的比例，用于把同一段落的重复列举收敛成一个
    private fun overlapRatio(a: Candidate, b: Candidate): Double {
        val overlap = (minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)).coerceAtLeast(0L)
        val shorter = minOf(a.durationMs, b.durationMs).coerceAtLeast(1L)
        return overlap.toDouble() / shorter
    }

    /**
     * 只保留可用于定位的歌词行：有效时间轴 + 非制作信息行。
     *
     * 制作信息行（作词/作曲/编曲……）常被整段重复，若参与匹配会把副歌判到信息行上，
     * 故复用 [LyricFeatures.cleanLyrics] 的同一套判据而非另立一份前缀表。
     * 同一时间戳的多行（原文与翻译分行）只取首行，避免把翻译误当成复现。
     */
    private fun timedLyricLines(lines: List<LyricLine>): List<LyricLine> = lines
        .filter { it.timeMs > 0 && it.text.isNotBlank() }
        .sortedBy { it.timeMs }
        .distinctBy { it.timeMs }
        .filter { LyricFeatures.cleanLyrics(listOf(it.text)).isNotEmpty() }

    // 行文本归一：去空白与大小写差异，让「副歌第二遍」与第一遍能对上
    private fun normalize(text: String): String =
        text.trim().lowercase().replace(WHITESPACE, "")

    // 拼接窗口内各行：以不出现在歌词文本中的分隔符相连，避免相邻行边界产生假重合
    private fun buildKey(texts: List<String>, start: Int, width: Int): String {
        val builder = StringBuilder()
        for (index in start until start + width) {
            if (index > start) builder.append(KEY_SEPARATOR)
            builder.append(texts[index])
        }
        return builder.toString()
    }

    /**
     * 某一行的结束点：取后继行的起点，即本行唱完的那一刻；已是末行时以曲末为界。
     *
     * 逐字歌词给出真实演唱终点时取二者较大者 —— 末字可能延伸到下一行起点之后，
     * 只认下一行起点会把这一行的收尾切掉，这正是「末句没唱完就被切走」的成因。以曲末为上限封顶。
     */
    private fun lineEndMs(timed: List<LyricLine>, lineIndex: Int, durationMs: Long): Long {
        val nextStart = timed.getOrNull(lineIndex + 1)?.timeMs ?: durationMs
        val wordEnd = timed[lineIndex].words.maxOfOrNull { it.startMs + it.durationMs } ?: 0L
        return maxOf(nextStart, wordEnd).coerceAtMost(durationMs)
    }

    private val WHITESPACE = Regex("\\s+")

    // 键分隔符：取歌词文本中不可能出现的控制字符
    private const val KEY_SEPARATOR = "\u0001"
}
