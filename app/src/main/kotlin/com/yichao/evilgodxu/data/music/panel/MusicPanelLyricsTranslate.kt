package com.yichao.evilgodxu.data.music.panel

import android.content.Context
import com.yichao.evilgodxu.data.music.api.TranslateResult
import com.yichao.evilgodxu.data.music.api.TranslationApi
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// 批次间隔，公开接口按 IP 限流
private const val BATCH_INTERVAL_MS = 1_200L

// 单批字符上限，接口上限约 1000
private const val MAX_BATCH_CHARS = 600

// 超限拆分次数上限，避免放大请求
private const val MAX_SPLIT_DEPTH = 1

// 中文判定：汉字区间
private val CHINESE_PATTERN = Regex("[\\u4e00-\\u9fff]")

// 出现假名或谚文即不算中文
private val KANA_HANGUL_PATTERN = Regex("[\\u3040-\\u30ff\\uac00-\\ud7af]")

// 曲首标题行窗口，其时间戳对应前奏
private const val TITLE_LINE_WINDOW_MS = 2_000L

// 制作信息行：署名键开头，补译无意义
private val CREDIT_LINE = Regex(
    "^\\s*(?:作词|作曲|编曲|填词|词曲|词|曲|制作人|监制|出品人|出品|录音师|录音|混音师|混音|" +
        "母带师|母带|吉他|贝斯|鼓|键盘|弦乐|和声|统筹|企划|发行|版权|混音|翻译|校对|策划|" +
        "封面|视觉|导演|总策划|音乐总监|演唱|原唱|旁白|by|op|sp|isrc|lrc)\\s*(?:/[^:：]{1,8})?\\s*[:：]",
    RegexOption.IGNORE_CASE,
)

// 纯音乐占位行
private val PLACEHOLDER_LINE = Regex(
    "^\\s*[\\[（(【]?\\s*(?:此歌曲为没有填词的纯音乐|没有填词|纯音乐|请欣赏|间奏|前奏|尾奏|music)" +
        "\\s*[\\]）)】]?\\s*[，,。.、！!~～]*\\s*$",
    RegexOption.IGNORE_CASE,
)

/** 补译结局，未做的原因各不相同 */
internal sealed interface TranslateOutcome {
    /** 补译完成，translated 为写入行数 */
    data class Applied(val translated: Int) : TranslateOutcome

    /** 无待补译行：已有译文或非歌词行 */
    data object NothingToDo : TranslateOutcome

    /** 原文已是中文，译文会相同 */
    data object SameLanguage : TranslateOutcome

    /** 所有批次都失败（限流未恢复或网络异常） */
    data object Failed : TranslateOutcome
}

/** 单行补译结局，沿用整篇分类 */
internal sealed interface TranslateLineOutcome {
    /** 补译完成，translation 为该行译文 */
    data class Applied(val translation: String) : TranslateLineOutcome

    /** 无可补译内容：为空或与原文相同 */
    data object NothingToDo : TranslateLineOutcome

    /** 原文已是中文，译文会相同 */
    data object SameLanguage : TranslateLineOutcome

    /** 请求失败（限流未恢复或网络异常） */
    data object Failed : TranslateLineOutcome
}

/** 翻译指定的一行；不过滤行类型，也不落盘 */
internal suspend fun autoTranslateLyricLine(text: String): TranslateLineOutcome {
    val source = text.trim()
    if (source.isEmpty()) return TranslateLineOutcome.NothingToDo
    if (isMostlyChinese(listOf(source))) return TranslateLineOutcome.SameLanguage
    return when (val result = TranslationApi.translate(listOf(source))) {
        is TranslateResult.Success -> {
            val translation = result.texts.firstOrNull()?.trim().orEmpty()
            if (translation.isEmpty() || translation == source) {
                TranslateLineOutcome.NothingToDo
            } else {
                TranslateLineOutcome.Applied(translation)
            }
        }
        else -> TranslateLineOutcome.Failed
    }
}

