package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.LyricWord
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 歌词文本编解码：把各平台的歌词原文统一解析为 [LyricLine]。
 *
 * 平台歌词格式分三类：普通 LRC（逐行）、增强 LRC（行内 `<mm:ss.xxx>` 字标签）、
 * 以及平台私有逐字格式（QQ 的 QRC、酷狗的 KRC、酷我的 lrcx）。
 *
 * 逐字时间轴一律归一为绝对毫秒：字标签写相对行首偏移的格式（KRC、酷我 lrcx）在此补上行起点，
 * 与歌词缓存的序列化约定（[com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache.encodeLyrics]）
 * 保持一致，各平台解析结果也因此可直接互换比较。
 */

/**
 * 字标签的捕获组编号：各平台把文本与时间数值放在正则的不同位置，解析时按组号取值。
 * 前两组均为时间数值，其含义随格式而异（KRC/QRC 是字的起止，酷我 lrcx 是区间两端的编码值）。
 */
private class WordTagGroups(val first: Int, val second: Int, val text: Int)

// 标准 LRC 解析：网易云（无逐字标签时）复用
internal fun parseLrcText(lrc: String): List<LyricLine> {
    return lrc.lineSequence().mapNotNull { line ->
        val match = Regex("\\[(\\d+):(\\d+)(?:\\.(\\d+))?](.*)").find(line) ?: return@mapNotNull null
        LyricLine(
            timeMs = match.groupValues[1].toLong() * 60_000 +
                    match.groupValues[2].toLong() * 1_000 +
                    match.groupValues[3].padEnd(3, '0').take(3).toLong(),
            text = match.groupValues[4].trim()
        ).takeIf { it.text.isNotBlank() }
    }.sortedBy { it.timeMs }.toList()
}

// 按时间戳把翻译歌词合并进原歌词：优先精确匹配，其次取 [TRANSLATION_MATCH_WINDOW_MS] 内最近的一条
internal fun mergeTranslations(lines: List<LyricLine>, transLines: List<LyricLine>): List<LyricLine> {
    if (lines.isEmpty() || transLines.isEmpty()) return lines
    val byTime = transLines.associateBy { it.timeMs }
    val sorted = transLines.sortedBy { it.timeMs }
    return lines.map { line ->
        val translation = byTime[line.timeMs]?.text
            ?: sorted.minByOrNull { kotlin.math.abs(it.timeMs - line.timeMs) }
                ?.takeIf { kotlin.math.abs(it.timeMs - line.timeMs) <= TRANSLATION_MATCH_WINDOW_MS }
                ?.text
            ?: return@map line
        line.copy(translation = translation)
    }
}

// 译文与原文的时间戳可能不严格相等，容差内按最近的一条配对
private const val TRANSLATION_MATCH_WINDOW_MS = 500L

/**
 * 增强 LRC 解析：兼容纯文本行、行内 `<mm:ss.xxx>` 逐字标签与 `[tr][/tr]` 翻译块。
 *
 * 无字标签的行按普通 LRC 处理，故本函数可同时承担 [parseLrcText] 的职责：平台歌词带逐字标签时取其时间轴，
 * 不带时退化为逐行歌词。时间戳支持 `[mm:ss(.xxx)]` 与长音频常用的小时制 `[hh:mm:ss(.xxx)]`。
 */
internal fun parseWordTimedLrcText(lrc: String): List<LyricLine> {
    if (lrc.isBlank()) return emptyList()
    return lrc.lineSequence().mapNotNull { rawLine ->
        val match = WORD_LINE_PATTERN.find(rawLine) ?: return@mapNotNull null
        val content = match.groupValues[5]
        val translation = TRANSLATION_PATTERN.find(content)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
        val cleanContent = TRANSLATION_PATTERN.replace(content, "").trim()
        val words = WORD_TAG_PATTERN.findAll(cleanContent).map { tag ->
            LyricWord(
                startMs = colonTimestamp(tag.groupValues[1], tag.groupValues[2], tag.groupValues[3], tag.groupValues[4]),
                durationMs = 0L,
                text = tag.groupValues[5]
            )
        }.filter { it.text.isNotEmpty() }.toList()
        val text = if (words.isNotEmpty()) words.joinToString("") { it.text } else cleanContent
        LyricLine(
            timeMs = colonTimestamp(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.groupValues[4]),
            text = text.trim(),
            words = words,
            translation = translation,
        ).takeIf { it.text.isNotBlank() }
    }.sortedBy { it.timeMs }.toList()
}

