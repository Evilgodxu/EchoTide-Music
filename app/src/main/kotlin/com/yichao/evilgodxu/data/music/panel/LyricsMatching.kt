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

// 把歌词同步内嵌进音频文件：歌词缓存文件是网络来源歌词的唯一副本，内嵌后即使缓存文件丢失，
// 也能从音频文件本身恢复。失败只记录日志（写入器内部处理），不影响歌词缓存与显示
internal suspend fun embedLyricsText(context: Context, track: MusicTrack, lyricsText: String) {
    if (lyricsText.isBlank()) return
    MusicMetadataWriter.writeLyricsToSource(context, track, lyricsText)
}

// 应用歌词候选：代理音源优先，未配置或失败时回退各平台内置歌词接口。
// 自定义平台没有内置歌词接口，代理不可用即视为该候选无歌词
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
                // 已获取到歌词，清除此前的匹配失败标记，避免重启后缓存恢复被挡住
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
            // 编辑结果可能拆分为多行，整体替换原位置并维持时间序
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
