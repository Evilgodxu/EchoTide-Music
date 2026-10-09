package com.yichao.evilgodxu.data.music.highlight

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.recommend.LyricFeatures
import kotlin.math.abs
import kotlin.math.sqrt

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
 * 副歌定位：从带时间轴的歌词里找出「反复出现的连续行块」，作为高潮候选。
 *
 * 判据的取舍：副歌在歌词上表现为「同一批行反复出现」，这是与 [LyricFeatures.structure] 同源的
 * 结构判据，也是 RefraiD、pychorus 一类副歌检测的共同前提（副歌是整曲重复次数最多的段落）。
 * 逐曲解码全曲做能量峰值检测在扫描路径上代价偏大，故歌词侧的重复结构作为**第一判据**在此独立成立，
 * 音频能量只作补充（见 [HighlightSelector]）—— 于是有清晰重复副歌的曲子不必解码音频即可判定。
 *
 * 与旧版只返回单一区间不同，这里返回**候选列表并附可信度**：一个重复块可能是主歌而非副歌，
 * 与其武断地取最高分，不如把并列的候选交给上层，由音频能量在必要时择一。
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

    // 候选列表上限：并列的重复块最多保留这么多个交给音频择优
    private const val MAX_CANDIDATES = 4

    // 候选之间重叠超过此比例即视为同一段落的重复列举，只留高分者
    private const val OVERLAP_REJECT_RATIO = 0.5

    // 音频兜底片段吸附到歌词行的容差：超出即认为该处没有对应的歌词行，保持原时间
    private const val ALIGN_TOLERANCE_MS = 4_000L

    /**
     * 副歌候选：一个重复行块收成的片段，附重复次数与可信度得分。
     *
     * [repeats] 单独留出是因为它承担「证据强度」的语义：重复三次以上的块几乎必然是副歌，
     * 只重复两次的块要与其它段落竞争（见 [HighlightScanner] 是否再走音频）。
     */
    data class Candidate(
        val startMs: Long,
        val endMs: Long,
        val repeats: Int,
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
     * 找出全部副歌候选，按可信度降序。
     *
     * @param lines 曲目歌词（含时间轴）。时间轴无效时返回空列表
     * @param durationMs 曲目总时长，用于片段边界约束
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
            // 键 = 窗口内各行的拼接；同键的起始下标即该行块的各次出现
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

        // 宽窗口会把窄窗口的子块一并覆盖，同一段落因而被列举多次：按得分降序保留，重叠过多的丢弃
        val kept = ArrayList<Candidate>()
        raw.sortedByDescending { it.score }.forEach { candidate ->
            if (kept.none { overlapRatio(it, candidate) >= OVERLAP_REJECT_RATIO }) kept += candidate
        }
        return kept.take(MAX_CANDIDATES)
    }

    /**
     * 以 [anchorMs] 附近的一条歌词行为起点收出一个片段，供音频兜底片段吸附到歌词边界。
     *
     * 音频给的是能量意义上的高潮区间，起点未必落在歌词行上；于是找最近的行再按同一套规则收段，
     * 收尾因而同样落在歌词行边界上。附近没有可对齐的行（或歌词不足以定位）时返回 null，
     * 调用方据此保留音频原始区间。
     */
    fun segmentAt(lines: List<LyricLine>, anchorMs: Long, durationMs: Long): Highlight? {
        val timed = timedLyricLines(lines)
        if (timed.size < MIN_WINDOW_LINES) return null
        val index = timed.indices.minByOrNull { abs(timed[it].timeMs - anchorMs) } ?: return null
        if (abs(timed[index].timeMs - anchorMs) > ALIGN_TOLERANCE_MS) return null
        return buildSegment(timed, index, durationMs)
    }

    /**
     * 把某个重复行块收成片段，**收尾一律落在歌词行的边界上**。
     *
     * 起点取块首行（副歌从第一句听起）；结束行先取块末行，块本身越过上限时向回缩到能容纳的行，
     * 再逐行向后补到时长下限，上限用「再补一行就会超出」判定 —— 于是收尾总停在某一行唱完之后，
     * 而不是被上限硬切在半句上，这就是「把歌词行播完再切下一曲」。
     *
     * 块本身连同补足都无法凑到下限一半时返回 null（连一个像样的片段都收不出）。
     */
    private fun buildCandidate(
        timed: List<LyricLine>,
        starts: List<Int>,
        width: Int,
        durationMs: Long,
    ): Candidate? {
        val firstStart = starts.first()
        val startMs = timed[firstStart].timeMs
        var lastLine = firstStart + width - 1
        // 块自身越过上限：向回收缩，保证整个块落在 45 秒以内
        while (lastLine > firstStart &&
            lineEndMs(timed, lastLine, durationMs) - startMs > MAX_SEGMENT_MS
        ) {
            lastLine--
        }
        while (lineEndMs(timed, lastLine, durationMs) - startMs < MIN_SEGMENT_MS &&
            lastLine + 1 < timed.size
        ) {
            val nextEnd = lineEndMs(timed, lastLine + 1, durationMs)
            if (nextEnd - startMs > MAX_SEGMENT_MS) break
            lastLine++
        }
        // 兜底钳制：单行歌词长过上限时仍要满足 30–45 秒（此时收尾不落在行边界，属极端情况）
        val endMs = lineEndMs(timed, lastLine, durationMs)
            .coerceAtMost(durationMs)
            .coerceAtMost(startMs + MAX_SEGMENT_MS)
        if (endMs - startMs < MIN_SEGMENT_MS / 2) return null
        return Candidate(
            startMs = startMs,
            endMs = endMs,
            repeats = starts.size,
            score = scoreOf(starts, width, firstStart, timed.size, startMs, endMs),
        )
    }

    // 从某个起始行按同一套规则收段，供音频兜底对齐使用
    private fun buildSegment(timed: List<LyricLine>, startIndex: Int, durationMs: Long): Highlight? {
        val startMs = timed[startIndex].timeMs
        var lastLine = startIndex
        while (lineEndMs(timed, lastLine, durationMs) - startMs < MIN_SEGMENT_MS &&
            lastLine + 1 < timed.size
        ) {
            val nextEnd = lineEndMs(timed, lastLine + 1, durationMs)
            if (nextEnd - startMs > MAX_SEGMENT_MS) break
            lastLine++
        }
        val endMs = lineEndMs(timed, lastLine, durationMs)
            .coerceAtMost(durationMs)
            .coerceAtMost(startMs + MAX_SEGMENT_MS)
        if (endMs - startMs < MIN_SEGMENT_MS / 2) return null
        return Highlight(startMs, endMs)
    }

    /**
     * 候选得分：重复次数 × 块宽，再按三个修正因子缩放。
     *
     * - **间隔规律**：多次出现等距分布比零星重合更像副歌的循环结构；
     * - **出现位置**：首次出现越靠后越可能是副歌（先听到主歌是流行歌的常态）；
     * - **时长适配**：能直接收进 30–45 秒的块优先，需要大幅补足或压缩的降权。
     */
    private fun scoreOf(
        starts: List<Int>,
        width: Int,
        firstStart: Int,
        totalLines: Int,
        startMs: Long,
        endMs: Long,
    ): Double {
        val duration = endMs - startMs
        val fit = when {
            duration in MIN_SEGMENT_MS..MAX_SEGMENT_MS -> 1.0
            duration >= MIN_SEGMENT_MS / 2 -> 0.6
            else -> 0.2
        }
        val regularity = spacingRegularity(starts)
        val lateness = if (totalLines <= 0) 0.0 else firstStart.toDouble() / totalLines
        return starts.size * width * (0.7 + 0.3 * regularity) * (0.85 + 0.3 * lateness) * fit
    }

    // 出现间隔的规律度：间隔越均匀越接近 1；只有两次出现时无从判断，取中间值
    private fun spacingRegularity(starts: List<Int>): Double {
        if (starts.size < 3) return 0.5
        val gaps = starts.zipWithNext { a, b -> (b - a).toDouble() }
        val mean = gaps.average()
        if (mean <= 0.0) return 0.0
        val variance = gaps.sumOf { (it - mean) * (it - mean) } / gaps.size
        return (1.0 - sqrt(variance) / mean).coerceIn(0.0, 1.0)
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
