package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.data.music.proxy.customSearchPlatforms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 冷启动恢复：把读取快照落到播放状态，保留首帧已预置取色 */
internal class PlaybackRestorer(private val state: MusicPlaybackState) {

    suspend fun restore(context: Context) {
        val restored = state.startupStateLoader.load(context)
        val preferences = restored.preferences
        val queue = restored.queue
        val cachedPlaylist = queue.tracks
        // 无过滤时套用保存排序，有过滤保持来源顺序
        val orderedCachedPlaylist = if (queue.source == null) {
            sortTracks(cachedPlaylist, queue.sortField, queue.sortDescending)
        } else {
            cachedPlaylist
        }
        val cachedBackup = queue.backup
        val savedSource = queue.source
        val savedState = state.playbackStateStore.read(preferences)
        val savedUri = savedState.audioUri
        val savedPosition = savedState.position
        val savedMode = savedState.modeOrdinal
        // 保存平台可能已移除，须按可用候选校验
        val savedSearchSource = state.searchSourceStore.read(preferences)
        val availablePlatformKeys = MusicSearchSource.builtInKeys +
            customSearchPlatforms(state.proxySourceStore.sources.value).keys
        withContext(Dispatchers.Main) {
            state.searchSource = resolveSearchSource(savedSearchSource, availablePlatformKeys)
            // 无保存过滤来源即播默认队列全集
            state.queueFilter = savedSource
            state.applyRestoredViewedState(queue.followsQueue, queue.viewedSource)
            state.playlistSortField = queue.sortField
            state.playlistSortDescending = queue.sortDescending
            // 无过滤用缓存列表，有过滤取保存的默认队列
            state.defaultPlaylist = when {
                savedSource == null -> orderedCachedPlaylist.ifEmpty { cachedBackup }
                else -> cachedBackup.ifEmpty { orderedCachedPlaylist }
            }
            // 收藏合并缓存列表与默认队列
            state.likedIds = (cachedPlaylist + state.defaultPlaylist)
                .filter { it.isFavorite }
                .map { it.id }
                .toSet()
            if (state.playlist.isEmpty()) {
                // 有过滤用缓存结果，无过滤用默认队列
                val restored = if (savedSource == null) state.defaultPlaylist else orderedCachedPlaylist
                if (restored.isNotEmpty()) {
                    state.playlist = restored.map { it.copy(isFavorite = state.likedIds.contains(it.id)) }
                }
            }
            // 列表就绪后为预置曲目归位
            adoptSeededCurrentTrack()
            // 列表为空则撤下预置曲目，交由扫描恢复
            if (state.playlist.isEmpty()) state.currentTrack = null
            state.pendingSavedUri = savedUri
            state.pendingResumePosition = savedPosition
            // 首帧已预置同曲取色则不覆盖
            state.backgroundColors.restoreFrom(preferences)
            if (state.currentTrack == null) {
                state.currentPosition = savedPosition
            }
            state.playMode = PlayMode.entries.getOrElse(savedMode) { PlayMode.RepeatAll }
        }
        // 预读上次曲目落盘封面，不阻塞恢复
        savedUri?.let { uri -> state.playbackScope.launch { CurrentCoverCache.load(context, uri) } }
        // 恢复后补时长、歌词与源格式，界面重建无需重编
        state.currentTrack?.let { track ->
            if (state.duration <= 0L) state.duration = track.duration
            state.requestMetadata(track)
            state.refreshIdleTrackFormatInfo(context)
        }
    }

    // 预置曲目归位并补下标，保留首帧歌词
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
