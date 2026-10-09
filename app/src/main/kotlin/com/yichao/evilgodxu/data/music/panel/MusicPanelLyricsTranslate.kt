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

// 批次之间的间隔：公开接口按 IP 限流，连续提交容易被判为突发流量
private const val BATCH_INTERVAL_MS = 1_200L

// 单批提交的字符总量上限：接口上限约 1000 字符，留出余量后按此切批
private const val MAX_BATCH_CHARS = 600

// 单批因规模超限失败时的拆分次数上限，避免接口异常时把一次请求放大成大量请求
private const val MAX_SPLIT_DEPTH = 1

// 中文判定：汉字区间
private val CHINESE_PATTERN = Regex("[\\u4e00-\\u9fff]")

// 日文假名与韩文谚文区间：出现即说明该行的汉字是日文汉字或韩文汉字的借字，不算中文
private val KANA_HANGUL_PATTERN = Regex("[\\u3040-\\u30ff\\uac00-\\ud7af]")

/** 自动补译的结局：三种「没做」的原因给用户的提示不同，故分开返回 */
internal sealed interface TranslateOutcome {
    /** 补译完成，[translated] 为本次写入译文的歌词行数 */
    data class Applied(val translated: Int) : TranslateOutcome

    /** 没有待补译的歌词行：或已有译文，或全部是署名等非歌词行 */
    data object NothingToDo : TranslateOutcome

    /** 原文主体已是中文，再译一遍只会得到与原文相同的文本 */
    data object SameLanguage : TranslateOutcome

    /** 所有批次都失败（限流未恢复或网络异常） */
    data object Failed : TranslateOutcome
}

/** 单行补译的结局：沿用整篇补译的分类，使「没做」的原因在界面上仍有准确提示 */
internal sealed interface TranslateLineOutcome {
    /** 补译完成，[translation] 为该行译文 */
    data class Applied(val translation: String) : TranslateLineOutcome

    /** 该行没有可补译的内容：原文为空，或译文与原文相同（专有名词、感叹词等） */
    data object NothingToDo : TranslateLineOutcome

    /** 原文主体已是中文，再译一遍只会得到与原文相同的文本 */
    data object SameLanguage : TranslateLineOutcome

    /** 请求失败（限流未恢复或网络异常） */
    data object Failed : TranslateLineOutcome
}

/**
 * 补译单行歌词：为用户显式指定的一行请求译文。
 *
 * 与整篇补译不同，此处不按「是否歌词正文行」过滤 —— 目标行由用户长按指定，意图已经明确，
 * 即便该行是署名或曲首标题也按其选择翻译。
 *
 * 只返回译文，既不写歌词缓存也不改动内存态：调用方把译文并入该行的翻译后照常落盘，
 * 写入的是音频文件的内嵌歌词，与播放端的整篇补译互不干扰。
 */
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

/**
 * 为尚无译文块的歌词行自动补译。
 *
 * 只处理歌词正文行：署名与曲首标题行的时间戳标注的是伴奏长度，译文对用户没有意义，
 * 「制作人：xxx」这类文本还会被译成莫名其妙的人名。
 *
 * 按字符总量切批提交，批次之间留间隔；单批失败不影响已完成的批次，用户可再次发起把剩下的补齐。
 * 结果写入歌词缓存并刷新内存态，但不内嵌进音频文件标签，因此不会改动用户的音频文件。
 */
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
        // 各批都成功但译文与原文完全相同（专有名词、感叹词等）时无内容可写，不落盘
        if (result.added == 0) return TranslateOutcome.NothingToDo

        val updated = withContext(Dispatchers.IO) {
            // 内存态歌词已应用手动偏移（与播放时间轴一致），缓存文件保存的始终是原始时间戳，
            // 故落盘前先还原偏移，避免偏移被叠加两次
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
        // 协程取消属正常流程（离开页面等）：不记日志，向上传递取消
        throw e
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelLyricsTranslate", "自动补译失败: 歌曲=${track.title}", e)
        TranslateOutcome.Failed
    }
}

/** 逐批翻译并写入译文；全部批次失败返回 null，其余情况返回补译后的完整歌词 */
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
            // 译文与原文完全相同（专有名词、感叹词等）时不写入，避免出现和原文一样的译文行
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

// 按行数与字符总量双阈值切批：行数受接口的条数上限约束，字符总量受接口的文本长度上限约束
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

// 请求一批译文，返回与入参等长的列表，失败项为 null；规模超限时二分重试
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

// 原文主体是否为中文：日文含汉字但另有假名、韩文另有谚文，故除汉字外出现假名或谚文即不算中文
private fun isMostlyChinese(texts: List<String>): Boolean {
    val counted = texts.filter { it.isNotBlank() }
    if (counted.isEmpty()) return false
    val chinese = counted.count { CHINESE_PATTERN.containsMatchIn(it) && !KANA_HANGUL_PATTERN.containsMatchIn(it) }
    return chinese * 2 >= counted.size
}

/** 补译结果：[added] 为本次新写入译文的行数 */
private class TranslationResult(val lines: List<LyricLine>, val added: Int)
