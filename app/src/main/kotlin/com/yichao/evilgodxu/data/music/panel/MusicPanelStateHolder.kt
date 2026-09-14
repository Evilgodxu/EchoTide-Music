package com.yichao.evilgodxu.data.music.panel

import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState

// 音乐面板全局状态持有者，保证面板关闭后播放状态不丢失，
// 并在未播放时释放 ExoPlayer 资源。由 App 以单例持有，全库共享同一实例。
class MusicPanelStateHolder(
    private val metadataEnricher: MetadataEnricher,
    private val playlistStore: PlaylistStore,
) {
    val state = MusicPlaybackState(metadataEnricher, playlistStore)

    fun releaseIfIdle() {
        if (!state.isPlayerActive) {
            state.softRelease()
        }
    }
}