/**
 * QRC 解析（QQ 音乐逐字歌词）：`[行起点,行时长]字(字起点,字时长)...`。
 *
 * 字标签给的是绝对毫秒，与行标签同一时间基准，无需再叠加行起点；元信息行（`[ti:...]`）不匹配数字行标签，被跳过。
 */
internal fun parseQrcText(raw: String): List<LyricLine> {
    return raw.lineSequence().mapNotNull { rawLine ->
        val line = BRACKET_LINE_PATTERN.find(rawLine) ?: return@mapNotNull null
        buildWordLine(line.groupValues[1].toLong(), line.groupValues[3], QRC_WORD_PATTERN, QRC_GROUPS, absolute = true)
    }.sortedBy { it.timeMs }.toList()
}

/**
 * KRC 解析（酷狗逐字歌词）：`[行起点,行时长]<字偏移,字时长,效果>字...`。
 *
 * 字偏移相对行起点，需叠加行起点还原为绝对时间。译文有两条来源：其一是内嵌的
 * `[language:<base64>]` 行，按歌词行顺序 1:1 对齐，是酷狗给出译文的常规通道；
 * 其二是与主行同时间戳、字标签全零的翻译行。前者按行序、后者按时间戳分别并入主行。
 */
internal fun parseKrcText(raw: String): List<LyricLine> {
    val main = mutableListOf<LyricLine>()
    val translations = mutableListOf<LyricLine>()
    val languageTranslations = parseKrcLanguageTranslations(raw)
    var lyricIndex = 0
    raw.lineSequence().forEach { rawLine ->
        val line = BRACKET_LINE_PATTERN.find(rawLine) ?: return@forEach
        val timeMs = line.groupValues[1].toLong()
        val payload = line.groupValues[3]
        // [language] 数组按 [行起点,行时长] 行的出现顺序对齐，逐行取下一条
        val translation = languageTranslations.getOrNull(lyricIndex)
        lyricIndex++
        if (isZeroOffsetLine(payload, KRC_WORD_PATTERN, KRC_GROUPS)) {
            stripTags(payload, KRC_WORD_PATTERN, KRC_GROUPS).takeIf { it.isNotBlank() }
                ?.let { translations += LyricLine(timeMs, it) }
            return@forEach
        }
        buildWordLine(timeMs, payload, KRC_WORD_PATTERN, KRC_GROUPS, absolute = false)?.let {
            main += if (translation.isNullOrBlank()) it else it.copy(translation = translation)
        }
    }
    return mergeTranslationLines(main, translations)
}

/**
 * 解析 KRC 的 `[language:<base64>]` 元信息行，取出与歌词行一一对应的译文。
 *
 * 载荷是 base64 编码的 JSON：`content` 为若干语言段，`type=1` 是译文、`type=0` 是音译；
 * 每段的 `lyricContent` 按歌词行顺序排列，元素为该行拆分后的字块，拼回整行文本即为译文。
 * 仅取译文段，音译不并入译文位；缺行或载荷非法时返回空列表，解析退化为无译文。
 */
private fun parseKrcLanguageTranslations(raw: String): List<String> {
    val encoded = raw.lineSequence()
        .firstOrNull { it.startsWith(KRC_LANGUAGE_PREFIX) }
        ?.removePrefix(KRC_LANGUAGE_PREFIX)
        ?.removeSuffix("]")
        ?.takeIf { it.isNotBlank() }
        ?: return emptyList()
    return runCatching {
        val json = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        KRC_LANGUAGE_JSON.decodeFromString<KrcLanguage>(json)
    }.getOrNull()
        ?.content
        ?.firstOrNull { it.type == KRC_LANGUAGE_TRANSLATION_TYPE }
        ?.lyricContent
        ?.map { it.joinToString("") }
        .orEmpty()
}

// [language] 载荷的 JSON 结构：content 为语言段列表，type 区分译文/音译
@Serializable
private data class KrcLanguage(val content: List<KrcLanguageSegment> = emptyList())

@Serializable
private data class KrcLanguageSegment(
    val type: Int = 0,
    // 每行译文由若干字块拼成，与歌词行顺序逐一对应
    val lyricContent: List<List<String>> = emptyList(),
)

