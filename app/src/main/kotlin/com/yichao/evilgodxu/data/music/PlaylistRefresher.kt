package com.yichao.evilgodxu.data.music

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.music.analysis.AiMusicAnalyzer
import com.yichao.evilgodxu.data.music.analysis.FakeLosslessAnalyzer
import com.yichao.evilgodxu.data.music.analysis.trackFormatCategory
import com.yichao.evilgodxu.data.music.panel.normalizeTitle
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 播放列表刷新器：首页与音乐面板共用扫描入口，串行执行避免并发重复扫描
class PlaylistRefresher(private val playlistStore: PlaylistStore) {
    private val scanMutex = Mutex()

    // 扫描本地音乐并与外部曲目合并；restoreCurrent 控制扫描后是否恢复当前播放曲目
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
                val tracks = MusicScanner.scan(context)
                // 合并去重、缓存复用索引建立与排序全为纯计算，与扫描同放 IO：
                // 原先这段留在主线程，数千首的库在刷新期间会持续占用主线程
                val sortedLibrary = withContext(Dispatchers.IO) {
                    // 在线播放曲目（含已缓存）仅保留当前播放项，其余未播放的一律丢弃，
                    // 其余外部曲目（path 为空）照常合并，避免刷新后在线歌曲常驻
                    val activeId = state.currentTrack?.id
                    val externalTracks = state.playlist.filter {
                        it.path.isBlank() && (!it.isOnlinePlay || it.id == activeId)
                    }
                    // 合并去重：同一文件可能经 SAF 选择器 / 缓存下载 / MediaStore 多条 URI
                    // 形态进入列表，仅按 URI 去重会残留同文件多条目且每次刷新重新产生，故按真实文件路径合并
                    val mergedBase = (tracks + externalTracks).distinctBy { trackIdentityKey(context, it) }
                    // 缓存复用索引以全量库为准而非当前列表：停留在歌单时当前列表只是全量库子集，
                    // 仅按它建索引会丢掉库内其他歌曲的歌词缓存引用，导致切歌单后缓存污染
                    val cachedLibrary = state.libraryTracks
                    val previous = cachedLibrary.associateBy { normalizedAudioUri(it.audioUri) }
                    // 缓存下载后 audioUri 由 downloads 集合切换为 audio/media 集合，归一化后仍不一致；
                    // 按"标题 - 艺术家"兜底匹配旧列表，复用在线播放期间已保存的歌词缓存
                    val previousByTitleArtist = cachedLibrary
                        .filter { it.lyricCachePath.isNotBlank() }
                        .associateBy { titleArtistKey(it) }
                    val mergedTracks = mergedBase
                        .map { track ->
                            val cached = previous[normalizedAudioUri(track.audioUri)]
                                ?: previousByTitleArtist[titleArtistKey(track)]
                                ?: return@map track
                            track.copy(
                                neteaseId = cached.neteaseId,
                                neteaseCoverUrl = cached.neteaseCoverUrl,
                                lyricCachePath = cached.lyricCachePath,
                                lyricLines = cached.lyricLines,
                                lyricFailed = cached.lyricFailed,
                            )
                        }
                    val defaultOrdered = state.sortPlaylistForDefaultOrder(mergedTracks)
                    // 默认全量播放列表套用用户排序规则；歌单来源保持其自身顺序
                    if (state.playlistSource == null) state.sortByActiveRule(defaultOrdered) else defaultOrdered
                }
                withContext(Dispatchers.Main) {
                    // 扫描前快照当前歌单选择，扫描后按新库重建并保持选中而非回到默认
                    val selectedSource = state.playlistSource
                    val currentId = state.currentTrack?.id
                    state.applySortedPlaylist(sortedLibrary)
                    if (selectedSource != null) {
                        val rebuilt = rebuildFromSource(context, state, sortedLibrary, selectedSource)
                        if (rebuilt.isNotEmpty()) {
                            state.playlist = rebuilt
                            state.playlistSource = selectedSource
                            val newIndex = rebuilt.indexOfFirst { it.id == currentId }
                            state.currentIndex = newIndex
                            if (newIndex >= 0) state.currentTrack = rebuilt[newIndex]
                            state.defaultPlaylistBackup = sortedLibrary
                        } else {
                            state.playlistSource = null
                            state.defaultPlaylistBackup = null
                        }
                    } else {
                        state.defaultPlaylistBackup = null
                    }
                    state.persistPlaylist()
                    if (restoreCurrent) restoreCurrentTrack(state)
                }
                afterMerge()
            } finally {
                withContext(Dispatchers.Main + NonCancellable) {
                    state.isScanning = false
                }
            }
        }
    }

    // 归一化"标题 + 艺术家"作为歌词缓存复用的匹配键
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

    // 按来源 key 从全量库重建歌单，供扫描刷新后保持选中
    private suspend fun rebuildFromSource(
        context: Context,
        state: MusicPlaybackState,
        library: List<MusicTrack>,
        source: PlaylistSource,
    ): List<MusicTrack> {
        playlistStore.awaitLoaded(context)
        return when {
            source.key == "smart:RECENT" -> {
                val byId = library.associateBy { it.id }
                state.recentPlayedIds.mapNotNull { id -> byId[id] }
            }
            source.key == "smart:FAVORITE" ->
                library.filter { it.id in state.likedIds }
            source.key.startsWith("smart:FORMAT:") -> {
                val format = source.key.removePrefix("smart:FORMAT:")
                // 识别类目逐曲校验、格式类目逐曲判定都在 IO 执行，避免占用主线程
                withContext(Dispatchers.IO) {
                    if (format == FakeLosslessAnalyzer.FAKE_LOSSLESS_KEY ||
                        format == AiMusicAnalyzer.AI_MUSIC_KEY
                    ) {
                        buildList {
                            library.forEach { track ->
                                val hit = if (format == FakeLosslessAnalyzer.FAKE_LOSSLESS_KEY) {
                                    FakeLosslessAnalyzer.isSuspectedFakeLossless(context, track)
                                } else {
                                    AiMusicAnalyzer.isSuspectedAiMusic(context, track)
                                }
                                if (hit) add(track)
                            }
                        }
                    } else {
                        library.filter { trackFormatCategory(context, it) == format }
                    }
                }
            }
            source.key.startsWith("custom:") -> {
                val playlistId = source.key.removePrefix("custom:").toLongOrNull()
                    ?: return emptyList()
                val playlist = playlistStore.playlists.find { it.id == playlistId }
                    ?: return emptyList()
                // 建一次 id 索引再查表：逐个线性扫描全库会退化为 O(歌单长度 × 库大小)
                val byId = library.associateBy { it.id }
                playlist.trackIds.mapNotNull { trackId -> byId[trackId] }
            }
            source.key.startsWith("album:") -> {
                val albumId = source.key.removePrefix("album:").toLongOrNull()
                library.filter { albumId != null && it.albumId == albumId }
            }
            source.key.startsWith("artist:") ->
                library.filter { it.artist == source.key.removePrefix("artist:") }
            else -> emptyList()
        }
    }
}
