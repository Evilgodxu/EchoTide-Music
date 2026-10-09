package com.yichao.evilgodxu.data.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.highlight.CacheState
import com.yichao.evilgodxu.data.music.highlight.Highlight
import com.yichao.evilgodxu.data.music.highlight.HighlightResolver
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
    }
    return controller
}

fun applyPlaybackMode(controller: MediaController, mode: PlayMode) {
    controller.repeatMode = when (mode) {
        PlayMode.RepeatOne -> Player.REPEAT_MODE_ONE
        // 心动模式按列表顺序循环：片段是「把每首裁短」，不是「播哪首」，顺序语义与列表循环一致
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
    // 触发本次变更的类型：界面据此决定过渡；默认按无方向的选曲播放处理
    switchKind: TrackSwitchKind = TrackSwitchKind.Select,
) {
    state.playTrackMutex.withLock {
        // 手动切歌默认清空插队队列；仅自然接续（队列消费/自动下一首）时由调用方显式关闭
        if (clearQueue) state.clearPlayNextQueue()
        val track = state.playlist.getOrNull(index) ?: return
        // 目标曲目就是当前曲目（续播、重播当前曲）时画面不变，不记类型
        if (track.id != state.currentTrack?.id) {
            state.beginTrackSwitch(switchKind)
        }
        val controller = getController(context, state)
        // 冷启动续播不该被裁剪：保存的位置是整曲的绝对位置，裁剪后它多半落在片段之外，
        // 会被播放器钳到片段末尾、随即跳曲。故与「模式切换」同一条规则 —— 恢复中的这一首整曲播放，
        // 从下一首起才按模式裁剪
        val resumeTrackId = track
            .takeIf { it.audioUri == state.pendingSavedUri && state.pendingResumePosition > 0L }
            ?.id
        // 心动模式：目标曲目的片段先解析出来，让它自副歌起播。模式切换本身不影响正在播放的曲目
        // （见 setPlayMode），但用户新选一首属于「下一首」范畴，应当按模式裁剪
        if (state.playMode == PlayMode.Highlight) {
            HighlightResolver.resolve(context, track)
            // 缓存代表「播放器里当前是什么」，与播放器不一致会让下面的队列一致性判定误判「队列没变」
            // 而沿用旧项，于是这次切歌不带片段。故就地补丁该项 —— 续播那一首按上面的规则补成整曲
            state.patchCachedMediaItem(
                index,
                toMediaItem(track, if (track.id == resumeTrackId) null else highlightClipFor(track)),
            )
        }
        val items = state.cachedMediaItems ?: withContext(Dispatchers.IO) {
            state.playlist.map { trackItem ->
                toMediaItem(trackItem, if (trackItem.id == resumeTrackId) null else clipFor(state, trackItem))
            }.also {
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
            // 队列一致性同时校验 mediaId、URI 与裁剪区间：在线曲目缓存完成后 URI 已指向本地文件，
            // 仅比较 mediaId 会误判一致，导致播放源无法重定向（这是在线/离线切换失效的根因）；
            // 裁剪区间同理 —— 心动模式补上的片段不带进比较，就会被判成「队列没变」而跳过装载，
            // 表现为切歌后照旧整曲播放
            val sameQueue = controller.mediaItemCount == items.size &&
                    (0 until controller.mediaItemCount).all { i ->
                        val old = controller.getMediaItemAt(i)
                        old.mediaId == items[i].mediaId &&
                            old.localConfiguration?.uri?.toString() == items[i].localConfiguration?.uri?.toString() &&
                            old.clippingConfiguration == items[i].clippingConfiguration
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
        // 队列装载后补齐其余曲目的片段：只替换非当前项，不打断本次起播
        if (state.playMode == PlayMode.Highlight) {
            state.playbackScope.launch { syncHighlightQueue(context, state) }
        }
    }
}

private fun toMediaItem(track: MusicTrack, clip: Highlight? = null): MediaItem {
    val metadata = androidx.media3.common.MediaMetadata.Builder()
        .setTitle(track.title)
        .setArtist(track.artist)
    // 专辑与时长是蓝牙车机侧仅有的两个可补充字段：分别对应平台会话的 ALBUM 与 DURATION，
    // 后者即 AVRCP GetElementAttributes 的 PLAYING_TIME。两项缺失时车机分别落到空串与 0，
    // 时长未知时不填 0，交给播放器自身的时长承担进度展示
    track.albumName.takeIf { it.isNotBlank() }?.let { metadata.setAlbumTitle(it) }
    track.duration.takeIf { it > 0 }?.let { metadata.setDurationMs(it) }
    // 系统媒体面板（通知栏/锁屏/Android Auto）的封面：本地曲目给系统封面 URI（MediaProvider
    // 的专辑封面缓存，列表略缩图读的是同一份）；在线曲目给在线封面地址，由 media3 的
    // BitmapLoader 异步下载并在就绪后自动刷新通知，应用侧不自行下载、不落盘
    panelArtworkUri(track)?.let { metadata.setArtworkUri(it) }
    return MediaItem.Builder()
        .setMediaId(track.id.toString())
        .setUri(track.audioUri.toUri())
        .setMediaMetadata(metadata.build())
        // 心动模式只在片段区间内播放：裁剪交给播放器，进度、时长与 seek 边界随之都落在片段内
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
}

/**
 * 心动模式下该曲应播放的片段。
 *
 * 只查缓存、不触解析：本函数在构建队列项时被逐首调用，读盘解析会拖慢起播；
 * 未解析的曲目按整曲装载，由 [syncHighlightQueue] 在后台补齐。
 */
private fun clipFor(state: MusicPlaybackState, track: MusicTrack): Highlight? {
    if (state.playMode != PlayMode.Highlight) return null
    return highlightClipFor(track)
}

// 已解析出的片段；未解析或判定不可定位时返回 null。供播放回调同步判定，不触解析
internal fun highlightClipFor(track: MusicTrack): Highlight? {
    val state = HighlightResolver.cachedState(track.id, HighlightResolver.fingerprint(track))
    return (state as? CacheState.Found)?.highlight
}

/**
 * 心动模式下判定某曲是否「确实没有副歌」。
 *
 * 只有已解析且判定不可定位才为真 —— 尚未解析不等于没有副歌，据此跳过会在冷启动时
 * 把整库都跳过。调用方以「未解析」为「照常整曲播放」。
 */
internal fun isKnownHighlightMiss(track: MusicTrack): Boolean =
    HighlightResolver.cachedState(track.id, HighlightResolver.fingerprint(track)) is CacheState.Miss

/**
 * 心动模式下补齐队列各曲的裁剪片段。
 *
 * 队列装载时只应用了已在缓存里的片段，其余按整曲装载 —— 逐首读盘解析放到后台，
 * 既不拖慢起播，也正好对上「下一首才生效」：本函数**只替换非当前项**，
 * replaceMediaItem 对非当前项不触发重新准备，正在播放的音频不被打断。
 *
 * 代次（[MusicPlaybackState.highlightSyncGeneration]）在模式切换与队列更替时递增，
 * 在途的本轮因此会自行退出，不会把旧模式的片段写回新队列。
 */
internal suspend fun syncHighlightQueue(context: Context, state: MusicPlaybackState) {
    val generation = state.highlightSyncGeneration
    // 队列先取快照再遍历：迭代期间队列被替换会让下标与新队列错位，替换到不相干的曲目上
    val tracks = state.playlist
    tracks.forEachIndexed { index, track ->
        if (state.highlightSyncGeneration != generation) return
        if (state.playMode != PlayMode.Highlight) return
        HighlightResolver.resolve(context, track)
        val controller = state.mediaController ?: return
        withContext(Dispatchers.Main) {
            if (state.highlightSyncGeneration != generation) return@withContext
            if (state.playMode != PlayMode.Highlight) return@withContext
            if (index >= controller.mediaItemCount) return@withContext
            // 当前项不得替换：替换当前项会重新准备音频源，正是「下一首才生效」要避免的
            if (index == controller.currentMediaItemIndex) return@withContext
            val existingClip = controller.getMediaItemAt(index).clippingConfiguration
            val clip = highlightClipFor(track)
            // 已处于目标状态就不再替换：重复 replaceMediaItem 会派发多余的列表变更回调
            val matches = if (clip == null) {
                existingClip == MediaItem.ClippingConfiguration.UNSET
            } else {
                existingClip.startPositionMs == clip.startMs && existingClip.endPositionMs == clip.endMs
            }
            if (matches) return@withContext
            val replacement = toMediaItem(track, clip)
            controller.replaceMediaItem(index, replacement)
            // 缓存与播放器保持一致：不一致会让后续装载拿旧项比对而误判「队列没变」
            state.patchCachedMediaItem(index, replacement)
        }
    }
}

/**
 * 退出心动模式：把队列里已裁剪的项还原为整曲。
 *
 * 与进入时同理只动非当前项 —— 当前曲目按原样播完，不因退出模式被截断或重载。
 * 代次判定与 [syncHighlightQueue] 共用，两者在途时互相作废。
 */
internal suspend fun clearHighlightFromQueue(state: MusicPlaybackState) {
    val generation = state.highlightSyncGeneration
    val tracks = state.playlist
    tracks.forEachIndexed { index, track ->
        if (state.highlightSyncGeneration != generation) return
        if (state.playMode == PlayMode.Highlight) return
        val controller = state.mediaController ?: return
        withContext(Dispatchers.Main) {
            if (state.highlightSyncGeneration != generation) return@withContext
            if (state.playMode == PlayMode.Highlight) return@withContext
            if (index >= controller.mediaItemCount) return@withContext
            if (index == controller.currentMediaItemIndex) return@withContext
            // 已是整曲项则无需还原
            if (controller.getMediaItemAt(index).clippingConfiguration == MediaItem.ClippingConfiguration.UNSET) {
                return@withContext
            }
            val replacement = toMediaItem(track, null)
            controller.replaceMediaItem(index, replacement)
            state.patchCachedMediaItem(index, replacement)
        }
    }
}

/**
 * 为「下一首」预备片段。
 *
 * 队列装载后 [syncHighlightQueue] 会补齐全部；但它在队列很长时可能仍在进行，
 * 而下一首随时会开始 —— 切歌时对目标单独补一次，代价只有一首，且同样只替换非当前项。
 */
internal fun prepareNextHighlight(context: Context, state: MusicPlaybackState) {
    if (state.playMode != PlayMode.Highlight) return
    val nextIndex = state.nextIndex()
    val track = state.playlist.getOrNull(nextIndex) ?: return
    val controller = state.mediaController ?: return
    if (nextIndex == controller.currentMediaItemIndex) return
    state.playbackScope.launch {
        HighlightResolver.resolve(context, track)
        withContext(Dispatchers.Main) {
            if (state.playMode != PlayMode.Highlight) return@withContext
            if (nextIndex >= controller.mediaItemCount) return@withContext
            if (nextIndex == controller.currentMediaItemIndex) return@withContext
            val clip = highlightClipFor(track)
            val existing = controller.getMediaItemAt(nextIndex)
            // 已处于目标状态就不再替换：重复 replaceMediaItem 会派发多余的列表变更回调
            val matches = if (clip == null) {
                existing.clippingConfiguration == MediaItem.ClippingConfiguration.UNSET
            } else {
                existing.clippingConfiguration.startPositionMs == clip.startMs &&
                    existing.clippingConfiguration.endPositionMs == clip.endMs
            }
            if (matches) return@withContext
            val replacement = toMediaItem(track, clip)
            controller.replaceMediaItem(nextIndex, replacement)
            state.patchCachedMediaItem(nextIndex, replacement)
        }
    }
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
 * 换源期间临时关闭随机播放。
 *
 * 播放器在随机模式下重建播放顺序时会按随机序前进，替换当前项即跳到别的曲目；
 * 关闭随机让换源走列表顺序的确定性路径，完成后再恢复原有设置。
 */
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
        val newItem = toMediaItem(track, clipFor(state, track))
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
