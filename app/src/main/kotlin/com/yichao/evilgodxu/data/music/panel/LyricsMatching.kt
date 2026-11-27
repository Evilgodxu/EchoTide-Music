package com.yichao.evilgodxu.data.music.panel

import android.content.Context
import android.net.Uri
import com.yichao.evilgodxu.data.music.api.fetchPlatformLyrics
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.proxy.ProxySourceEngine
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun searchLyricsCandidates(
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    source: MusicSearchSource,
) {
    playbackState.isLyricsSearching = true
    playbackState.lyricsCandidates = emptyList()
    playbackState.lyricsRefreshError = null
    try {
        playbackState.lyricsCandidates = searchSingleSourceCandidates(
            playbackState.proxySourceStore, source, track.title, track.artist,
        )
            .filter { matchesTrackTitle(track.title, it) }
            .take(30)
    } catch (e: Exception) {
        CrashLogManager.logException("LyricsMatching", "搜索歌词候选失败: 歌曲=${track.title}", e)
        playbackState.lyricsCandidates = emptyList()
    } finally {
        playbackState.isLyricsSearching = false
    }
}

// 歌词同步内嵌进音频文件，缓存丢失仍可恢复
internal suspend fun embedLyricsText(context: Context, track: MusicTrack, lyricsText: String) {
    if (lyricsText.isBlank()) return
    MusicMetadataWriter.writeLyricsToSource(context, track, lyricsText)
}

// 歌词候选：代理优先，失败回退平台内置接口
internal suspend fun applyLyricsCandidate(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    candidate: NeteaseSongSearchResult,
): Boolean {
    playbackState.isLyricsRefreshing = true
    playbackState.lyricsRefreshError = null
    return try {
        val updated = withContext(Dispatchers.IO) {
            val lines = ProxySourceEngine.lyricLines(playbackState.proxySourceStore, candidate)
                ?: fetchPlatformLyrics(candidate)
            if (lines.isEmpty()) return@withContext null
            val path = MusicMetadataCache.saveLyrics(context, track.title, track.artist, lines).orEmpty()
            if (path.isBlank()) return@withContext null
            embedLyricsText(context, track, MusicMetadataCache.encodeLyrics(lines))
            track.copy(
                lyricCachePath = path,
                lyricLines = lines,
                neteaseId = candidate.id,
                neteaseCoverUrl = candidate.coverUrl.orEmpty(),
                // 清掉匹配失败标记，避免挡住重启恢复
                lyricFailed = false,
            )
        } ?: return false
        withContext(Dispatchers.Main) {
            playbackState.updateTrack(updated)
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("LyricsMatching", "应用歌词候选失败: 歌曲=${track.title} 路径=${track.path}", e)
        false
    } finally {
        withContext(Dispatchers.Main) {
            playbackState.isLyricsRefreshing = false
        }
    }
}

internal suspend fun applyLocalLyrics(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    uri: Uri,
): Boolean {
    return try {
        val updated = withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@withContext null
            val lines = MusicMetadataCache.parseLyricsText(text)
            if (lines.isEmpty()) return@withContext null
            val path = MusicMetadataCache.saveLyrics(context, track.title, track.artist, lines).orEmpty()
            if (path.isBlank()) return@withContext null
            embedLyricsText(context, track, MusicMetadataCache.encodeLyrics(lines))
            track.copy(lyricCachePath = path, lyricLines = lines, lyricFailed = false)
        } ?: return false
        withContext(Dispatchers.Main) {
            playbackState.updateTrack(updated)
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("LyricsMatching", "导入本地歌词失败: 歌曲=${track.title}", e)
        false
    }
}

internal suspend fun applyLyricsLineEdit(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    index: Int,
    rawText: String,
): Boolean {
    return try {
        val updated = withContext(Dispatchers.IO) {
            val edited = MusicMetadataCache.parseLyricsText(rawText)
            if (edited.isEmpty()) return@withContext null
            val lines = track.lyricLines.toMutableList()
            if (index !in lines.indices) return@withContext null
            // 编辑结果可能多行，整段替换后按时间排序
            lines.removeAt(index)
            lines.addAll(index, edited)
            val sorted = lines.sortedBy { it.timeMs }
            val path = MusicMetadataCache.saveLyrics(context, track.title, track.artist, sorted).orEmpty()
            if (path.isBlank()) return@withContext null
            track.copy(lyricCachePath = path, lyricLines = sorted, lyricFailed = false)
        } ?: return false
        withContext(Dispatchers.Main) {
            playbackState.updateTrack(updated)
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("LyricsMatching", "编辑歌词原文失败: 歌曲=${track.title}", e)
        false
    }
}
