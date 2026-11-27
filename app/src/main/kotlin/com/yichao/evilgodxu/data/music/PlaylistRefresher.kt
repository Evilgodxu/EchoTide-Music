package com.yichao.evilgodxu.data.music

import android.content.Context
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.playlist.AlbumOrderStore
import com.yichao.evilgodxu.data.playlist.ArtistOrderStore
import com.yichao.evilgodxu.data.playlist.FavoriteOrderStore
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.playlist.resolveSourceTracks
import com.yichao.evilgodxu.data.music.analysis.trackFormatCategory
import com.yichao.evilgodxu.data.music.metadata.EmbeddedTagBackfill
import com.yichao.evilgodxu.data.music.panel.normalizeTitle
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 刷新器：共用扫描入口，串行避免重复扫描
class PlaylistRefresher(
    private val playlistStore: PlaylistStore,
    private val albumOrderStore: AlbumOrderStore,
    private val artistOrderStore: ArtistOrderStore,
    private val favoriteOrderStore: FavoriteOrderStore,
) {
    private val scanMutex = Mutex()

    suspend fun refresh(
        context: Context,
        state: MusicPlaybackState,
        restoreCurrent: Boolean,
        afterMerge: suspend () -> Unit = {},
    ) {
        scanMutex.withLock {
            val started = withContext(Dispatchers.Main) {
                if (state.isScanning) {
                    false
                } else {
                    state.isScanning = true
                    true
                }
            }
            if (!started) return@withLock
            try {
                // 签名先于扫描取，扫描期新曲目下轮补上
                val scannedSignature = MusicScanner.signature(context)
                val tracks = MusicScanner.scan(context)
                // 合并、索引与排序放 IO，避免占用主线程
                val sortedLibrary = withContext(Dispatchers.IO) {
                    // 在线曲目仅保留当前播放项，其余丢弃
                    val activeId = state.currentTrack?.id
                    val externalTracks = state.playlist.filter {
                        it.path.isBlank() && (!it.isOnlinePlay || it.id == activeId)
                    }
                    // 按真实文件路径合并去重，避免同文件多条目
                    val mergedBase = (tracks + externalTracks).distinctBy { trackIdentityKey(context, it) }
                    // 以全量库建缓存索引，避免切歌单丢引用
                    val cachedLibrary = state.defaultPlaylist
                    val previous = cachedLibrary.associateBy { normalizedAudioUri(it.audioUri) }
                    // URI 变化后按标题+艺术家兜底匹配
                    val previousByTitleArtist = cachedLibrary
                        .filter { it.lyricCachePath.isNotBlank() }
                        .associateBy { titleArtistKey(it) }
                    val unknownArtist = context.getString(R.string.music_scanner_unknown_artist)
                    val mergedTracks = mergedBase
                        .map { track ->
                            val cached = previous[normalizedAudioUri(track.audioUri)]
                            // 只沿用上轮标签，读取交给后台补全
                            val labeled = EmbeddedTagBackfill.carryOver(track, cached, unknownArtist)
                            // 用补全后的标题匹配，否则歌词缓存丢失
                            val fallback = cached ?: previousByTitleArtist[titleArtistKey(labeled)]
                            if (fallback == null) return@map labeled
                            labeled.copy(
                                neteaseId = fallback.neteaseId,
                                neteaseCoverUrl = fallback.neteaseCoverUrl,
                                lyricCachePath = fallback.lyricCachePath,
                                lyricLines = fallback.lyricLines,
                                lyricFailed = fallback.lyricFailed,
                            )
                        }
                    // 默认队列套用户排序，来源顺序仅在过滤中
                    state.sortByActiveRule(state.sortPlaylistForDefaultOrder(mergedTracks))
                }
                withContext(Dispatchers.Main) {
                    // 保留过滤条件，按新库重建
                    val selectedFilter = state.queueFilter
                    val currentId = state.currentTrack?.id
                    // 新全量库成为默认队列
                    state.applyDefaultPlaylist(sortedLibrary)
                    if (selectedFilter != null) {
                        val rebuilt = rebuildFromSource(context, state, sortedLibrary, selectedFilter)
                        if (rebuilt.isNotEmpty()) {
                            // 排序规则作用于任何队列
                            val ordered = state.sortByActiveRule(rebuilt)
                            state.queueFilter = selectedFilter
                            state.playlist = ordered
                            val newIndex = ordered.indexOfFirst { it.id == currentId }
                            state.currentIndex = newIndex
                            if (newIndex >= 0) state.currentTrack = ordered[newIndex]
                        } else {
                            // 来源已不存在，回落默认队列
                            state.queueFilter = null
                            state.playlist = sortedLibrary
                            state.currentIndex = sortedLibrary.indexOfFirst { it.id == currentId }
                        }
                    }
                    state.persistPlaylist()
                    if (restoreCurrent) restoreCurrentTrack(state)
                }
                // 签名落盘，冷启动据此跳过扫描
                scannedSignature?.let { signature ->
                    state.writeLibrarySignature(signature)
                }
                afterMerge()
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    state.isScanning = false
                }
            }
        }
    }

    // 归一化的标题+艺术家匹配键
    private fun titleArtistKey(track: MusicTrack): String =
        normalizeTitle(track.title) + "\u0000" + normalizeTitle(track.artist)

    private fun restoreCurrentTrack(state: MusicPlaybackState) {
        if (state.playlist.isEmpty() || state.currentTrack != null) return
        val savedUri = state.pendingSavedUri
        val index = savedUri?.let { uri -> state.playlist.indexOfFirst { it.audioUri == uri } }
            ?.takeIf { it >= 0 }
            ?: 0
        state.currentIndex = index
        state.currentTrack = state.playlist[index]
    }

    // 按来源重建播放结果，口径与面板一致
    private suspend fun rebuildFromSource(
        context: Context,
        state: MusicPlaybackState,
        library: List<MusicTrack>,
        source: PlaylistSource,
    ): List<MusicTrack> = when {
        source.key.startsWith("smart:FORMAT:") -> {
            val format = source.key.removePrefix("smart:FORMAT:")
            // 格式判定放 IO，避免占用主线程
            withContext(Dispatchers.IO) {
                library.filter { trackFormatCategory(context, it) == format }
            }
        }
        else -> {
            // 曲序未读完会覆盖用户顺序，须先等待
            albumOrderStore.awaitLoaded()
            artistOrderStore.awaitLoaded()
            favoriteOrderStore.awaitLoaded()
            resolveSourceTracks(
                all = library,
                playlists = playlistStore.playlists,
                likedIds = state.likedIds,
                recentPlayedIds = state.recentPlayedIds,
                source = source,
                albumOrders = albumOrderStore.orders,
                artistOrders = artistOrderStore.orders,
                favoriteOrder = favoriteOrderStore.order,
            )
        }
    }
}
