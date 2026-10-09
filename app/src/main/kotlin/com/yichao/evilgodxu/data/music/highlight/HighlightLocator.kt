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
 * 定位是**纯函数**：同一份歌词与时长必得同一区间。冷启动恢复依赖这一点 ——
 * 片段内进度只有在区间可复现时才能还原到正确位置。
 *
 * 无法定位时返回 null，由调用方决定跳过该曲（本项目在心动模式下即跳过）。
 */
internal object HighlightLocator {

    // 目标片段时长：低于下限向前补足，高于上限截断
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
        // 整曲不足最小片段：无论取哪一段都达不到目标时长，直接判不可定位
        if (durationMs < MIN_SEGMENT_MS) return null

        val timed = timedLyricLines(lines)
        if (timed.size < MIN_TIMED_LINES) return null

        val block = repeatedBlock(timed.map { normalize(it.text) }) ?: return null

        val startLine = block.firstStart
        val blockEndLine = startLine + block.width
        // 块末行的结束点由后继行起点界定；块收尾于末行时以曲末为界
        val rawEnd = timed.getOrNull(blockEndLine)?.timeMs ?: durationMs
        return clampToSegment(timed[startLine].timeMs, rawEnd, durationMs)
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
     * 把定位到的行块时间收敛到目标片段长度。
     *
     * 行块本身通常短于目标下限（一段副歌的时长取决于演唱速度），故以块起点为锚向前铺到下限；
     * 副歌靠近曲末、剩余不足下限时改为向前回退，保证片段时长仍然落在目标区间。
     */
    private fun clampToSegment(blockStartMs: Long, blockEndMs: Long, durationMs: Long): Highlight? {
        val targetEnd = (blockStartMs + MIN_SEGMENT_MS).coerceAtMost(durationMs)
        var end = maxOf(blockEndMs, targetEnd).coerceAtMost(blockStartMs + MAX_SEGMENT_MS)
        var start = blockStartMs
        if (end - start < MIN_SEGMENT_MS) {
            start = (end - MIN_SEGMENT_MS).coerceAtLeast(0L)
        }
        if (end - start < MIN_SEGMENT_MS) return null
        return Highlight(start, end)
    }

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
