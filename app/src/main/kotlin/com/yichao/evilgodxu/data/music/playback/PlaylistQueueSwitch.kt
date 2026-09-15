package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.model.MusicTrack
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
    // 首次从默认库切到歌单时备份默认列表，供快捷切回
    if (state.playlistSource == null && state.defaultPlaylistBackup == null) {
        state.defaultPlaylistBackup = state.playlist
    }
    // 默认全量列表按用户排序规则重排，起播位置必须在新顺序上按曲目 id 反查
    val ordered = if (source == null) state.sortByActiveRule(tracks) else tracks
    val index = startTrackId?.let { id -> ordered.indexOfFirst { it.id == id } }
        ?.takeIf { it >= 0 } ?: 0
    state.playlist = ordered
    state.playlistSource = source
    state.currentIndex = index
    // 在播放器全局作用域执行，避免弹层关闭取消协程导致队列未加载
    state.playbackScope.launch { playTrackAt(context, state, index, autoPlay = autoPlay) }
    state.persistPlaylist()
    // 切换歌单后后台补全新歌单缺失的封面/歌词，缓存已就绪的歌曲直接命中不重复加载
    state.playbackScope.launch { metadataEnricher.enrichAndCleanup(context, state) }
}
