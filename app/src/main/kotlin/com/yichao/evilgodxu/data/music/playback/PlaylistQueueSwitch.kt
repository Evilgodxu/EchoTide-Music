package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.playlist.Playlist
import com.yichao.evilgodxu.data.playlist.resolveSourceTracks
import kotlinx.coroutines.launch

// 切换默认队列的过滤来源；可指定起播曲
internal fun switchToPlaylistQueue(
    context: Context,
    state: MusicPlaybackState,
    tracks: List<MusicTrack>,
    source: PlaylistSource?,
    metadataEnricher: MetadataEnricher,
    startTrackId: Long? = null,
    autoPlay: Boolean = false,
) {
    // 空来源无曲可播，保持原队列
    if (tracks.isEmpty()) return
    // 起播位置按曲目 id 反查
    val ordered = applyPlaylistQueue(state, tracks, source)
    val index = startTrackId?.let { id -> ordered.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 } ?: 0
    state.currentIndex = index
    // 在全局作用域执行，避免弹层关闭取消
    state.playbackScope.launch { playTrackAt(context, state, index, autoPlay = autoPlay) }
    state.persistPlaylist()
    // 后台补全来源缺失的封面/歌词
    state.playbackScope.launch { metadataEnricher.enrichAndCleanup(context, state) }
}

// 切换过滤来源，默认队列本体不变
internal fun switchPlaylistSource(
    context: Context,
    state: MusicPlaybackState,
    source: PlaylistSource?,
    playlists: List<Playlist>,
    albumOrders: Map<String, List<Long>>,
    artistOrders: Map<String, List<Long>>,
    favoriteOrder: List<Long>,
    metadataEnricher: MetadataEnricher,
): Boolean {
    // 已是当前来源则跳过
    if (source?.key == state.queueFilter?.key) return true
    // 过滤结果始终从默认队列解析
    val library = state.defaultPlaylist
    val tracks = if (source == null) {
        library
    } else {
        resolveSourceTracks(
            all = library,
            playlists = playlists,
            likedIds = state.likedIds,
            recentPlayedIds = state.recentPlayedIds,
            source = source,
            albumOrders = albumOrders,
            artistOrders = artistOrders,
            favoriteOrder = favoriteOrder,
        )
    }
    // 空来源无曲可播，只切换面板展示
    if (tracks.isEmpty()) return false
    val currentId = state.currentTrack?.id
    // 当前曲目是否仍出声（含缓冲）
    val currentPlaying = currentId != null && state.isPlayerActive &&
        state.mediaController?.currentMediaItem?.mediaId == currentId.toString()
    if (!currentPlaying) {
        // 未出声则直接装载；暂停中定位原曲，否则从首曲起播
        switchToPlaylistQueue(
            context = context,
            state = state,
            tracks = tracks,
            source = source,
            metadataEnricher = metadataEnricher,
            startTrackId = currentId,
            autoPlay = currentId == null,
        )
        return true
    }
    // 出声中只换过滤结果，播完再接续
    val ordered = applyPlaylistQueue(state, tracks, source)
    val index = ordered.indexOfFirst { it.id == currentId }
    state.currentIndex = index
    state.pendingQueueStartIndex = if (index >= 0) (index + 1) % ordered.size else 0
    state.clearPlayNextQueue()
    state.persistPlaylist()
    state.playbackScope.launch { metadataEnricher.enrichAndCleanup(context, state) }
    return true
}

// 写入过滤条件与结果，均按用户排序规则重排
private fun applyPlaylistQueue(
    state: MusicPlaybackState,
    tracks: List<MusicTrack>,
    source: PlaylistSource?,
): List<MusicTrack> {
    val ordered = state.sortByActiveRule(tracks)
    // 先落定过滤条件，setter 会同步默认队列
    state.queueFilter = source
    state.playlist = ordered
    return ordered
}