/** 自动补译歌词正文，写缓存不内嵌音频文件 */
internal suspend fun autoTranslateLyrics(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    onProgress: suspend (done: Int, total: Int) -> Unit,
): TranslateOutcome {
    val lines = track.lyricLines
    if (lines.isEmpty()) return TranslateOutcome.NothingToDo
    val firstIndex = lines.indexOfFirst { it.text.isNotBlank() }
    val targets = lines.indices.filter { index ->
        val line = lines[index]
        line.text.length <= MAX_BATCH_CHARS &&
            line.translation.isNullOrBlank() &&
            isLyricBodyLine(line, index == firstIndex)
    }
    if (targets.isEmpty()) return TranslateOutcome.NothingToDo
    if (isMostlyChinese(targets.map { lines[it].text })) return TranslateOutcome.SameLanguage

    return try {
        val result = withContext(Dispatchers.IO) { runTranslation(lines, targets, onProgress) }
            ?: return TranslateOutcome.Failed
        // 译文与原文相同则无内容可写
        if (result.added == 0) return TranslateOutcome.NothingToDo

        val updated = withContext(Dispatchers.IO) {
            // 落盘前还原手动偏移，避免叠加两次
            val stored = if (track.lyricOffsetMs != 0L) {
                MusicMetadataCache.shiftLyrics(result.lines, -track.lyricOffsetMs)
            } else {
                result.lines
            }
            val path = MusicMetadataCache.saveLyrics(context, track.title, track.artist, stored).orEmpty()
            if (path.isBlank()) return@withContext null
            track.copy(lyricCachePath = path, lyricLines = result.lines, lyricFailed = false)
        } ?: return TranslateOutcome.Failed

        withContext(Dispatchers.Main) { playbackState.updateTrack(updated) }
        TranslateOutcome.Applied(result.added)
    } catch (e: CancellationException) {
        // 离开页面导致的取消，不记日志
        throw e
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelLyricsTranslate", "自动补译失败: 歌曲=${track.title}", e)
        TranslateOutcome.Failed
    }
}

/** 逐批翻译；全部失败返回 null */
private suspend fun runTranslation(
    lines: List<LyricLine>,
    targets: List<Int>,
    onProgress: suspend (done: Int, total: Int) -> Unit,
): TranslationResult? {
    val result = lines.toMutableList()
    val batches = batchTargets(lines, targets)
    var done = 0
    var added = 0
    var succeeded = false
    onProgress(0, targets.size)
    batches.forEachIndexed { position, batch ->
        val translations = requestTranslations(batch.map { lines[it].text }, MAX_SPLIT_DEPTH)
        if (translations.any { it != null }) succeeded = true
        batch.forEachIndexed { index, lineIndex ->
            val text = translations[index]
            // 译文与原文相同时不写入
            if (text != null && text.isNotBlank() && text != lines[lineIndex].text) {
                result[lineIndex] = lines[lineIndex].copy(translation = text)
                added++
            }
        }
        done += batch.size
        onProgress(done, targets.size)
        if (position < batches.lastIndex) delay(BATCH_INTERVAL_MS)
    }
    return if (succeeded) TranslationResult(result, added) else null
}

// 按行数与字符双阈值切批
private fun batchTargets(lines: List<LyricLine>, targets: List<Int>): List<List<Int>> {
    val batches = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    var chars = 0
    for (index in targets) {
        val length = lines[index].text.length
        if (current.isNotEmpty() &&
            (current.size >= TranslationApi.MAX_BATCH_SIZE || chars + length > MAX_BATCH_CHARS)
        ) {
            batches += current
            current = mutableListOf()
            chars = 0
        }
        current += index
        chars += length
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

// 请求一批译文，失败项为 null，超限二分重试
private suspend fun requestTranslations(texts: List<String>, splitDepth: Int): List<String?> =
    when (val result = TranslationApi.translate(texts)) {
        is TranslateResult.Success -> result.texts
        TranslateResult.Unavailable -> List(texts.size) { null }
        TranslateResult.TooLarge -> if (splitDepth <= 0 || texts.size == 1) {
            List(texts.size) { null }
        } else {
            val middle = texts.size / 2
            delay(BATCH_INTERVAL_MS)
            requestTranslations(texts.subList(0, middle), splitDepth - 1) +
                requestTranslations(texts.subList(middle, texts.size), splitDepth - 1)
        }
    }

// 汉字占多数且无假名/谚文才算中文
private fun isMostlyChinese(texts: List<String>): Boolean {
    val counted = texts.filter { it.isNotBlank() }
    if (counted.isEmpty()) return false
    val chinese = counted.count { CHINESE_PATTERN.containsMatchIn(it) && !KANA_HANGUL_PATTERN.containsMatchIn(it) }
    return chinese * 2 >= counted.size
}

/** 是否为歌词正文：排除署名、占位与曲首标题 */
internal fun isLyricBodyLine(line: LyricLine, isFirst: Boolean): Boolean {
    val text = line.text.trim()
    if (text.isBlank()) return false
    if (CREDIT_LINE.containsMatchIn(text)) return false
    if (PLACEHOLDER_LINE.matches(text)) return false
    if (isFirst && line.timeMs <= TITLE_LINE_WINDOW_MS && text.contains(" - ")) return false
    return true
}

private class TranslationResult(val lines: List<LyricLine>, val added: Int)
