package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.model.PlayMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 冷启动恢复的应用侧：把读取快照落到播放状态。
 *
 * 读取与排序由 StartupStateLoader 完成，本类只做状态赋值与首帧预置的归位；
 * 启动镜像已预置的取色结果不覆盖，避免首帧背景色回退。
 */
internal class PlaybackRestorer(private val state: MusicPlaybackState) {

    suspend fun restore(context: Context) {
        state.appContext = context
        val restored = state.startupStateLoader.load(context)
        state.searchHistoryStore.replaceAll(restored.searchHistory)
        state.recentPlayedStore.replaceAll(restored.recentPlayEvents)
        val preferences = restored.preferences
        val cachedPlaylist = restored.cachedPlaylist
        val orderedCachedPlaylist = restored.orderedPlaylist
        val cachedBackup = restored.cachedBackup
        val savedSource = restored.savedSource
        val savedSortField = restored.savedSortField
        val savedSortDescending = restored.savedSortDescending
        val savedFollowsQueue = restored.savedFollowsQueue
        val savedViewedSource = restored.savedViewedSource
        val savedState = state.playbackStateStore.read(preferences)
        val savedUri = savedState.audioUri
        val savedPosition = savedState.position
        val savedMode = savedState.modeOrdinal
        withContext(Dispatchers.Main) {
            // 无保存来源时处于全量播放列表
            state.playlistSource = savedSource
            state.applyRestoredViewedState(savedFollowsQueue, savedViewedSource)
            state.playlistSortField = savedSortField
            state.playlistSortDescending = savedSortDescending
            state.defaultPlaylistBackup = cachedBackup.takeIf { it.isNotEmpty() }
            // 收藏合并自当前歌单与默认库备份，避免切歌单后库内收藏丢失
            state.likedIds = (cachedPlaylist + cachedBackup)
                .filter { it.isFavorite }
                .map { it.id }
                .toSet()
            if (state.playlist.isEmpty() && cachedPlaylist.isNotEmpty()) {
                state.playlist = orderedCachedPlaylist.map { it.copy(isFavorite = state.likedIds.contains(it.id)) }
            }
            // 列表就绪即让首帧预置的当前曲目归位并补齐下标，此后界面不再需要二次定位
            adoptSeededCurrentTrack()
            // 播放列表缓存缺失时（如首次安装、缓存被清）首帧预置的曲目无从校验，可能已不在库中：
            // 撤下交由随后的扫描恢复路径按保存的 URI 重新定位，避免显示一首无法播放的曲目
            if (state.playlist.isEmpty()) state.currentTrack = null
            state.pendingSavedUri = savedUri
            state.pendingResumePosition = savedPosition
            // 启动镜像已为同一曲目预置取色时不覆盖，比较与采纳由 BackgroundColorMemory 承担
            state.backgroundColors.restoreFrom(preferences)
            if (state.currentTrack == null) {
                state.currentPosition = savedPosition
            }
            state.playMode = PlayMode.entries.getOrElse(savedMode) { PlayMode.RepeatAll }
        }
        // 冷启动预读上次曲目的落盘封面：驻留内存后首帧可同步取用，不阻塞本次恢复
        savedUri?.let { uri -> state.playbackScope.launch { CurrentCoverCache.load(context, uri) } }
        // 恢复后的首帧预读：曲目时长补齐、歌词与源格式提前取回，
        // 使界面重建时无需自己再编排一遍启动加载
        state.currentTrack?.let { track ->
            if (state.duration <= 0L) state.duration = track.duration
            state.requestMetadata(track)
            state.refreshIdleTrackFormatInfo(context)
        }
    }

    // 启动镜像预置的当前曲目在播放列表就绪后归位：以列表实例为准并补齐下标（首帧时列表为空，无从定位），
    // 同时保留首帧已同步读出的歌词内容，避免镜像副本与列表实例长期并存
    private fun adoptSeededCurrentTrack() {
        if (state.playlist.isEmpty()) return
        val seeded = state.currentTrack
        val index = when {
            seeded != null -> state.playlist.indexOfFirst { it.id == seeded.id }
            else -> state.pendingSavedUri?.let { uri -> state.playlist.indexOfFirst { it.audioUri == uri } } ?: -1
        }.takeIf { it >= 0 } ?: 0
        state.currentIndex = index
        val target = state.playlist[index]
        state.currentTrack = if (seeded != null && seeded.id == target.id && seeded.lyricLines.isNotEmpty()) {
            target.copy(lyricLines = seeded.lyricLines)
        } else {
            target
        }
    }
}
