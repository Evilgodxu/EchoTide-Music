package com.yichao.evilgodxu.data.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.highlight.Highlight
import com.yichao.evilgodxu.data.music.highlight.HighlightStore
import com.yichao.evilgodxu.data.music.metadata.panelArtworkUri
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.service.MusicPlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private suspend fun getController(context: Context, state: MusicPlaybackState): MediaController {
    state.mediaController?.let { return it }
    val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
    val controller = withContext(Dispatchers.Main) {
        MediaController.Builder(context, token).buildAsync().await()
    }
    withContext(Dispatchers.Main) {
        state.mediaController = controller
        state.player = controller
        controller.addListener(state.controllerListener)
        applyPlaybackMode(controller, state.playMode)
    }
    return controller
}

fun applyPlaybackMode(controller: MediaController, mode: PlayMode) {
    controller.repeatMode = when (mode) {
        PlayMode.RepeatOne -> Player.REPEAT_MODE_ONE
        // 心动模式按列表循环，片段不是「播哪首」
        PlayMode.RepeatAll, PlayMode.Shuffle, PlayMode.Highlight -> Player.REPEAT_MODE_ALL
    }
    controller.shuffleModeEnabled = mode == PlayMode.Shuffle
}

suspend fun playTrackAt(
    context: Context,
    state: MusicPlaybackState,
    index: Int,
    autoPlay: Boolean = true,
    clearQueue: Boolean = true,
    // 本次变更类型，界面据此决定过渡
    switchKind: TrackSwitchKind = TrackSwitchKind.Select,
) {
    state.playTrackMutex.withLock {
        // 手动切歌清空插队队列，自然接续由调用方关闭
        if (clearQueue) state.clearPlayNextQueue()
        val track = state.playlist.getOrNull(index) ?: return
        // 目标即当前曲时不记类型
        if (track.id != state.currentTrack?.id) {
            state.beginTrackSwitch(switchKind)
        }
        val controller = getController(context, state)
        // 起播前落定源位深与声道，避免轨道回调同步读头
        withContext(Dispatchers.IO) { state.recordSourceBitDepthBeforePlayback(track) }
        // 队列项由片段表推出，装载不含解析
        val items = state.cachedMediaItems ?: withContext(Dispatchers.IO) {
            state.playlist.map { trackItem -> toMediaItem(trackItem, clipFor(state, trackItem)) }.also {
                state.cachedMediaItems = it
            }
        }

        withContext(Dispatchers.Main) {
            applyPlaybackMode(controller, state.playMode)
            // 播放器已在目标曲上时沿用实际进度，避免从头重播
            val sameTrack = controller.currentMediaItem?.mediaId == track.id.toString()
            val resumePosition = when {
                state.pendingSavedUri == track.audioUri ->
                    mapSavedResumePosition(state.playMode, track, state.pendingResumePosition)
                sameTrack -> controller.currentPosition.coerceAtLeast(0L)
                else -> 0L
            }
            // 以保存位置起播时记锚点，真实切歌不设
            state.resumeAnchorPosition = if (resumePosition > 0L) resumePosition else -1L
            state.resumeAnchorTrackId = if (resumePosition > 0L) track.id else -1L
            // 队列一致性含 URI 与裁剪区间
            val sameQueue = queueItemsMatch(controller, items)

            state.currentIndex = index
            state.currentTrack = track
            // 队列已接入播放器，待接入位置失效
            state.pendingQueueStartIndex = null
            state.errorMsg = null
            if (!sameQueue) {
                controller.setMediaItems(items, index, resumePosition)
                controller.prepare()
            } else if (!sameTrack) {
                controller.seekToDefaultPosition(index)
            } else if (resumePosition > 0L && controller.currentPosition == 0L) {
                controller.seekTo(resumePosition)
            }
            if (autoPlay) {
                controller.play()
            } else {
                controller.pause()
            }
            state.pendingSavedUri = null
            state.pendingResumePosition = 0L
        }
    }
}

private fun toMediaItem(track: MusicTrack, clip: Highlight? = null): MediaItem =
    MediaItem.Builder()
        .setMediaId(track.id.toString())
        .setUri(track.audioUri.toUri())
        .setMediaMetadata(mediaMetadataFor(track))
        // 心动模式只在片段内播放，进度与时长随之裁剪
        .apply {
            if (clip != null) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.startMs)
                        .setEndPositionMs(clip.endMs)
                        .build()
                )
            }
        }
        .build()

// 播放器项展示元数据，含系统面板封面
private fun mediaMetadataFor(track: MusicTrack): MediaMetadata {
    val metadata = MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
    // ALBUM 与 PLAYING_TIME，未知不填
    track.albumName.takeIf { it.isNotBlank() }?.let { metadata.setAlbumTitle(it) }
    track.duration.takeIf { it > 0 }?.let { metadata.setDurationMs(it) }
    // 本地曲给系统封面 URI，在线曲给地址
    panelArtworkUri(track)?.let { metadata.setArtworkUri(it) }
    return metadata.build()
}

