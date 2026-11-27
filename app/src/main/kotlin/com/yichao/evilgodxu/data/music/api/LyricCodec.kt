package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.LyricWord
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// 解析平台歌词为 LyricLine：LRC 与逐字格式
// 逐字时间轴统一归一为绝对毫秒，与歌词缓存约定一致

// 字标签捕获组：前两组为时间数值，text 为文本组
private class WordTagGroups(val first: Int, val second: Int, val text: Int)

// 标准 LRC 解析，网易云无逐字标签时复用
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

// 按时间戳合并译文，精确匹配优先，容差内取最近
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

// 译文与原文时间戳可能有偏差，容差内配对
private const val TRANSLATION_MATCH_WINDOW_MS = 500L

// 增强 LRC：兼容纯文本、逐字标签与 [tr] 翻译
// 无标签按 LRC 处理；支持 mm:ss、hh:mm:ss
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

// QRC 解析：字标签为绝对毫秒，无需叠加行起点
internal fun parseQrcText(raw: String): List<LyricLine> {
    return raw.lineSequence().mapNotNull { rawLine ->
        val line = BRACKET_LINE_PATTERN.find(rawLine) ?: return@mapNotNull null
        buildWordLine(line.groupValues[1].toLong(), line.groupValues[3], QRC_WORD_PATTERN, QRC_GROUPS, absolute = true)
    }.sortedBy { it.timeMs }.toList()
}

// KRC 解析：字偏移相对行起点，需叠加还原绝对时间
// 译文来自 [language] 行与同时间戳全零字标签行
internal fun parseKrcText(raw: String): List<LyricLine> {
    val main = mutableListOf<LyricLine>()
    val translations = mutableListOf<LyricLine>()
    val languageTranslations = parseKrcLanguageTranslations(raw)
    var lyricIndex = 0
    raw.lineSequence().forEach { rawLine ->
        val line = BRACKET_LINE_PATTERN.find(rawLine) ?: return@forEach
        val timeMs = line.groupValues[1].toLong()
        val payload = line.groupValues[3]
        // [language] 数组按歌词行顺序对齐，逐行取下一条
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

// [language] 元信息：base64 JSON 取译文
// 载荷非法或缺行时返回空列表，退化为无译文
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

// [language] 载荷结构，type 区分译文/音译
@Serializable
private data class KrcLanguage(val content: List<KrcLanguageSegment> = emptyList())

@Serializable
private data class KrcLanguageSegment(
    val type: Int = 0,
    // 每行译文由字块拼成，与歌词行逐一对应
    val lyricContent: List<List<String>> = emptyList(),
)

// 酷我 lrcx 解析：两数值为区间两端放大 8 倍的编码
// 起点=|尾+起|/16，终点=max(|尾|,|起|)/8
// 重叠按下一字起点截断；译文靠行时间戳配对
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

// 合并译文；配不上主行的译文保留为普通行，避免丢句
private fun mergeTranslationLines(main: List<LyricLine>, translations: List<LyricLine>): List<LyricLine> {
    if (translations.isEmpty()) return main.sortedBy { it.timeMs }
    val mainTimes = main.map { it.timeMs }
    val orphans = translations.filter { translation ->
        mainTimes.none { kotlin.math.abs(it - translation.timeMs) <= TRANSLATION_MATCH_WINDOW_MS }
    }
    return (mergeTranslations(main, translations) + orphans).sortedBy { it.timeMs }
}

// 把标签文本还原为 LyricLine，无标签退化为逐行
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

// 标签起止全零表示整行共用一个时间点，即译文行
private fun isZeroOffsetLine(payload: String, tagPattern: Regex, groups: WordTagGroups): Boolean {
    val tags = tagPattern.findAll(payload).toList()
    return tags.isNotEmpty() && tags.all {
        it.groupValues[groups.first] == "0" && it.groupValues[groups.second] == "0"
    }
}

// 时间戳转毫秒，小时段可省略
private fun colonTimestamp(hours: String, minutes: String, seconds: String, fraction: String): Long =
    (hours.toLongOrNull() ?: 0L) * 3_600_000 +
        minutes.toLong() * 60_000 +
        seconds.toLong() * 1000 +
        fraction.padEnd(3, '0').take(3).toLong()

// zlib 解压，数据非法返回 null
internal fun inflateBytes(data: ByteArray): ByteArray? = try {
    val inflater = Inflater()
    inflater.setInput(data)
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (!inflater.finished()) {
        val count = inflater.inflate(buffer)
        // 无输出即输入耗尽或截断，避免空转
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

// QRC/KRC 行标签：[行起点,行时长]内容
private val BRACKET_LINE_PATTERN = Regex("""\[(\d+),(\d+)](.*)""")

// QRC 字在标签前：字(起点,时长)，惰性通配避免吞括号
private val QRC_WORD_PATTERN = Regex("""(.*?)\((\d+),(\d+)\)""")
private val QRC_GROUPS = WordTagGroups(first = 2, second = 3, text = 1)

// KRC 字在标签后：<偏移,时长,效果>字
private val KRC_WORD_PATTERN = Regex("""<(-?\d+),(-?\d+)(?:,-?\d+)?>([^<]*)""")
private val KRC_GROUPS = WordTagGroups(first = 1, second = 2, text = 3)

// KRC 译文前缀，base64 JSON 中 type=1
private const val KRC_LANGUAGE_PREFIX = "[language:"
private const val KRC_LANGUAGE_TRANSLATION_TYPE = 1
private val KRC_LANGUAGE_JSON = Json { ignoreUnknownKeys = true }

// 酷我字在标签后：<区间一端,另一端>字
private val KUWO_LINE_PATTERN = Regex("""^\[(\d+):(\d+)\.(\d+)](.*)$""")
private val KUWO_WORD_PATTERN = Regex("""<(-?\d+),(-?\d+)>([^<]*)""")
private val KUWO_GROUPS = WordTagGroups(first = 1, second = 2, text = 3)
