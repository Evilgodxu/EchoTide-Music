package com.yichao.evilgodxu.data.music.highlight

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全库片段扫描，后台成批执行；只读歌词，不碰音频
 * 只处理指纹过期的曲目，重复调用无代价
 */
internal object HighlightScanner {

    // 每 200 条落盘一次，崩溃损失小
    private const val CHECKPOINT = 200

    // 时长缺失时末行后补 45s
    private const val FALLBACK_TAIL_MS = 45_000L

    /** 扫描全库；true 表示片段表有变 */
    suspend fun scan(context: Context, tracks: List<MusicTrack>): Boolean {
        HighlightStore.ensureLoaded(context)
        val pending = withContext(Dispatchers.Default) {
            tracks.filterNot { HighlightStore.isUpToDate(it) }
        }
        if (pending.isEmpty()) return false
        val batch = LinkedHashMap<Long, HighlightStore.ScanResult>()
        withContext(Dispatchers.Default) {
            pending.forEach { track ->
                batch[track.id] = resolve(context, track)
                if (batch.size >= CHECKPOINT) {
                    HighlightStore.commit(context, batch)
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) HighlightStore.commit(context, batch)
        return true
    }

    /** 解析单曲：歌词→候选→最优片段，并附指纹 */
    private suspend fun resolve(context: Context, track: MusicTrack): HighlightStore.ScanResult {
        val fingerprint = HighlightStore.fingerprintOf(track)
        val lines = withContext(Dispatchers.IO) { lyricLines(context, track) }
        if (lines.isEmpty()) {
            // 无歌词记为无法定位，补齐后重扫
            return HighlightStore.ScanResult(fingerprint, HighlightEntry.Unresolved)
        }
        val durationMs = effectiveDurationMs(track, lines)
        val best = HighlightLocator.candidates(lines, durationMs).firstOrNull()
        val entry = when {
            best != null -> HighlightEntry.Segment(best.toHighlight())
            // 时间轴可用但无片段，确无副歌
            HighlightLocator.hasUsableTimeline(lines) -> HighlightEntry.NoChorus
            // 无时间轴则无法定位，整曲播放
            else -> HighlightEntry.Unresolved
        }
        return HighlightStore.ScanResult(fingerprint, entry)
    }

    /** 取歌词：内存→缓存路径→按标题与艺术家查找 */
    private fun lyricLines(context: Context, track: MusicTrack): List<LyricLine> {
        if (track.lyricLines.isNotEmpty()) return track.lyricLines
        val path = track.lyricCachePath
        if (MusicMetadataCache.isValid(context, path)) return MusicMetadataCache.loadLyrics(context, path)
        val byName = MusicMetadataCache.findLyrics(context, track.title, track.artist)
        return byName?.let { MusicMetadataCache.loadLyrics(context, it) }.orEmpty()
    }

    // 时长缺失时末行后补 45s
    private fun effectiveDurationMs(track: MusicTrack, lines: List<LyricLine>): Long {
        if (track.duration > 0L) return track.duration
        val lastLine = lines.lastOrNull()?.timeMs ?: return 0L
        return lastLine + FALLBACK_TAIL_MS
    }
}