/** 心动模式下该曲的片段，只查表不解析 */
private fun clipFor(state: MusicPlaybackState, track: MusicTrack): Highlight? =
    if (state.playMode == PlayMode.Highlight) HighlightStore.segmentOf(track.id) else null

/** 已扫描、有歌词且定位不出副歌才为真 */
internal fun isKnownHighlightMiss(track: MusicTrack): Boolean =
    HighlightStore.isKnownChorusMiss(track.id)

/** 把整曲绝对续播位置换算到当前片段坐标 */
private fun mapSavedResumePosition(mode: PlayMode, track: MusicTrack, savedAbsoluteMs: Long): Long {
    val saved = savedAbsoluteMs.coerceAtLeast(0L)
    if (mode != PlayMode.Highlight || saved <= 0L) return saved
    val clip = HighlightStore.segmentOf(track.id) ?: return saved
    if (saved !in clip.startMs until clip.endMs) return 0L
    return saved - clip.startMs
}

/** 切换心动模式后立即重建队列，含当前曲，两向都不跳 */
internal fun applyHighlightModeChange(context: Context, state: MusicPlaybackState) {
    val tracks = state.playlist
    // 队列项随模式变化，缓存作废
    state.cachedMediaItems = null
    if (tracks.isEmpty()) {
        state.mediaController?.let { applyPlaybackMode(it, state.playMode) }
        return
    }
    val enteringHighlight = state.playMode == PlayMode.Highlight
    state.playbackScope.launch {
        // 确保片段表已载入，否则会误判整库无片段
        HighlightStore.ensureLoaded(context)
        val items = withContext(Dispatchers.IO) {
            tracks.map { toMediaItem(it, if (enteringHighlight) HighlightStore.segmentOf(it.id) else null) }
        }
        // 构建期间模式又变了，结果作废
        if ((state.playMode == PlayMode.Highlight) != enteringHighlight) return@launch
        state.cachedMediaItems = items
        val controller = state.mediaController ?: return@launch
        withContext(Dispatchers.Main) {
            // 装载前读取位置，装载后已是新坐标
            val absoluteBefore = currentClipStartMs(controller) + controller.currentPosition.coerceAtLeast(0L)
            // 起播下标以播放器为准
            val index = controller.currentMediaItemIndex.takeIf { it in items.indices }
                ?: state.currentIndex.coerceIn(0, items.lastIndex)
            val wasPlaying = controller.isPlaying
            // 进入片段模式且有片段则从头听；否则沿用整曲位置
            val hasSegment = tracks.getOrNull(index)?.let { HighlightStore.segmentOf(it.id) } != null
            val target = if (enteringHighlight && hasSegment) 0L else absoluteBefore
            // 本次自带起播位置，清掉锚点
            state.resumeAnchorPosition = -1L
            state.resumeAnchorTrackId = -1L
            applyPlaybackMode(controller, state.playMode)
            // 队列未变则不重载，只摆正位置
            if (queueItemsMatch(controller, items)) {
                if (controller.currentPosition != target) controller.seekTo(target)
            } else {
                controller.setMediaItems(items, index, target)
                controller.prepare()
            }
            if (wasPlaying) controller.play() else controller.pause()
        }
        state.persistState()
    }
}

// 当前项片段起点，只由已装载项决定
private fun currentClipStartMs(controller: MediaController): Long =
    controller.currentMediaItem?.clippingConfiguration?.startPositionMs
        ?.takeIf { it > 0L } ?: 0L

/** 队列逐项一致：须比 mediaId、URI 与裁剪区间 */
private fun queueItemsMatch(controller: MediaController, items: List<MediaItem>): Boolean {
    if (controller.mediaItemCount != items.size) return false
    return (0 until items.size).all { index ->
        val loaded = controller.getMediaItemAt(index)
        val target = items[index]
        loaded.mediaId == target.mediaId &&
            loaded.localConfiguration?.uri?.toString() == target.localConfiguration?.uri?.toString() &&
            loaded.clippingConfiguration == target.clippingConfiguration
    }
}

/** 片段表更新后补到非当前项，避免打断播放；替换后作废缓存 */
internal fun applyNewClipsToLoadedQueue(state: MusicPlaybackState) {
    if (state.playMode != PlayMode.Highlight) return
    val controller = state.mediaController ?: return
    val tracks = state.playlist
    state.playbackScope.launch {
        withContext(Dispatchers.Main) {
            if (state.playMode != PlayMode.Highlight) return@withContext
            var replaced = false
            tracks.forEachIndexed { index, track ->
                if (index >= controller.mediaItemCount) return@forEachIndexed
                if (index == controller.currentMediaItemIndex) return@forEachIndexed
                val desired = toMediaItem(track, HighlightStore.segmentOf(track.id))
                if (controller.getMediaItemAt(index).clippingConfiguration == desired.clippingConfiguration) {
                    return@forEachIndexed
                }
                controller.replaceMediaItem(index, desired)
                replaced = true
            }
            if (replaced) state.cachedMediaItems = null
        }
    }
}


