package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.playlist.Playlist
import com.yichao.evilgodxu.data.playlist.resolveSourceTracks
import kotlinx.coroutines.launch

// 将指定歌单设为播放队列；startTrackId 指定起播曲目，缺省从歌单首曲起并保持暂停
internal fun switchToPlaylistQueue(
    context: Context,
    state: MusicPlaybackState,
    tracks: List<MusicTrack>,
    source: PlaylistSource?,
    metadataEnricher: MetadataEnricher,
    startTrackId: Long? = null,
    autoPlay: Boolean = false,
) {
    if (tracks.isEmpty()) {
        state.playlist = tracks
        state.playlistSource = source
        state.persistPlaylist()
        return
    }
    // 起播位置必须在新顺序上按曲目 id 反查
    val ordered = applyPlaylistQueue(state, tracks, source)
    val index = startTrackId?.let { id -> ordered.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 } ?: 0
    state.currentIndex = index
    // 在播放器全局作用域执行，避免弹层关闭取消协程导致队列未加载
    state.playbackScope.launch { playTrackAt(context, state, index, autoPlay = autoPlay) }
    state.persistPlaylist()
    // 切换歌单后后台补全新歌单缺失的封面/歌词，缓存已就绪的歌曲直接命中不重复加载
    state.playbackScope.launch { metadataEnricher.enrichAndCleanup(context, state) }
}

// 切换播放列表来源：所选歌单成为播放队列。
// 正在播放的曲目出声期间不做队列装载，改在待接入位置接续，避免打断当前播放：
// 当前曲目仍在新歌单内则接续新顺序中它的下一曲，不在新歌单内则接续新歌单首曲
internal fun switchPlaylistSource(
    context: Context,
    state: MusicPlaybackState,
    source: PlaylistSource?,
    playlists: List<Playlist>,
    metadataEnricher: MetadataEnricher,
) {
    // 已是当前播放队列的歌单：重建队列只会留下一份与队列等价的全量库备份
    if (source?.key == state.playlistSource?.key) return
    // 全量库须在切换前取值：默认库备份会在写队列时就地建立
    val library = state.libraryTracks
    val tracks = if (source == null) {
        library
    } else {
        resolveSourceTracks(library, playlists, state.likedIds, state.recentPlayedIds, source)
    }
    // 空歌单无曲可播：保持原播放队列，仅由面板切到该歌单展示
    if (tracks.isEmpty()) return
    val currentId = state.currentTrack?.id
    // 当前曲目是否仍由播放器出声（含缓冲）：出声期间装载新队列会重建音频源打断播放
    val currentPlaying = currentId != null && state.isPlayerActive &&
        state.mediaController?.currentMediaItem?.mediaId == currentId.toString()
    if (!currentPlaying) {
        // 未出声：新歌单直接成为播放队列并装载。有当前曲目（暂停中）则定位到它并保持暂停，
        // 其不在新歌单内时定位到首曲；无当前曲目则从首曲起播
        switchToPlaylistQueue(
            context = context,
            state = state,
            tracks = tracks,
            source = source,
            metadataEnricher = metadataEnricher,
            startTrackId = currentId,
            autoPlay = currentId == null,
        )
        return
    }
    // 出声中：只换状态层队列，播放队列留待当前曲目播完再接续，当前曲目下标同步为新歌单中的位置
    val ordered = applyPlaylistQueue(state, tracks, source)
    val index = ordered.indexOfFirst { it.id == currentId }
    state.currentIndex = index
    state.pendingQueueStartIndex = if (index >= 0) (index + 1) % ordered.size else 0
    state.clearPlayNextQueue()
    state.persistPlaylist()
    state.playbackScope.launch { metadataEnricher.enrichAndCleanup(context, state) }
}

// 把歌单写入状态层：首次从默认库切到歌单时备份默认列表，供快捷切回；
// 默认全量列表按用户排序规则重排，返回最终队列顺序
private fun applyPlaylistQueue(
    state: MusicPlaybackState,
    tracks: List<MusicTrack>,
    source: PlaylistSource?,
): List<MusicTrack> {
    if (state.playlistSource == null && state.defaultPlaylistBackup == null) {
        state.defaultPlaylistBackup = state.playlist
    }
    val ordered = if (source == null) state.sortByActiveRule(tracks) else tracks
    state.playlist = ordered
    state.playlistSource = source
    return ordered
}
