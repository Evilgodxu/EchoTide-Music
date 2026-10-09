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
 * 副歌定位：在带时间轴的歌词里找出重复次数最多的连续行块，作为歌曲的高潮段。
 *
 * 判据取自歌词结构而非音频能量。逐曲解码全曲做能量峰值检测在播放路径上代价过大
 * （频谱解码基建是给可视化用的），而副歌在歌词上表现为「同一批行反复出现」，
 * 是与 [LyricFeatures.structure] 同一思路的零成本近似。
 *
 * 定位是**纯函数**：同一份歌词与时长必得同一区间。仅在后台扫描时调用一次
 * （见 [HighlightScanner]），结果连同伴随判据由 [HighlightStore] 落盘；播放路径只查表，
 * 从不调用本函数 —— 片段因此不会在切歌时才现算，也不会因歌词晚到而行为不定。
 *
 * 无法定位时返回 null。调用方据此区分「有歌词但确实没有副歌」（该曲跳过）与
 * 「歌词还没到位」（整曲播放），判定归属见 [HighlightStore]。
 */
internal object HighlightLocator {

    // 目标片段时长：先纳入整个副歌块，再逐行补到下限；上限用「再补一行就会超出」判定
    private const val MIN_SEGMENT_MS = 30_000L
    private const val MAX_SEGMENT_MS = 45_000L

    // 认定为副歌所需的重复次数：一次前段 + 至少一次复现
    private const val MIN_REPEAT = 2

    // 参与匹配的连续行窗口长度范围。单行不成段（避免把反复出现的语气短句当副歌），
    // 上限取 8 行 —— 流行歌副歌通常不超过这个规模，再长会把「主歌+副歌」整段纳入
    private const val MIN_WINDOW_LINES = 2
    private const val MAX_WINDOW_LINES = 8

    // 带时间轴歌词的最少行数：不足时视为不可定位，不强行给出片段
    private const val MIN_TIMED_LINES = 8

    /**
     * 定位副歌区间。
     *
     * @param lines 曲目歌词（含时间轴）。时间轴无效（全为 0 或重复）时不可定位
     * @param durationMs 曲目总时长，用于片段边界约束
     * @return 片段区间；不可定位时返回 null
     */
    fun locate(lines: List<LyricLine>, durationMs: Long): Highlight? {
        // 整曲不足片段下限的一半：无论取哪一段都短到没有意义，直接判不可定位
        if (durationMs < MIN_SEGMENT_MS / 2) return null

        val timed = timedLyricLines(lines)
        if (timed.size < MIN_TIMED_LINES) return null

        val block = repeatedBlock(timed.map { normalize(it.text) }) ?: return null
        return buildSegment(timed, block, durationMs)
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

    /**
     * 找出得分最高的重复连续行块。
     *
     * 得分为「重复次数 × 块长度」：长且反复出现的段落更像副歌。同分时依次比较重复次数、
     * 首次出现位置 —— 后者取更靠后的块，因为「主歌—副歌」各重复两次的歌曲里两块得分相同，
     * 而副歌总是后出现的那个（先听到主歌是流行歌的常态）。
     */
    private fun repeatedBlock(texts: List<String>): Block? {
        var best: Block? = null
        val maxWidth = minOf(MAX_WINDOW_LINES, texts.size / MIN_REPEAT)
        for (width in MIN_WINDOW_LINES..maxWidth) {
            // 键 = 窗口内各行的拼接；同键的起始下标即该行块的各次出现
            val occurrences = HashMap<String, MutableList<Int>>()
            for (start in 0..texts.size - width) {
                val key = buildKey(texts, start, width)
                occurrences.getOrPut(key) { mutableListOf() } += start
            }
            occurrences.forEach { (_, starts) ->
                if (starts.size < MIN_REPEAT) return@forEach
                val candidate = Block(width, starts)
                if (best == null || candidate.betterThan(best)) best = candidate
            }
        }
        return best
    }

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
     * 以副歌块为锚点收出一个片段，**收尾一律落在歌词行的边界上**。
     *
     * 先纳入整个重复块（副歌要整段听完，而不是掐头去尾），再逐行向后补到时长下限；
     * 上限用「再补一行就会超出」判定，于是收尾总停在某一行唱完之后，而不是被上限硬切在半句上 ——
     * 这就是「把歌词行播完再切下一曲」。
     *
     * 曲末不足下限时接受较短的收尾片段：把最后几句唱完比凑满时长更贴合听感。但仍不接受
     * 短到没有意义的长短（低于下限一半即判不可定位）。
     */
    private fun buildSegment(timed: List<LyricLine>, block: Block, durationMs: Long): Highlight? {
        val startMs = timed[block.firstStart].timeMs
        var lastLine = block.firstStart + block.width - 1
        var endMs = lineEndMs(timed, lastLine, durationMs)
        while (endMs - startMs < MIN_SEGMENT_MS && lastLine + 1 < timed.size) {
            val nextEnd = lineEndMs(timed, lastLine + 1, durationMs)
            if (nextEnd - startMs > MAX_SEGMENT_MS) break
            lastLine++
            endMs = nextEnd
        }
        if (endMs - startMs < MIN_SEGMENT_MS / 2) return null
        return Highlight(startMs, endMs)
    }

    // 某一行的结束点：取后继行的起点，即本行唱完的那一刻；已是末行时以曲末为界
    private fun lineEndMs(timed: List<LyricLine>, lineIndex: Int, durationMs: Long): Long =
        timed.getOrNull(lineIndex + 1)?.timeMs ?: durationMs

    private val WHITESPACE = Regex("\\s+")

    // 键分隔符：取歌词文本中不可能出现的控制字符
    private const val KEY_SEPARATOR = "\u0001"

    /** 重复块：窗口宽度与各次出现的起始行下标 */
    private class Block(val width: Int, val starts: List<Int>) {

        val firstStart: Int get() = starts.first()

        // 同分判定：重复次数多者优先，再次取首次出现更靠后的块
        fun betterThan(other: Block): Boolean {
            val score = starts.size * width
            val otherScore = other.starts.size * other.width
            return when {
                score != otherScore -> score > otherScore
                starts.size != other.starts.size -> starts.size > other.starts.size
                else -> firstStart > other.firstStart
            }
        }
    }
}
