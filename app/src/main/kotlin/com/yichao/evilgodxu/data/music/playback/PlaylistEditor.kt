package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放队列的编辑操作：排序、收藏、重排、元数据替换与曲目 ID 迁移。
 *
 * 只负责过滤结果与默认队列的一致性，不参与播放推进；改完由播放侧按需重新装载。
 */
internal class PlaylistEditor(private val state: MusicPlaybackState) {

    // 排序规则的纯计算部分（收藏回填 + 默认顺序）：不触碰状态，
    // 供调用方在 IO 线程算好后回主线程赋值，避免大库排序占用主线程
    fun sortForDefaultOrder(tracks: List<MusicTrack>): List<MusicTrack> =
        sortTracks(
            tracks.map { it.copy(isFavorite = it.id in state.likedIds) },
            PlaylistSortField.DEFAULT,
            descending = false,
        )

    // 按当前排序规则重排给定列表；规则为默认正序时原样返回，避免重复排序
    fun sortByActiveRule(tracks: List<MusicTrack>): List<MusicTrack> {
        if (state.playlistSortField == PlaylistSortField.DEFAULT && !state.playlistSortDescending) return tracks
        return sortTracks(tracks, state.playlistSortField, state.playlistSortDescending)
    }

    // 应用已排好序的默认队列；无过滤时播放结果与默认队列同源，一并重排并保留当前曲目索引
    fun applyDefaultPlaylist(sorted: List<MusicTrack>) {
        state.defaultPlaylist = sorted
        if (state.queueFilter != null) return
        applyQueueOrder(sorted)
    }

    // 按当前排序规则重排播放结果：默认队列与过滤结果都是当前播放队列，均可排序。
    // 重排放后台线程执行，避免大库拼音排序占用主线程
    fun setSort(field: PlaylistSortField, descending: Boolean) {
        state.playlistSortField = field
        state.playlistSortDescending = descending
        val snapshot = state.playlist
        val revision = state.playlistRevision
        val filter = state.queueFilter
        state.playbackScope.launch {
            val ordered = withContext(Dispatchers.Default) { sortTracks(snapshot, field, descending) }
            // 排序期间队列内容、过滤来源或排序规则已变：这份结果只代表旧快照，
            // 应用它会把新增/删除/改名的曲目一起回滚
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

    // 应用排序结果并保留当前曲目索引；无过滤时 playlist setter 会同步默认队列
    private fun applyQueueOrder(sorted: List<MusicTrack>) {
        val currentId = state.currentTrack?.id
        state.playlist = sorted
        state.currentIndex = sorted.indexOfFirst { it.id == currentId }.coerceAtLeast(-1)
    }

    // 切换指定曲目的收藏状态：仅就地更新收藏标记，不改变列表顺序。
    // 默认队列须一并更新：过滤结果只是默认队列的子集，只改队列会让其它来源的收藏图标不刷新。
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

    // 按新顺序重排当前播放队列，保持当前曲目与播放索引同步
    fun reorder(ordered: List<MusicTrack>) {
        if (ordered.isEmpty()) return
        val currentId = state.currentTrack?.id
        val tracks = ordered.map { it.copy(isFavorite = state.likedIds.contains(it.id)) }
        state.playlist = tracks
        state.currentIndex = tracks.indexOfFirst { it.id == currentId }
        state.persistPlaylist()
    }

    // 更新播放列表中指定曲目的元数据并持久化（过滤结果、当前曲目与默认队列同步替换）。
    // 默认队列必须一并替换：扫描刷新的缓存复用索引取自 libraryTracks，
    // 默认队列落后会在下次刷新时把旧字段（歌词等）搬回列表，抹掉刚写入的结果
    fun updateTrack(updated: MusicTrack) {
        val replace: (List<MusicTrack>) -> List<MusicTrack> = { list ->
            list.map { if (it.id == updated.id) updated.copy(isFavorite = state.likedIds.contains(it.id)) else it }
        }
        state.playlist = replace(state.playlist)
        state.currentTrack = state.currentTrack?.let { if (it.id == updated.id) updated else it }
        state.defaultPlaylist = replace(state.defaultPlaylist)
        state.persistPlaylist()
        // 通知栏与蓝牙读的是播放器里的 MediaItem：只改状态层列表不会刷新当前项，
        // 故把正在播放的同名项替换为最新标题/歌手/专辑；非当前项随下次装载自然更新
        if (state.currentTrack?.id == updated.id) {
            state.playbackScope.launch { refreshLoadedTrackMetadata(state, updated) }
        }
    }

    /**
     * 无损升级换 MediaStore ID 后迁移用户关联：收藏、最近播放与自定义歌单都按曲目 ID 记录，
     * 只更新音频 URI 而保留旧 ID，会在删旧条目后把这些关联一起丢掉。
     */
    suspend fun migrateTrackId(context: Context, oldId: Long, newId: Long) {
        if (oldId == newId) return
        if (oldId in state.likedIds) state.likedIds = (state.likedIds - oldId) + newId
        state.recentPlayedStore.migrateTrackId(oldId, newId)
        withContext(Dispatchers.IO) {
            state.playlistStore.awaitLoaded(context)
            state.playlistStore.replaceTrackId(context, oldId, newId)
        }
        state.persistPlaylist()
    }

    // 批量更新曲目元数据（封面等），一次触发重组；
    // 同时回写默认队列，保证切换过滤来源后其他来源的歌曲引用到最新封面。
    // persist=false 时只更新内存态，由调用方按节流策略统一落盘（渐进补全用，
    // 使列表能逐条刷新而不必为每条回写整份播放列表）
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
