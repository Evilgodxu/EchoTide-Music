package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.CoverColorCache
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.LargeCoverStore
import com.yichao.evilgodxu.data.music.metadata.MusicCoverLoader
import com.yichao.evilgodxu.data.music.metadata.extractCoverBackgroundColors
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 封面预热与取色：切歌落盘，扫描后补，预取单任务 */
internal class ArtworkCacheController(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
    private val currentTrack: () -> MusicTrack?,
    private val nextTrack: () -> MusicTrack?,
    private val previousTrack: () -> MusicTrack?,
    private val libraryTracks: () -> List<MusicTrack>,
    private val colorMemory: BackgroundColorMemory,
) {

    private companion object {
        // 切歌预取稳定期，快速连切时等于不预取
        const val COVER_PREFETCH_SETTLE_MS = 2_000L
    }

    // 切歌预取任务：新一轮先取消上一轮，并发有上界
    private var coverPrefetchJob: Job? = null

    // 扫描预热任务：新一轮取消上一轮
    private var artworkWarmupJob: Job? = null

    /** 切歌后落盘缩略图并持久化取色，同时预取相邻曲高清封面 */
    fun cacheCurrentCoverAndColor(track: MusicTrack) {
        val context = context() ?: return
        scope().launch {
            val bitmap = CurrentCoverCache.ensure(context, track.audioUri) {
                MusicCoverLoader.load(context, track, CurrentCoverCache.THUMBNAIL_SIZE)
            } ?: return@launch
            // 已切走则不落盘，避免顶掉恢复色
            if (currentTrack()?.audioUri != track.audioUri) return@launch
            // 取色优先用预取结果，保证与实时背景一致
            val colors = CoverColorCache.get(track.audioUri)
                ?: extractCoverBackgroundColors(bitmap)
                ?: return@launch
            colorMemory.saveFor(track.audioUri, colors)
        }
        coverPrefetchJob?.cancel()
        coverPrefetchJob = scope().launch {
            delay(COVER_PREFETCH_SETTLE_MS)
            LargeCoverStore.prefetch(context, neighboringTracks(track))
        }
    }

    /** 扫描完成后预热封面：按专辑去重，取色只算当前曲 */
    fun warmUpArtwork(context: Context) {
        val tracks = libraryTracks()
        if (tracks.isEmpty()) return
        currentTrack()?.let(::cacheCurrentCoverAndColor)
        val currentId = currentTrack()?.id
        artworkWarmupJob?.cancel()
        artworkWarmupJob = scope().launch {
            tracks.asSequence()
                .filter { it.id != currentId }
                .distinctBy { if (it.albumId > 0L) "album:${it.albumId}" else "track:${it.id}" }
                .forEach { representative ->
                    if (!isActive) return@launch
                    MusicCoverLoader.prefetch(context, representative, CurrentCoverCache.THUMBNAIL_SIZE)
                }
        }
    }

    // 按播放次序取当前、下一、上一曲，顺序即预取优先级
    private fun neighboringTracks(track: MusicTrack): List<MusicTrack> =
        listOfNotNull(track, nextTrack(), previousTrack()).distinctBy { it.id }
}