/**
 * 酷我 lrcx 解析：`[mm:ss.mmm]<字尾,字起>字...`。
 *
 * 两个数值是同一区间两端按 (和, 差) 变形后的编码值、且放大了 8 倍：
 * 字起点 = |尾 + 起| / 16，字终点 = max(|尾|, |起|) / 8（与 QQ QRC 逐字时间轴实测逐字吻合）。
 * 原始数据允许相邻字区间重叠，此处按下一字的起点截断，保证逐字高亮单调推进。
 *
 * 译文与原文成对出现且共用同一个行时间戳：原文行带真实字标签，译文行整行只有一个时间点（字标签全零），
 * 故译文只能靠行时间戳与原文配对，其文字语言不作判据 —— 英文等非中文译文同样存在。
 */
internal fun parseKuwoLrcxText(raw: String): List<LyricLine> {
    val main = mutableListOf<LyricLine>()
    val translations = mutableListOf<LyricLine>()
    raw.split(Regex("\r\n|\r|\n")).forEach { rawLine ->
        val match = KUWO_LINE_PATTERN.find(rawLine) ?: return@forEach
        val timeMs = match.groupValues[1].toLong() * 60_000 +
            match.groupValues[2].toLong() * 1_000 +
            match.groupValues[3].padEnd(3, '0').take(3).toLong()
        val payload = match.groupValues[4]
        if (isZeroOffsetLine(payload, KUWO_WORD_PATTERN, KUWO_GROUPS)) {
            stripTags(payload, KUWO_WORD_PATTERN, KUWO_GROUPS).takeIf { it.isNotBlank() }
                ?.let { translations += LyricLine(timeMs, it) }
            return@forEach
        }
        val spans = KUWO_WORD_PATTERN.findAll(payload).map { tag ->
            val edge = tag.groupValues[KUWO_GROUPS.first].toLong()
            val otherEdge = tag.groupValues[KUWO_GROUPS.second].toLong()
            Triple(
                kotlin.math.abs(edge + otherEdge) / 16,
                kotlin.math.abs(edge).coerceAtLeast(kotlin.math.abs(otherEdge)) / 8,
                tag.groupValues[KUWO_GROUPS.text],
            )
        }.toList()
        if (spans.isEmpty()) {
            payload.trim().takeIf { it.isNotBlank() }?.let { main += LyricLine(timeMs, it) }
            return@forEach
        }
        val words = spans.mapIndexedNotNull { index, (start, rawEnd, text) ->
            if (text.isEmpty()) return@mapIndexedNotNull null
            // 末字没有下一字可参照，直接采用原始终点
            val nextStart = spans.getOrNull(index + 1)?.first?.takeIf { it > start }
            val end = nextStart?.let { minOf(rawEnd, it) } ?: rawEnd
            LyricWord(timeMs + start, (end - start).coerceAtLeast(0L), text)
        }
        words.joinToString("") { it.text }.trim().takeIf { it.isNotBlank() }
            ?.let { main += LyricLine(timeMs, it, words) }
    }
    return mergeTranslationLines(main, translations)
}

/**
 * 合并译文并把配不上主行的译文行保留为普通行。
 *
 * 主行为空（如纯器乐段）时平台仍会给出译文行，此时译文没有可挂靠的主行；
 * 直接丢弃会整句丢失，退化为普通行至少不丢内容。
 */
private fun mergeTranslationLines(main: List<LyricLine>, translations: List<LyricLine>): List<LyricLine> {
    if (translations.isEmpty()) return main.sortedBy { it.timeMs }
    val mainTimes = main.map { it.timeMs }
    val orphans = translations.filter { translation ->
        mainTimes.none { kotlin.math.abs(it - translation.timeMs) <= TRANSLATION_MATCH_WINDOW_MS }
    }
    return (mergeTranslations(main, translations) + orphans).sortedBy { it.timeMs }
}

// 把 `字<时间标签>` 形式的载荷还原为 [LyricLine]；标签缺失时退化为逐行歌词，空行返回 null
private fun buildWordLine(
    timeMs: Long,
    payload: String,
    tagPattern: Regex,
    groups: WordTagGroups,
    absolute: Boolean,
): LyricLine? {
    val words = tagPattern.findAll(payload).mapNotNull { tag ->
        val text = tag.groupValues[groups.text]
        if (text.isEmpty()) {
            null
        } else {
            val rawStart = tag.groupValues[groups.first].toLong()
            LyricWord(
                startMs = if (absolute) rawStart else timeMs + rawStart,
                durationMs = tag.groupValues[groups.second].toLong(),
                text = text,
            )
        }
    }.toList()
    val text = if (words.isNotEmpty()) words.joinToString("") { it.text } else stripTags(payload, tagPattern, groups)
    return LyricLine(timeMs, text.trim(), words).takeIf { it.text.isNotBlank() }
}

