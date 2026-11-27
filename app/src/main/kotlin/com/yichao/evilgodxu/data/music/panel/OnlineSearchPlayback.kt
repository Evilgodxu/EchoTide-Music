package com.yichao.evilgodxu.data.music.panel

import android.content.Context
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.api.KugouMusicApi
import com.yichao.evilgodxu.data.music.api.KuwoMusicApi
import com.yichao.evilgodxu.data.music.api.MusicQuality
import com.yichao.evilgodxu.data.music.api.NeteaseMusicApi
import com.yichao.evilgodxu.data.music.api.QQMusicApi
import com.yichao.evilgodxu.data.music.api.adaptiveCandidates
import com.yichao.evilgodxu.data.music.api.fetchPlatformLyrics
import com.yichao.evilgodxu.data.music.download.cacheToDownloads
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.model.playlistTrackId
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.music.proxy.ProxySourceEngine
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal suspend fun downloadAndPlay(
    context: Context,
    playbackState: MusicPlaybackState,
    result: NeteaseSongSearchResult,
    url: String,
    metadataEnricher: MetadataEnricher,
    playlistRefresher: PlaylistRefresher,
) {
    val trackId = result.playlistTrackId
    val track = MusicTrack(
        id = trackId,
        path = "",
        audioUri = url,
        title = result.title,
        artist = result.artist,
        duration = result.duration,
        albumId = 0L,
        // 仅网易云写 neteaseId，供重试匹配
        neteaseId = if (result.source == MusicSearchSource.NETEASE) result.id else 0L,
        neteaseCoverUrl = result.coverUrl.orEmpty(),
        isOnlinePlay = true,
    )

    withContext(Dispatchers.Main) {
        val existingIndex = playbackState.playlist.indexOfFirst { it.id == trackId }
        val targetIndex = if (existingIndex >= 0) {
            // 换音质须写回既有条目，否则新链接会被丢弃
            val previous = playbackState.playlist[existingIndex]
            val refreshed = previous.copy(
                audioUri = url,
                path = "",
                duration = result.duration,
                isOnlinePlay = true,
                neteaseCoverUrl = result.coverUrl.orEmpty(),
            )
            val list = playbackState.playlist.toMutableList()
            list[existingIndex] = refreshed
            playbackState.playlist = list
            if (playbackState.currentTrack?.id == trackId) {
                playbackState.currentTrack = refreshed
            }
            // 有过滤时须回写默认队列，否则退回旧直链
            if (playbackState.queueFilter != null) {
                playbackState.defaultPlaylist = playbackState.defaultPlaylist.map { item ->
                    if (item.id == trackId) refreshed else item
                }
            }
            existingIndex
        } else {
            playbackState.playlist = playbackState.playlist + track
            playbackState.playlist.size - 1
        }
        // 类型须先于曲目状态记下，搜索结果起播为选曲
        playbackState.beginTrackSwitch(TrackSwitchKind.Select)
        playbackState.currentIndex = targetIndex
        playbackState.currentTrack = playbackState.playlist[targetIndex]
        playbackState.persistPlaylist()
        playTrackAt(context, playbackState, targetIndex)
    }

    // 后台取歌词，返回增强 LRC；异常返回 null
    val lyricsJob = playbackState.playbackScope.async(Dispatchers.IO) {
        try {
            val lines = ProxySourceEngine.lyricLines(playbackState.proxySourceStore, result)
                ?: fetchPlatformLyrics(result)
            if (lines.isEmpty()) return@async null
            val lyricPath = MusicMetadataCache.saveLyrics(context, result.title, result.artist, lines).orEmpty()
            withContext(Dispatchers.Main) {
                // updateTrack 同步持久化引用，否则重启丢失缓存
                val track = playbackState.playlist.firstOrNull { it.id == trackId } ?: return@withContext
                // 清掉失败标记，否则缓存被删不再重建
                playbackState.updateTrack(
                    track.copy(lyricCachePath = lyricPath, lyricLines = lines, lyricFailed = false)
                )
            }
            // 与缓存同一份增强 LRC，内嵌后可解析回来
            MusicMetadataCache.encodeLyrics(lines)
        } catch (e: Exception) {
            CrashLogManager.logException("OnlineSearchPlayback", "获取在线歌词失败", e)
            null
        }
    }

    // 同步下载封面字节，由缓存流程内嵌进音频文件
    val coverJob = playbackState.playbackScope.async(Dispatchers.IO) {
        try {
            // 代理优先按 coverId 换封面，失败回退直链
            ProxySourceEngine.coverBytes(playbackState.proxySourceStore, result)
                ?: run {
                    val coverUrl = result.coverUrl?.takeIf { it.isNotBlank() } ?: return@run null
                    NeteaseMusicApi.loadCoverBytes(coverUrl)
                }
        } catch (e: Exception) {
            CrashLogManager.logException("OnlineSearchPlayback", "下载在线封面失败: 歌曲=${result.title}", e)
            null
        }
    }

    playbackState.playbackScope.launch(Dispatchers.IO) {
        // 按 ID 去重缓存任务，重复起播不再下载
        val started = synchronized(playbackState.cacheInProgressIds) {
            playbackState.cacheInProgressIds.add(trackId)
        }
        if (!started) return@launch
        try {
            cacheToDownloads(
                context, result, url, trackId, playbackState,
                metadataEnricher, playlistRefresher, coverJob, lyricsJob,
            )
        } finally {
            playbackState.cacheInProgressIds.remove(trackId)
        }
    }
}

