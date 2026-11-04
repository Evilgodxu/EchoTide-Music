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
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    source: MusicSearchSource,
) {
    playbackState.isCoverSearching = true
    playbackState.coverCandidates = emptyList()
    try {
        playbackState.coverCandidates = searchSingleSourceCandidates(context, source, track.title, track.artist)
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
            // 代理音源优先按 coverId 换取封面，未配置或失败时回退候选自带的封面直链；
            // 自定义平台没有内置封面地址，两者都取不到时视为该候选不可用
            val bytes = ProxySourceEngine.coverBytes(context, candidate)
                ?: NeteaseMusicApi.loadCoverBytes(candidate.coverUrl.orEmpty())
                ?: return@withContext false
            // 手动刷新封面：按音频容器格式原生写入元数据；系统据此重建封面略缩图，
            // 显示端据此重新取图（系统略缩图或文件内嵌封面）；落盘的旧缩略图由 bumpCoverRevision 一并作废
            MusicMetadataWriter.writeCover(context, track, bytes)
        }
        if (!writeSuccess) return false
        withContext(Dispatchers.Main) {
            // 封面已写入音频文件：清掉在线封面地址，转由文件自身的封面提供显示
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