// 去掉载荷中的时间标签，只保留文本
private fun stripTags(payload: String, tagPattern: Regex, groups: WordTagGroups): String =
    tagPattern.replace(payload) { it.groupValues[groups.text] }.trim()

// 行内每个标签的起止均为 0，即整行共享一个时间点，是平台标记翻译行的约定。
// 要求全零而非只看首个标签：正常歌词的首字也可能落在行首偏移 0 上
private fun isZeroOffsetLine(payload: String, tagPattern: Regex, groups: WordTagGroups): Boolean {
    val tags = tagPattern.findAll(payload).toList()
    return tags.isNotEmpty() && tags.all {
        it.groupValues[groups.first] == "0" && it.groupValues[groups.second] == "0"
    }
}

// 形如 [mm:ss]、[mm:ss.xxx]、[hh:mm:ss.xxx] 的时间戳，转成毫秒；小时段可省略
private fun colonTimestamp(hours: String, minutes: String, seconds: String, fraction: String): Long =
    (hours.toLongOrNull() ?: 0L) * 3_600_000 +
        minutes.toLong() * 60_000 +
        seconds.toLong() * 1000 +
        fraction.padEnd(3, '0').take(3).toLong()

/** zlib 解压；数据非法时返回 null，由调用方按平台容忍缺失 */
internal fun inflateBytes(data: ByteArray): ByteArray? = try {
    val inflater = Inflater()
    inflater.setInput(data)
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (!inflater.finished()) {
        val count = inflater.inflate(buffer)
        // 无输出即输入已耗尽或数据被截断，继续循环只会空转
        if (count == 0) break
        out.write(buffer, 0, count)
    }
    inflater.end()
    out.toByteArray().takeIf { it.isNotEmpty() }
} catch (e: Exception) {
    null
}

private val WORD_LINE_PATTERN = Regex("""\[(?:(\d+):)?(\d+):(\d+)(?:\.(\d+))?](.*)""")
private val WORD_TAG_PATTERN = Regex("""<(?:(\d+):)?(\d+):(\d+)(?:\.(\d+))?>([^<]*)""")
private val TRANSLATION_PATTERN = Regex("""\[tr](.*?)\[/tr]""")

// QRC 与 KRC 的行标签形态一致：[行起点毫秒, 行时长毫秒]行内容
private val BRACKET_LINE_PATTERN = Regex("""\[(\d+),(\d+)](.*)""")

// QRC 的字文本写在标签之前：字(字起点, 字时长)。
// 前缀用惰性通配而非「排除括号」，否则歌词里的括号会被当成标签边界吞掉
private val QRC_WORD_PATTERN = Regex("""(.*?)\((\d+),(\d+)\)""")
private val QRC_GROUPS = WordTagGroups(first = 2, second = 3, text = 1)

// KRC 的字文本写在标签之后：<字偏移, 字时长, 效果>字
private val KRC_WORD_PATTERN = Regex("""<(-?\d+),(-?\d+)(?:,-?\d+)?>([^<]*)""")
private val KRC_GROUPS = WordTagGroups(first = 1, second = 2, text = 3)

// KRC 译文元信息行的前缀，其载荷为 base64 编码的 JSON；type=1 的段是译文
private const val KRC_LANGUAGE_PREFIX = "[language:"
private const val KRC_LANGUAGE_TRANSLATION_TYPE = 1
private val KRC_LANGUAGE_JSON = Json { ignoreUnknownKeys = true }

// 酷我 lrcx 的字文本写在标签之后：<区间一端, 区间另一端>字
private val KUWO_LINE_PATTERN = Regex("""^\[(\d+):(\d+)\.(\d+)](.*)$""")
private val KUWO_WORD_PATTERN = Regex("""<(-?\d+),(-?\d+)>([^<]*)""")
private val KUWO_GROUPS = WordTagGroups(first = 1, second = 2, text = 3)
