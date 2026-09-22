package com.yichao.evilgodxu.data.music.panel

import android.content.Context
import com.yichao.evilgodxu.data.music.analysis.AlignConfig
import com.yichao.evilgodxu.data.music.analysis.LyricSegmentDecoder
import com.yichao.evilgodxu.data.music.analysis.LyricWordAligner
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.log.CrashLogManager
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 单行时长上限：末行无后继时间戳、或下一行相隔过远（长间奏）时按此截断，
// 避免逐字对齐把字铺满整段伴奏
private const val MAX_LINE_MS = 12_000L

// 曲目标题行的判定窗口：LRC 习惯把「歌手 - 歌名」放在曲首，其时间戳绑定的是整段前奏
private const val TITLE_LINE_WINDOW_MS = 2_000L

// 制作信息行：以署名键开头。这些行的时间戳标注的是前奏/间奏的长度，逐字对齐会把字铺满伴奏。
// 键名后允许跟随「/xxx」，用于「词/曲：xxx」这类合并写法
private val CREDIT_LINE = Regex(
    "^\\s*(?:作词|作曲|编曲|填词|词曲|词|曲|制作人|监制|出品人|出品|录音师|录音|混音师|混音|" +
        "母带师|母带|吉他|贝斯|鼓|键盘|弦乐|和声|统筹|企划|发行|版权|混音|翻译|校对|策划|" +
        "封面|视觉|导演|总策划|音乐总监|演唱|原唱|旁白|by|op|sp|isrc|lrc)\\s*(?:/[^:：]{1,8})?\\s*[:：]",
    RegexOption.IGNORE_CASE,
)

// 无唱词的占位行：整行只由括号与这些词构成，才视为占位
private val PLACEHOLDER_LINE = Regex(
    "^\\s*[\\[（(【]?\\s*(?:此歌曲为没有填词的纯音乐|没有填词|纯音乐|请欣赏|间奏|前奏|尾奏|music)" +
        "\\s*[\\]）)】]?\\s*[，,。.、！!~～]*\\s*$",
    RegexOption.IGNORE_CASE,
)

// 逐字对齐的结局：区分「没有可对齐的歌词」与「对齐失败」，两者给用户的提示不同
internal enum class AlignOutcome { Applied, NoTargets, Failed }

/**
 * 把当前曲目的行级歌词升级为逐字歌词。
 *
 * 对每一行原文重新推导逐字时序，已有逐字时序的行一并覆盖：用户显式发起对齐即期望结果由本次
 * 推导决定，且调整过歌词偏移后需要能重新对齐。对齐结果写入歌词缓存并刷新内存态，
 * 但不内嵌进音频文件标签，因此不会改动用户的音频文件。
 */
internal suspend fun alignLyricsWords(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    onProgress: suspend (done: Int, total: Int) -> Unit,
): AlignOutcome {
    val lines = track.lyricLines
    if (lines.isEmpty()) return AlignOutcome.NoTargets
    // 非歌词行不参与对齐：制作信息行与曲目标题行的时间戳标注的是前奏/间奏长度，
    // 对其逐字会把字均摊到整段伴奏上，产出明显错误的卡拉OK时序
    val firstIndex = lines.indexOfFirst { it.text.isNotBlank() }
    val targets = lines.indices.filter { isAlignedLyricLine(lines[it], it == firstIndex) }
    if (targets.isEmpty()) return AlignOutcome.NoTargets

    return try {
        val aligned = withContext(Dispatchers.IO) {
            runAlignment(context, track, lines, targets, onProgress)
        } ?: return AlignOutcome.Failed
        if (aligned.none { it.words.isNotEmpty() }) return AlignOutcome.Failed

        val updated = withContext(Dispatchers.IO) {
            // 内存态歌词已应用手动偏移（与播放时间轴一致），缓存文件则始终保存原始时间戳，
            // 偏移在读取时重新应用。故落盘前先还原偏移，避免偏移被叠加两次
            val stored = if (track.lyricOffsetMs != 0L) {
                MusicMetadataCache.shiftLyrics(aligned, -track.lyricOffsetMs)
            } else {
                aligned
            }
            val path = MusicMetadataCache.saveLyrics(context, track.title, track.artist, stored).orEmpty()
            if (path.isBlank()) return@withContext null
            track.copy(lyricCachePath = path, lyricLines = aligned, lyricFailed = false)
        } ?: return AlignOutcome.Failed

        withContext(Dispatchers.Main) { playbackState.updateTrack(updated) }
        AlignOutcome.Applied
    } catch (e: CancellationException) {
        // 协程取消属正常流程（离开页面等）：不记日志，向上传递取消
        throw e
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelLyricsAlign", "逐字对齐失败: 歌曲=${track.title}", e)
        AlignOutcome.Failed
    }
}

/** 逐行解码并对齐：解码器与对齐引擎在一次任务内复用，逐行释放音频段。 */
private suspend fun runAlignment(
    context: Context,
    track: MusicTrack,
    lines: List<LyricLine>,
    targets: List<Int>,
    onProgress: suspend (done: Int, total: Int) -> Unit,
): List<LyricLine>? {
    val config = AlignConfig()
    val decoder = LyricSegmentDecoder.open(context, track, config.sr) ?: return null
    return try {
        val aligner = LyricWordAligner(config)
        val result = lines.toMutableList()
        onProgress(0, targets.size)
        targets.forEachIndexed { position, index ->
            val line = lines[index]
            val endMs = lineEndMs(lines, index)
            val segment = decoder.decode(
                fromMs = (line.timeMs - (config.preCtx * 1000).toLong()).coerceAtLeast(0L),
                toMs = endMs + (config.postCtx * 1000).toLong(),
            )
            if (segment != null) {
                val words = aligner.alignLine(segment, line.timeMs, endMs, line.text)
                if (words.isNotEmpty()) result[index] = line.copy(words = words)
            }
            onProgress(position + 1, targets.size)
        }
        result.toList()
    } finally {
        decoder.release()
    }
}

/** 行结束时间：取下一条行时间戳，末行与间隔过大的行按上限截断 */
private fun lineEndMs(lines: List<LyricLine>, index: Int): Long {
    val start = lines[index].timeMs
    val next = lines.getOrNull(index + 1)?.timeMs ?: (start + MAX_LINE_MS)
    return min(next, start + MAX_LINE_MS)
}

/**
 * 判断该行是否是可供逐字对齐的歌词正文。
 *
 * 纯标点或空白的行没有可对齐的字元；制作信息行与曲首的标题行虽然有字，但其时间戳标注的
 * 是伴奏长度而非演唱，对齐结果会把字铺满前奏。
 */
private fun isAlignedLyricLine(line: LyricLine, isFirst: Boolean): Boolean {
    val text = line.text.trim()
    if (text.isBlank()) return false
    if (CREDIT_LINE.containsMatchIn(text)) return false
    if (PLACEHOLDER_LINE.matches(text)) return false
    if (isFirst && line.timeMs <= TITLE_LINE_WINDOW_MS && text.contains(" - ")) return false
    return true
}