internal suspend fun enrichOnlineMetadata(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    result: NeteaseSongSearchResult,
) {
    if (track.neteaseId != 0L && track.lyricLines.isNotEmpty()) return
    try {
        val lyric = NeteaseMusicApi.lyric(result.id)
        val lyricPath = if (lyric.lines.isNotEmpty()) {
            MusicMetadataCache.saveLyrics(context, track.title, track.artist, lyric.lines).orEmpty()
        } else ""
        embedLyricsText(context, track, MusicMetadataCache.encodeLyrics(lyric.lines))
        withContext(Dispatchers.Main) {
            // 同步持久化引用，避免重启清理误删缓存
            val track = playbackState.playlist.firstOrNull { it.id == track.id } ?: return@withContext
            playbackState.updateTrack(
                track.copy(
                    neteaseId = result.id,
                    neteaseCoverUrl = result.coverUrl.orEmpty(),
                    lyricCachePath = lyricPath,
                    lyricLines = lyric.lines,
                    // 取到歌词才清失败标记，否则保留
                    lyricFailed = lyric.lines.isEmpty() && track.lyricFailed
                )
            )
        }
    } catch (e: Exception) {
        CrashLogManager.logException("OnlineSearchPlayback", "获取在线元数据失败", e)
    }
}

internal suspend fun tryPlayLocalMatch(
    target: NeteaseSongSearchResult,
    playbackState: MusicPlaybackState,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
): Boolean {
    val normalizedTitle = normalizeTitle(target.title)
    val normalizedArtist = normalizeTitle(target.artist)
    val localMatch = playbackState.playlist.firstOrNull { t ->
        t.path.isNotBlank() &&
        normalizeTitle(t.title) == normalizedTitle &&
        (normalizedArtist.isBlank() || normalizeTitle(t.artist) == normalizedArtist)
    } ?: return false
    val idx = playbackState.playlist.indexOfFirst { it.id == localMatch.id }
    if (idx < 0) return false
    if (target.source == MusicSearchSource.NETEASE) {
        scope.launch {
            enrichOnlineMetadata(context, playbackState, localMatch, target)
        }
    }
    playbackState.errorMsg = null
    // 类型须先于曲目状态记下，同为选曲播放
    playbackState.beginTrackSwitch(TrackSwitchKind.Select)
    playbackState.currentIndex = idx
    playbackState.currentTrack = playbackState.playlist[idx]
    playbackState.isSearchMode = false
    playbackState.searchPanel.clearSearchResults()
    playTrackAt(context, playbackState, idx)
    return true
}

// 代理优先，null 表示该音质不可用
internal suspend fun resolvePlayUrlByQuality(
    sourceStore: com.yichao.evilgodxu.data.music.proxy.ProxySourceStore,
    target: NeteaseSongSearchResult,
    quality: MusicQuality,
): String? = withContext(Dispatchers.IO) {
    ProxySourceEngine.resolveUrl(sourceStore, target, quality) ?: when (target.source) {
        MusicSearchSource.NETEASE -> NeteaseMusicApi.songUrl(target.id, quality)
        MusicSearchSource.QQ -> QQMusicApi.songUrl(target.sourceId.orEmpty(), quality)
        MusicSearchSource.KUGOU -> KugouMusicApi.songUrl(target.sourceId.orEmpty())
        MusicSearchSource.KUWO -> KuwoMusicApi.songUrl(target.sourceId.orEmpty(), quality)
        else -> null
    }
}

// 自适应解析：选定档→更高档→更低档，全无则 null
private suspend fun resolveAdaptivePlayUrl(
    sourceStore: com.yichao.evilgodxu.data.music.proxy.ProxySourceStore,
    target: NeteaseSongSearchResult,
    requested: MusicQuality,
): String? = requested.adaptiveCandidates().firstNotNullOfOrNull { candidate ->
    resolvePlayUrlByQuality(sourceStore, target, candidate)?.takeIf { url ->
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
    }
}

// 按选定音质起播，标记待确认曲目供回调判成败
internal suspend fun playSearchResultWithQuality(
    target: NeteaseSongSearchResult,
    quality: MusicQuality,
    playbackState: MusicPlaybackState,
    context: Context,
    metadataEnricher: MetadataEnricher,
    playlistRefresher: PlaylistRefresher,
): Boolean {
    val url = resolveAdaptivePlayUrl(playbackState.proxySourceStore, target, quality) ?: return false
    playbackState.pendingQualityPlayTrackId = target.playlistTrackId
    playbackState.closeSearchResultsOnReady = true
    downloadAndPlay(context, playbackState, target, url, metadataEnricher, playlistRefresher)
    return true
}
