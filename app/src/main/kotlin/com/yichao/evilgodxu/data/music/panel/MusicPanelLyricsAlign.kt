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

// 行级时间戳缺失时的兜底行宽，与歌词解析的同行宽上限保持一致
private const val MAX_LINE_MS = 12_000L

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
    // 纯标点或空白的行没有可对齐的字元，跳过
    val targets = lines.indices.filter { lines[it].text.isNotBlank() }
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

// 行结束时间：取下一条行时间戳，末行与间隔过大的行按上限截断
private fun lineEndMs(lines: List<LyricLine>, index: Int): Long {
    val start = lines[index].timeMs
    val next = lines.getOrNull(index + 1)?.timeMs ?: (start + MAX_LINE_MS)
    return min(next, start + MAX_LINE_MS)
}
