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
        // 仅网易云结果写入 neteaseId，供播放失败重试时匹配原曲
        neteaseId = if (result.source == MusicSearchSource.NETEASE) result.id else 0L,
        neteaseCoverUrl = result.coverUrl.orEmpty(),
        isOnlinePlay = true,
    )

    withContext(Dispatchers.Main) {
        val existingIndex = playbackState.playlist.indexOfFirst { it.id == trackId }
        val targetIndex = if (existingIndex >= 0) {
            // 同一在线曲目换音质重播：新解析出的直链必须写回既有条目，
            // 否则下面取 currentTrack 时又拿到旧列表项，新 URL 在起播前就被丢掉
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
            existingIndex
        } else {
            playbackState.playlist = playbackState.playlist + track
            playbackState.playlist.size - 1
        }
        // 曲目由本处先行写入，类型必须与它同帧记下：随后的 playTrackAt 因目标已是当前曲目
        // 而不再补记，本处漏记就会沿用上一次的类型，方向随之失真。
        // 搜索结果起播没有前后关系可读，类型为无方向的选曲播放
        playbackState.beginTrackSwitch(TrackSwitchKind.Select)
        playbackState.currentIndex = targetIndex
        playbackState.currentTrack = playbackState.playlist[targetIndex]
        playbackState.persistPlaylist()
        playTrackAt(context, playbackState, targetIndex)
    }

    // 在线结果后台加载歌词：各平台各有歌词接口。
    // 以 async 返回增强 LRC 文本，供缓存流程把歌词与标题/艺术家/封面一次内嵌进音频文件；
    // 异常已在块内捕获并返回 null，不会向作用域抛出
    val lyricsJob = playbackState.playbackScope.async(Dispatchers.IO) {
        try {
            val lines = ProxySourceEngine.lyricLines(context, result.source, result)
                ?: fetchPlatformLyrics(result)
            if (lines.isEmpty()) return@async null
            val lyricPath = MusicMetadataCache.saveLyrics(context, result.title, result.artist, lines).orEmpty()
            withContext(Dispatchers.Main) {
                // updateTrack 同步回写并持久化引用：仅改内存态会丢失持久化引用，
                // 进程被杀后重启清理会把刚落盘的歌词缓存当作孤儿误删
                val track = playbackState.playlist.firstOrNull { it.id == trackId } ?: return@withContext
                // 歌词已就位，清掉此前的失败标记，否则缓存文件被删后不再重建
                playbackState.updateTrack(
                    track.copy(lyricCachePath = lyricPath, lyricLines = lines, lyricFailed = false)
                )
            }
            // 与歌词缓存文件同一份增强 LRC 文本，内嵌后可被内嵌歌词读取器原样解析回来
            MusicMetadataCache.encodeLyrics(lines)
        } catch (e: Exception) {
            CrashLogManager.logException("OnlineSearchPlayback", "获取在线歌词失败", e)
            null
        }
    }

    // 在线播放时同步下载封面原图：仅以字节形式交回缓存流程，由其在缓存完成后内嵌进音频文件，
    // 系统随即为该文件生成封面略缩图（显示端只读系统略缩图，仅当前曲目的一张缩略图由 CurrentCoverCache 落盘）。
    // 在线播放由用户主动选择曲目触发，属用户决策下的联网补齐；自动补全路径不联网
    val coverJob = playbackState.playbackScope.async(Dispatchers.IO) {
        try {
            // 代理音源优先按 coverId 换取封面，失败时回退搜索结果的封面直链
            ProxySourceEngine.coverBytes(context, result)
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
        // 同一曲目的缓存任务按 ID 去重：add 的返回值才是「本次是否抢到任务」，
        // 重复起播不应再下载一遍，也不能让先完成者移除另一个任务的进行中标记
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
            // updateTrack 同步回写并持久化引用，避免进程被杀后重启清理误删刚下载的歌词缓存
            val track = playbackState.playlist.firstOrNull { it.id == track.id } ?: return@withContext
            playbackState.updateTrack(
                track.copy(
                    neteaseId = result.id,
                    neteaseCoverUrl = result.coverUrl.orEmpty(),
                    lyricCachePath = lyricPath,
                    lyricLines = lyric.lines,
                    // 取到歌词即清掉失败标记；本次没取到则保留原标记，不清也不新置
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
    // 类型须先于曲目状态记下，且与搜索结果起播同属无方向的选曲播放（见 downloadAndPlay）
    playbackState.beginTrackSwitch(TrackSwitchKind.Select)
    playbackState.currentIndex = idx
    playbackState.currentTrack = playbackState.playlist[idx]
    playbackState.isSearchMode = false
    playbackState.showSearchResults = false
    playbackState.searchQuery = ""
    playbackState.searchResults = emptyList()
    playbackState.searchPending = emptyList()
    playbackState.searchPendingFull = false
    playTrackAt(context, playbackState, idx)
    return true
}

// 代理音源优先，返回 null 表示该音质不可用
internal suspend fun resolvePlayUrlByQuality(
    context: Context,
    target: NeteaseSongSearchResult,
    quality: MusicQuality,
): String? = withContext(Dispatchers.IO) {
    ProxySourceEngine.resolveUrl(context, target, quality) ?: when (target.source) {
        MusicSearchSource.NETEASE -> NeteaseMusicApi.songUrl(target.id, quality)
        MusicSearchSource.QQ -> QQMusicApi.songUrl(target.sourceId.orEmpty(), quality)
        MusicSearchSource.KUGOU -> KugouMusicApi.songUrl(target.sourceId.orEmpty())
        MusicSearchSource.KUWO -> KuwoMusicApi.songUrl(target.sourceId.orEmpty(), quality)
        else -> null
    }
}

// 按用户选定音质自适应解析播放地址：选定档优先，其次向上匹配更高档，最后降级到更低档；
// 全部档位都无直链才返回 null（此时保留音质对话框供用户改选）
private suspend fun resolveAdaptivePlayUrl(
    context: Context,
    target: NeteaseSongSearchResult,
    requested: MusicQuality,
): String? = requested.adaptiveCandidates().firstNotNullOfOrNull { candidate ->
    resolvePlayUrlByQuality(context, target, candidate)?.takeIf { url ->
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
    }
}

// 按用户选定音质播放在线搜索结果；URL 解析成功即加入播放列表开始播放，
// 并标记待确认曲目交由播放器回调判定成败（失败时移除曲目、保留音质对话框）
internal suspend fun playSearchResultWithQuality(
    target: NeteaseSongSearchResult,
    quality: MusicQuality,
    playbackState: MusicPlaybackState,
    context: Context,
    metadataEnricher: MetadataEnricher,
    playlistRefresher: PlaylistRefresher,
): Boolean {
    val url = resolveAdaptivePlayUrl(context, target, quality) ?: return false
    playbackState.pendingQualityPlayTrackId = target.playlistTrackId
    playbackState.closeSearchResultsOnReady = true
    downloadAndPlay(context, playbackState, target, url, metadataEnricher, playlistRefresher)
    return true
}
