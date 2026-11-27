package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 播放队列编辑：排序、收藏、重排、元数据替换与 ID 迁移 */
internal class PlaylistEditor(private val state: MusicPlaybackState) {

    // 默认顺序的纯计算，可在 IO 线程算好再赋值
    fun sortForDefaultOrder(tracks: List<MusicTrack>): List<MusicTrack> =
        sortTracks(
            tracks.map { it.copy(isFavorite = it.id in state.likedIds) },
            PlaylistSortField.DEFAULT,
            descending = false,
        )

    // 按当前规则重排；默认正序时原样返回
    fun sortByActiveRule(tracks: List<MusicTrack>): List<MusicTrack> {
        if (state.playlistSortField == PlaylistSortField.DEFAULT && !state.playlistSortDescending) return tracks
        return sortTracks(tracks, state.playlistSortField, state.playlistSortDescending)
    }

    // 应用排好序的默认队列，无过滤时同步播放结果
    fun applyDefaultPlaylist(sorted: List<MusicTrack>) {
        state.defaultPlaylist = sorted
        if (state.queueFilter != null) return
        applyQueueOrder(sorted)
    }

    // 按当前规则重排播放结果，后台执行
    fun setSort(field: PlaylistSortField, descending: Boolean) {
        state.playlistSortField = field
        state.playlistSortDescending = descending
        val snapshot = state.playlist
        val revision = state.playlistRevision
        val filter = state.queueFilter
        state.playbackScope.launch {
            val ordered = withContext(Dispatchers.Default) { sortTracks(snapshot, field, descending) }
            // 快照或规则已变则丢弃结果
            if (state.playlistRevision != revision ||
                state.queueFilter != filter ||
                state.playlistSortField != field ||
                state.playlistSortDescending != descending
            ) {
                return@launch
            }
            applyQueueOrder(ordered)
            state.persistPlaylist()
        }
    }

    // 应用排序并保留当前曲目索引
    private fun applyQueueOrder(sorted: List<MusicTrack>) {
        val currentId = state.currentTrack?.id
        state.playlist = sorted
        state.currentIndex = sorted.indexOfFirst { it.id == currentId }.coerceAtLeast(-1)
    }

    // 切换收藏：就地更新，默认队列一并更新
    fun toggleFavorite(trackId: Long) {
        val newLiked = if (state.likedIds.contains(trackId)) {
            state.likedIds - trackId
        } else {
            state.likedIds + trackId
        }
        state.likedIds = newLiked
        val replace: (List<MusicTrack>) -> List<MusicTrack> = { list ->
            list.map { if (it.id == trackId) it.copy(isFavorite = trackId in newLiked) else it }
        }
        state.playlist = replace(state.playlist)
        state.defaultPlaylist = replace(state.defaultPlaylist)
        state.persistPlaylist()
    }

    // 按新顺序重排并同步当前索引
    fun reorder(ordered: List<MusicTrack>) {
        if (ordered.isEmpty()) return
        val currentId = state.currentTrack?.id
        val tracks = ordered.map { it.copy(isFavorite = state.likedIds.contains(it.id)) }
        state.playlist = tracks
        state.currentIndex = tracks.indexOfFirst { it.id == currentId }
        state.persistPlaylist()
    }

    // 更新元数据，过滤结果、当前曲目与默认队列同步
    fun updateTrack(updated: MusicTrack) {
        val replace: (List<MusicTrack>) -> List<MusicTrack> = { list ->
            list.map { if (it.id == updated.id) updated.copy(isFavorite = state.likedIds.contains(it.id)) else it }
        }
        state.playlist = replace(state.playlist)
        state.currentTrack = state.currentTrack?.let { if (it.id == updated.id) updated else it }
        state.defaultPlaylist = replace(state.defaultPlaylist)
        state.persistPlaylist()
        // 通知栏读播放器项，当前曲目须刷新 MediaItem
        if (state.currentTrack?.id == updated.id) {
            state.playbackScope.launch { refreshLoadedTrackMetadata(state, updated) }
        }
    }

    /** 换 ID 后迁移收藏、常听与自定义歌单关联 */
    suspend fun migrateTrackId(oldId: Long, newId: Long) {
        if (oldId == newId) return
        if (oldId in state.likedIds) state.likedIds = (state.likedIds - oldId) + newId
        state.recentPlayedStore.migrateTrackId(oldId, newId)
        state.playlistStore.replaceTrackId(oldId, newId)
        state.persistPlaylist()
    }

    // 批量更新元数据；persist=false 不落盘
    fun batchUpdate(updates: List<MusicTrack>, persist: Boolean = true) {
        if (updates.isEmpty()) return
        val updateMap = updates.associateBy { it.id }
        val applyUpdates: (List<MusicTrack>) -> List<MusicTrack> = { list ->
            list.map { orig ->
                updateMap[orig.id]?.let { it.copy(isFavorite = state.likedIds.contains(it.id)) } ?: orig
            }
        }
        state.playlist = applyUpdates(state.playlist)
        state.currentTrack = state.currentTrack?.let { updateMap[it.id] ?: it }
        state.defaultPlaylist = applyUpdates(state.defaultPlaylist)
        if (persist) state.persistPlaylist()
    }
}
