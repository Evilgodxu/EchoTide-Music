package com.yichao.evilgodxu.data.music.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
    state.appContext = context.applicationContext
    val token = SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))
    val controller = withContext(Dispatchers.Main) {
        MediaController.Builder(context, token).buildAsync().await()
    }
    withContext(Dispatchers.Main) {
        state.mediaController = controller
        state.player = controller
        controller.addListener(state.controllerListener)
        applyPlaybackMode(controller, state.playMode)
        applyPlaybackSpeed(controller, state.playbackSpeed)
    }
    return controller
}

fun applyPlaybackSpeed(controller: MediaController, speed: Float) {
    controller.setPlaybackSpeed(speed)
}

fun applyPlaybackMode(controller: MediaController, mode: PlayMode) {
    controller.repeatMode = when (mode) {
        PlayMode.RepeatOne -> Player.REPEAT_MODE_ONE
        PlayMode.RepeatAll, PlayMode.Shuffle -> Player.REPEAT_MODE_ALL
    }
    controller.shuffleModeEnabled = mode == PlayMode.Shuffle
}

suspend fun playTrackAt(
    context: Context,
    state: MusicPlaybackState,
    index: Int,
    autoPlay: Boolean = true,
    clearQueue: Boolean = true,
) {
    state.playTrackMutex.withLock {
        // 手动切歌默认清空插队队列；仅自然接续（队列消费/自动下一首）时由调用方显式关闭
        if (clearQueue) state.clearPlayNextQueue()
        val track = state.playlist.getOrNull(index) ?: return
        val controller = getController(context, state)
        val items = state.cachedMediaItems ?: withContext(Dispatchers.IO) {
            state.playlist.map { trackItem -> toMediaItem(trackItem) }.also {
                state.cachedMediaItems = it
            }
        }

        withContext(Dispatchers.Main) {
            applyPlaybackMode(controller, state.playMode)
            // 播放器当前项即目标曲目：整体替换队列（如切换歌单）时沿用实际进度，
            // 避免只是换了队列顺序却把正在播放的曲目从头重播
            val sameTrack = controller.currentMediaItem?.mediaId == track.id.toString()
            val resumePosition = when {
                state.pendingSavedUri == track.audioUri -> state.pendingResumePosition.coerceAtLeast(0L)
                sameTrack -> controller.currentPosition.coerceAtLeast(0L)
                else -> 0L
            }
            // 续播锚点：以保存位置起播时记录目标与归属曲目，供异步派发的 onMediaItemTransition
            // 在该曲目的过渡上保留已还原进度；真实切歌（resumePosition=0）不设锚点，按常规复位到起点
            state.resumeAnchorPosition = if (resumePosition > 0L) resumePosition else -1L
            state.resumeAnchorTrackId = if (resumePosition > 0L) track.id else -1L
            // 队列一致性同时校验 mediaId 与 URI：在线曲目缓存完成后 URI 已指向本地文件，
            // 仅比较 mediaId 会误判一致，导致播放源无法重定向（这是在线/离线切换失效的根因）
            val sameQueue = controller.mediaItemCount == items.size &&
                    (0 until controller.mediaItemCount).all { i ->
                        val old = controller.getMediaItemAt(i)
                        old.mediaId == items[i].mediaId &&
                            old.localConfiguration?.uri?.toString() == items[i].localConfiguration?.uri?.toString()
                    }

            state.currentIndex = index
            state.currentTrack = track
            // 本次加载已把当前状态队列接入播放器，待接入队列随之失效
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

private fun toMediaItem(track: MusicTrack): MediaItem {
    val metadata = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
    // 系统媒体面板（通知栏/锁屏/Android Auto）的封面：本地曲目给系统封面 URI（MediaProvider
    // 的专辑封面缓存，列表略缩图读的是同一份）；在线曲目给在线封面地址，由 media3 的
    // BitmapLoader 异步下载并在就绪后自动刷新通知，应用侧不自行下载、不落盘
    panelArtworkUri(track)?.let { metadata.setArtworkUri(it) }
    return MediaItem.Builder()
        .setMediaId(track.id.toString())
        .setUri(Uri.parse(track.audioUri))
        .setMediaMetadata(metadata.build())
        .build()
}

fun togglePlayPause(state: MusicPlaybackState) {
    state.playbackScope.launch {
        val controller = state.mediaController
        if (controller == null) {
            val context = state.appContext ?: return@launch
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

fun seekTo(state: MusicPlaybackState, positionMs: Long) {
    state.mediaController?.let { controller ->
        state.playbackScope.launch { controller.seekTo(positionMs) }
    }
}

/**
 * 跳转到指定位置并开始播放。
 * 暂停状态下用歌词拖拽定位后需要直接起播；seek 与 play 分开派发时会互相竞争
 * （play 可能先于 seek 生效，出现从旧位置起播的瞬间），故合并到同一协程内顺序执行。
 */
fun seekToAndPlay(state: MusicPlaybackState, positionMs: Long) {
    state.mediaController?.let { controller ->
        state.playbackScope.launch {
            controller.seekTo(positionMs)
            controller.play()
        }
    }
}

/**
 * 无损升级完成后把当前播放项就地换成指向新无损文件的 MediaItem，按原进度继续播放。
 *
 * 用 replaceMediaItem 而非重建时间线：媒体 ID 未变，播放器不会离开当前项，
 * 也就不会进入重新准备流程，播放不中断。这也让音频信息条（读实际播放源）与系统媒体面板
 * 自然刷新为新格式。播放器尚未就绪时先 prepare，避免替换落在空闲态上不起播。
 *
 * 换源以挂起方式执行并回报结果：换源生效前播放器仍在读旧文件，
 * 返回 false 表示本次未完成换源，调用方不得删除旧文件。
 */
suspend fun swapCurrentSourceToUri(state: MusicPlaybackState, index: Int, positionMs: Long): Boolean =
    withContext(Dispatchers.Main) {
        val controller = state.mediaController ?: return@withContext false
        val track = state.playlist.getOrNull(index) ?: return@withContext false
        val current = controller.currentMediaItem ?: return@withContext false
        // 播放器已离开目标曲目：此时替换会落到别的曲目上
        if (current.mediaId != track.id.toString()) return@withContext false
        // 替换下标取播放器自身的当前项：状态层队列收缩过之后两者不再等长，沿用状态层下标会替换错项
        val playerIndex = controller.currentMediaItemIndex
        if (playerIndex == C.INDEX_UNSET) return@withContext false
        val newItem = toMediaItem(track)
        if (current.localConfiguration?.uri?.toString() == newItem.localConfiguration?.uri?.toString()) {
            return@withContext false
        }
        // 换源期间保持播放意图：正在播放的继续播放，暂停的保持暂停
        val wasPlaying = controller.isPlaying
        val resumePosition = positionMs.coerceAtLeast(0L)
        // replaceMediaItem 会派发列表变更过渡回调，据锚点保留已还原进度，避免进度条清 0 再回填
        state.resumeAnchorPosition = if (resumePosition > 0L) resumePosition else -1L
        state.resumeAnchorTrackId = if (resumePosition > 0L) track.id else -1L
        // 换源在播放作用域内执行，调用方取消不会中断换源；等它结束才算换源生效
        state.playbackScope.launch {
            controller.replaceMediaItem(playerIndex, newItem)
            if (resumePosition > 0L) controller.seekTo(resumePosition)
            if (controller.playbackState == Player.STATE_IDLE) {
                controller.prepare()
            }
            if (wasPlaying) controller.play() else controller.pause()
        }.join()
        true
    }
