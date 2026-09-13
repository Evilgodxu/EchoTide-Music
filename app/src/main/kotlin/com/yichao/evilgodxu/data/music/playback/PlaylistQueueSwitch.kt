package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.launch

// 切换到指定歌单队列并播放该歌单第一首歌曲
internal fun switchToPlaylistQueue(
    context: Context,
    state: MusicPlaybackState,
    tracks: List<MusicTrack>,
    source: PlaylistSource?,
    metadataEnricher: MetadataEnricher,
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
    state.playlist = if (source == null) state.sortByActiveRule(tracks) else tracks
    state.playlistSource = source
    state.currentIndex = 0
    // 仅加载新队列并暂停，不自动播放；在播放器全局作用域执行，避免弹层关闭取消协程导致队列未加载
    state.playbackScope.launch { playTrackAt(context, state, 0, autoPlay = false) }
    state.persistPlaylist()
    // 切换歌单后后台补全新歌单缺失的封面/歌词，缓存已就绪的歌曲直接命中不重复加载
    state.playbackScope.launch { metadataEnricher.enrichAndCleanup(context, state) }
}
