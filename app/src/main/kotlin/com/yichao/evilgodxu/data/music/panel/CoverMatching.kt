package com.yichao.evilgodxu.data.music.panel

import android.content.Context
import com.yichao.evilgodxu.data.music.api.NeteaseMusicApi
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.proxy.ProxySourceEngine
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun searchCoverCandidates(
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    source: MusicSearchSource,
) {
    playbackState.isCoverSearching = true
    playbackState.coverCandidates = emptyList()
    try {
        playbackState.coverCandidates = searchSingleSourceCandidates(
            playbackState.proxySourceStore, source, track.title, track.artist,
        )
            .filter { matchesTrackTitle(track.title, it) && !it.coverUrl.isNullOrBlank() }
            .take(30)
    } catch (e: Exception) {
        CrashLogManager.logException("CoverMatching", "搜索封面候选失败: 歌曲=${track.title}", e)
        playbackState.coverCandidates = emptyList()
    } finally {
        playbackState.isCoverSearching = false
    }
}

internal suspend fun applyCoverCandidate(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    candidate: NeteaseSongSearchResult,
): Boolean {
    return try {
        val writeSuccess = withContext(Dispatchers.IO) {
            // 代理优先按 coverId 换封面，失败回退封面直链
            val bytes = ProxySourceEngine.coverBytes(playbackState.proxySourceStore, candidate)
                ?: NeteaseMusicApi.loadCoverBytes(candidate.coverUrl.orEmpty())
                ?: return@withContext false
            // 按容器格式写封面，旧缩略图作废
            MusicMetadataWriter.writeCover(context, track, bytes)
        }
        if (!writeSuccess) return false
        withContext(Dispatchers.Main) {
            // 封面已写入文件，清掉在线封面地址
            playbackState.updateTrack(track.copy(neteaseId = candidate.id, neteaseCoverUrl = ""))
            playbackState.coverCache.bumpCoverRevision()
            playbackState.coverCandidates = emptyList()
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("CoverMatching", "应用封面候选失败: 歌曲=${track.title} 路径=${track.path}", e)
        false
    }
}