fun togglePlayPause(state: MusicPlaybackState) {
    state.playbackScope.launch {
        val controller = state.mediaController
        if (controller == null) {
            val context = state.appContext
            val index = state.currentIndex
            if (index >= 0) {
                // 恢复当前曲目而非切歌，保留插队队列
                playTrackAt(context, state, index, clearQueue = false)
            }
            return@launch
        }
        if (controller.isPlaying) controller.pause() else controller.play()
    }
}

/**
 * 定位并起播：seek、play 顺序执行。
 * 无控制器时定位改由装载起始位置完成。
 */
fun seekToAndPlay(state: MusicPlaybackState, positionMs: Long) {
    val controller = state.mediaController
    if (controller == null) {
        playTrackAtPosition(state, positionMs)
        return
    }
    state.playbackScope.launch {
        controller.seekTo(positionMs)
        controller.play()
    }
}

/** 无控制器时按指定位置装载，锚点用整曲时间 */
private fun playTrackAtPosition(state: MusicPlaybackState, positionMs: Long) {
    val context = state.appContext
    val index = state.currentIndex
    val track = state.playlist.getOrNull(index) ?: return
    state.pendingSavedUri = track.audioUri
    state.pendingResumePosition = positionMs
    state.playbackScope.launch { playTrackAt(context, state, index, clearQueue = false) }
}

/** 换源期间临时关闭随机，走确定性路径 */
private fun withoutShuffle(controller: MediaController, block: () -> Unit) {
    if (!controller.shuffleModeEnabled) {
        block()
        return
    }
    controller.shuffleModeEnabled = false
    try {
        block()
    } finally {
        controller.shuffleModeEnabled = true
    }
}

/** 就地替换队列中该曲的项，不重建时间线；返回是否已无旧项 */
internal suspend fun replaceLoadedTrackSource(state: MusicPlaybackState, track: MusicTrack): Boolean =
    withContext(Dispatchers.Main) {
        val controller = state.mediaController ?: return@withContext true
        var currentStillOld = false
        for (index in 0 until controller.mediaItemCount) {
            val loaded = controller.getMediaItemAt(index)
            if (loaded.mediaId != track.id.toString()) continue
            if (index == controller.currentMediaItemIndex) {
                // 当前项由调用方按进度换源，这里不动
                currentStillOld = true
                continue
            }
            controller.replaceMediaItem(index, toMediaItem(track, clipFor(state, track)))
        }
        state.cachedMediaItems = null
        !currentStillOld
    }

/** 元数据变更后刷新队列同名项，只换元数据不重建 */
internal suspend fun refreshLoadedTrackMetadata(state: MusicPlaybackState, track: MusicTrack) =
    withContext(Dispatchers.Main) {
        val controller = state.mediaController ?: return@withContext
        val metadata = mediaMetadataFor(track)
        for (index in 0 until controller.mediaItemCount) {
            val loaded = controller.getMediaItemAt(index)
            if (loaded.mediaId != track.id.toString()) continue
            if (loaded.mediaMetadata == metadata) continue
            controller.replaceMediaItem(index, loaded.buildUpon().setMediaMetadata(metadata).build())
        }
        state.cachedMediaItems = null
    }

/** 当前项就地换成新无损文件并按原进度续播；返回是否生效 */
suspend fun swapCurrentSourceToUri(state: MusicPlaybackState, index: Int, positionMs: Long): Boolean =
    withContext(Dispatchers.Main) {
        val controller = state.mediaController ?: return@withContext false
        val track = state.playlist.getOrNull(index) ?: return@withContext false
        val current = controller.currentMediaItem ?: return@withContext false
        // 播放器已离开目标曲，替换会落错项
        if (current.mediaId != track.id.toString()) return@withContext false
        // 下标取播放器当前项，状态层可能已收缩
        val playerIndex = controller.currentMediaItemIndex
        if (playerIndex == C.INDEX_UNSET) return@withContext false
        val newItem = toMediaItem(track, clipFor(state, track))
        if (current.localConfiguration?.uri?.toString() == newItem.localConfiguration?.uri?.toString()) {
            return@withContext false
        }
        // 保持播放意图
        val wasPlaying = controller.isPlaying
        val resumePosition = positionMs.coerceAtLeast(0L)
        // 记锚点防进度清 0 再回填
        state.resumeAnchorPosition = if (resumePosition > 0L) resumePosition else -1L
        state.resumeAnchorTrackId = if (resumePosition > 0L) track.id else -1L
        // 在播放作用域执行，调用方取消不中断
        state.playbackScope.launch {
            withoutShuffle(controller) {
                controller.replaceMediaItem(playerIndex, newItem)
                if (resumePosition > 0L) controller.seekTo(resumePosition)
                if (controller.playbackState == Player.STATE_IDLE) {
                    controller.prepare()
                }
                if (wasPlaying) controller.play() else controller.pause()
            }
        }.join()
        true
    }
