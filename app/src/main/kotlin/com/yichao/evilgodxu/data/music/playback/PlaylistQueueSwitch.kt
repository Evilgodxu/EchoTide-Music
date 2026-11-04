package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.playlist.Playlist
import com.yichao.evilgodxu.data.playlist.resolveSourceTracks
import kotlinx.coroutines.launch

// 把指定来源设为默认队列的过滤条件；startTrackId 指定起播曲目，缺省从来源首曲起并保持暂停
internal fun switchToPlaylistQueue(
    context: Context,
    state: MusicPlaybackState,
    tracks: List<MusicTrack>,
    source: PlaylistSource?,
    metadataEnricher: MetadataEnricher,
    startTrackId: Long? = null,
    autoPlay: Boolean = false,
) {
    // 空来源无曲可播：保持原队列与过滤条件，面板的浏览态由调用方另行切换
    if (tracks.isEmpty()) return
    // 起播位置必须在新顺序上按曲目 id 反查
    val ordered = applyPlaylistQueue(state, tracks, source)
    val index = startTrackId?.let { id -> ordered.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 } ?: 0
    state.currentIndex = index
    // 在播放器全局作用域执行，避免弹层关闭取消协程导致队列未加载
    state.playbackScope.launch { playTrackAt(context, state, index, autoPlay = autoPlay) }
    state.persistPlaylist()
    // 切换过滤来源后后台补全来源缺失的封面/歌词，缓存已就绪的歌曲直接命中不重复加载
    state.playbackScope.launch { metadataEnricher.enrichAndCleanup(context, state) }
}

// 切换默认队列的过滤来源。默认队列本身始终不变，只替换播放的过滤结果。
// 正在播放的曲目出声期间不做装载，改在待接入位置接续，避免打断当前播放：
// 当前曲目仍在过滤结果内则接续它在新顺序中的下一曲，不在则接续过滤结果首曲
internal fun switchPlaylistSource(
    context: Context,
    state: MusicPlaybackState,
    source: PlaylistSource?,
    playlists: List<Playlist>,
    metadataEnricher: MetadataEnricher,
): Boolean {
    // 已是当前过滤来源：重复应用只会重建一份等价结果
    if (source?.key == state.queueFilter?.key) return true
    // 过滤结果始终从默认队列解析，默认队列不因来源切换而改变
    val library = state.defaultPlaylist
    val tracks = if (source == null) {
        library
    } else {
        resolveSourceTracks(library, playlists, state.likedIds, state.recentPlayedIds, source)
    }
    // 空来源无曲可播：保持原播放队列，仅由面板切到该来源展示
    if (tracks.isEmpty()) return false
    val currentId = state.currentTrack?.id
    // 当前曲目是否仍由播放器出声（含缓冲）：出声期间装载过滤结果会重建音频源打断播放
    val currentPlaying = currentId != null && state.isPlayerActive &&
        state.mediaController?.currentMediaItem?.mediaId == currentId.toString()
    if (!currentPlaying) {
        // 未出声：过滤结果直接装载。有当前曲目（暂停中）则定位到它并保持暂停，
        // 其不在过滤结果内时定位到首曲；无当前曲目则从首曲起播
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
    // 出声中：只换状态层过滤结果，播放器留待当前曲目播完再接续，当前曲目下标同步为过滤结果中的位置
    val ordered = applyPlaylistQueue(state, tracks, source)
    val index = ordered.indexOfFirst { it.id == currentId }
    state.currentIndex = index
    state.pendingQueueStartIndex = if (index >= 0) (index + 1) % ordered.size else 0
    state.clearPlayNextQueue()
    state.persistPlaylist()
    state.playbackScope.launch { metadataEnricher.enrichAndCleanup(context, state) }
    return true
}

// 把过滤来源与过滤结果写入状态层：默认队列与过滤结果都是当前播放队列，
// 均按用户排序规则重排（默认顺序时保留来源自身顺序）；过滤期间默认队列本体保持不变
private fun applyPlaylistQueue(
    state: MusicPlaybackState,
    tracks: List<MusicTrack>,
    source: PlaylistSource?,
): List<MusicTrack> {
    val ordered = state.sortByActiveRule(tracks)
    // 先落定过滤条件：无过滤时 playlist setter 会把同一结果同步为默认队列
    state.queueFilter = source
    state.playlist = ordered
    return ordered
}
