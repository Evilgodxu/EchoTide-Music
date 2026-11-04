package com.yichao.evilgodxu.data.music

import android.content.Context
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.MusicTrack
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

// 播放列表刷新器：首页与外部音频后台播放共用扫描入口，串行执行避免并发重复扫描
class PlaylistRefresher(private val playlistStore: PlaylistStore) {
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
                // 签名先于扫描取：扫描期间新入库的曲目会让下次签名不同，从而在下一轮补上
                val scannedSignature = MusicScanner.signature(context)
                val tracks = MusicScanner.scan(context)
                // 合并去重、缓存索引与排序同放 IO：数千首的库在刷新期间会持续占用主线程，
                // 其中的内嵌标签补全还要读文件，更不能留在主线程
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
                    val cachedLibrary = state.defaultPlaylist
                    val previous = cachedLibrary.associateBy { normalizedAudioUri(it.audioUri) }
                    // 缓存下载后 audioUri 由 downloads 集合切换为 audio/media 集合，归一化后仍不一致；
                    // 按"标题 - 艺术家"兜底匹配旧列表，复用在线播放期间已保存的歌词缓存
                    val previousByTitleArtist = cachedLibrary
                        .filter { it.lyricCachePath.isNotBlank() }
                        .associateBy { titleArtistKey(it) }
                    val unknownArtist = context.getString(R.string.music_scanner_unknown_artist)
                    val mergedTracks = mergedBase
                        .map { track ->
                            val cached = previous[normalizedAudioUri(track.audioUri)]
                            // 平台读不出标签的容器（WAV）不在刷新路径读文件：
                            // 这里只沿用上轮已补出的标签，仍需读取的交给后台补全通道逐首回写
                            val labeled = EmbeddedTagBackfill.carryOver(track, cached, unknownArtist)
                            // 兜底匹配用补全后的标题与艺术家：库内保存的是补全结果，
                            // 拿扫描期的占位标题去比对会匹配不上，歌词缓存随之丢失
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
                    // 默认队列始终套用用户排序规则；来源自身顺序只在过滤结果中体现
                    state.sortByActiveRule(state.sortPlaylistForDefaultOrder(mergedTracks))
                }
                withContext(Dispatchers.Main) {
                    // 扫描前快照当前过滤条件，扫描后按新库重建并保持过滤而非回到默认
                    val selectedFilter = state.queueFilter
                    val currentId = state.currentTrack?.id
                    // 新的全量库始终成为默认队列；无过滤时同时成为播放结果
                    state.applyDefaultPlaylist(sortedLibrary)
                    if (selectedFilter != null) {
                        val rebuilt = rebuildFromSource(context, state, sortedLibrary, selectedFilter)
                        if (rebuilt.isNotEmpty()) {
                            // 排序规则作用于任何播放队列；默认顺序时保留来源自身顺序
                            val ordered = state.sortByActiveRule(rebuilt)
                            state.queueFilter = selectedFilter
                            state.playlist = ordered
                            val newIndex = ordered.indexOfFirst { it.id == currentId }
                            state.currentIndex = newIndex
                            if (newIndex >= 0) state.currentTrack = ordered[newIndex]
                        } else {
                            // 来源在扫描后的库中已不存在：清空过滤，回落到默认队列
                            state.queueFilter = null
                            state.playlist = sortedLibrary
                            state.currentIndex = sortedLibrary.indexOfFirst { it.id == currentId }
                        }
                    }
                    state.persistPlaylist()
                    if (restoreCurrent) restoreCurrentTrack(state)
                }
                // 签名随本轮结果落盘：冷启动据此判定曲库是否变过，未变则跳过整轮扫描
                scannedSignature?.let { signature ->
                    withContext(Dispatchers.IO) { state.writeLibrarySignature(context, signature) }
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

    // 按过滤来源从新全量库重建播放结果，供扫描刷新后保持过滤。
    // 非格式来源统一走 resolveSourceTracks，与面板展示、快捷切换的口径一致
    private suspend fun rebuildFromSource(
        context: Context,
        state: MusicPlaybackState,
        library: List<MusicTrack>,
        source: PlaylistSource,
    ): List<MusicTrack> {
        playlistStore.awaitLoaded(context)
        return when {
            source.key.startsWith("smart:FORMAT:") -> {
                val format = source.key.removePrefix("smart:FORMAT:")
                // 格式类目逐曲判定在 IO 执行，避免占用主线程
                withContext(Dispatchers.IO) {
                    library.filter { trackFormatCategory(context, it) == format }
                }
            }
            else -> resolveSourceTracks(
                all = library,
                playlists = playlistStore.playlists,
                likedIds = state.likedIds,
                recentPlayedIds = state.recentPlayedIds,
                source = source,
            )
        }
    }
}
