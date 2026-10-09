package com.yichao.evilgodxu.data.music.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
        // 队列项一律由片段表推出（心动模式下带区间），装载路径不含任何解析
        val items = state.cachedMediaItems ?: withContext(Dispatchers.IO) {
            state.playlist.map { trackItem -> toMediaItem(trackItem, clipFor(state, trackItem)) }.also {
                state.cachedMediaItems = it
            }
        }

        withContext(Dispatchers.Main) {
            applyPlaybackMode(controller, state.playMode)
            // 播放器当前项即目标曲目：整体替换队列（如切换歌单）时沿用实际进度，
            // 避免只是换了队列顺序却把正在播放的曲目从头重播
            val sameTrack = controller.currentMediaItem?.mediaId == track.id.toString()
            val resumePosition = when {
                state.pendingSavedUri == track.audioUri ->
                    mapSavedResumePosition(state.playMode, track, state.pendingResumePosition)
                sameTrack -> controller.currentPosition.coerceAtLeast(0L)
                else -> 0L
            }
            // 续播锚点：以保存位置起播时记录目标与归属曲目，供异步派发的 onMediaItemTransition
            // 在该曲目的过渡上保留已还原进度；真实切歌（resumePosition=0）不设锚点，按常规复位到起点
            state.resumeAnchorPosition = if (resumePosition > 0L) resumePosition else -1L
            state.resumeAnchorTrackId = if (resumePosition > 0L) track.id else -1L
            // 队列一致性判定（含 URI 与裁剪区间，判据见 queueItemsMatch）
            val sameQueue = queueItemsMatch(controller, items)

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
 * 只查内存里的片段表，**不做任何解析** —— 表由后台扫描预先算好（见 HighlightScanner），
 * 于是本函数在构建队列项时被逐首调用也不会拖慢起播，切模式也不会有可见卡顿。
 */
private fun clipFor(state: MusicPlaybackState, track: MusicTrack): Highlight? =
    if (state.playMode == PlayMode.Highlight) HighlightStore.segmentOf(track.id) else null

/**
 * 心动模式下判定某曲是否「确实没有副歌」。
 *
 * 只有已扫描、有歌词、且定位不出重复段才为真 —— 未扫描与无歌词都不算，
 * 据此跳过会让歌词尚未补齐的曲库被整库跳过。
 */
internal fun isKnownHighlightMiss(track: MusicTrack): Boolean =
    HighlightStore.isKnownChorusMiss(track.id)

/**
 * 把落盘的绝对续播位置换算到当前项的坐标系。
 *
 * 落盘的是整曲的绝对位置，而心动模式下当前项被裁到片段上，位置须减去片段起点；
 * 保存时听的位置若落在片段之外（上次听的不是这一段），从片段起点起播 ——
 * 直接沿用绝对位置会被播放器钳到片段末尾，随即跳曲。
 */
private fun mapSavedResumePosition(mode: PlayMode, track: MusicTrack, savedAbsoluteMs: Long): Long {
    val saved = savedAbsoluteMs.coerceAtLeast(0L)
    if (mode != PlayMode.Highlight || saved <= 0L) return saved
    val clip = HighlightStore.segmentOf(track.id) ?: return saved
    if (saved !in clip.startMs until clip.endMs) return 0L
    return saved - clip.startMs
}

/**
 * 切换心动模式后即刻把新片段应用到播放队列，**包括当前正在播放的那一首**。
 *
 * 进入时当前曲目自片段起点起播，退出时把片段内进度换算回整曲的绝对位置 ——
 * 两个方向的听感都不跳，而不是等下一位生效。
 *
 * 片段全部取自持久化表，这里只做查表与重建队列，不含任何解析，故切换不会卡顿；
 * 与之相对，「模式切换不影响当前曲目」的旧行为已废弃。
 */
internal fun applyHighlightModeChange(context: Context, state: MusicPlaybackState) {
    val tracks = state.playlist
    // 队列项随模式变化，缓存一律作废；下面重建后会写回
    state.cachedMediaItems = null
    if (tracks.isEmpty()) {
        state.mediaController?.let { applyPlaybackMode(it, state.playMode) }
        return
    }
    val enteringHighlight = state.playMode == PlayMode.Highlight
    state.playbackScope.launch {
        // 先确保片段表已载入：表未载入时查表一律返回 null，会得出「整库都没有片段」的假象。
        // 已载入时这只是一次标志判断
        HighlightStore.ensureLoaded(context)
        val items = withContext(Dispatchers.IO) {
            tracks.map { toMediaItem(it, if (enteringHighlight) HighlightStore.segmentOf(it.id) else null) }
        }
        // 构建期间模式又被切回：本次结果已过期，交由后一次装载处理，避免连点后落在错误的那一版
        if ((state.playMode == PlayMode.Highlight) != enteringHighlight) return@launch
        state.cachedMediaItems = items
        val controller = state.mediaController ?: return@launch
        withContext(Dispatchers.Main) {
            // 切换前的位置必须在装载前读取：装载后控制器回报的已是新坐标系的值
            val absoluteBefore = currentClipStartMs(controller) + controller.currentPosition.coerceAtLeast(0L)
            // 起播下标以播放器为准：状态层下标在异常路径下可能落后于真实队列
            val index = controller.currentMediaItemIndex.takeIf { it in items.indices }
                ?: state.currentIndex.coerceIn(0, items.lastIndex)
            val wasPlaying = controller.isPlaying
            // 进入片段模式且当前曲确有片段：从头听副歌。其余情形（退出模式、或当前曲本就没有片段）
            // 都沿用整曲的绝对位置 —— 后者若也取 0，就会把一首本该整曲播放的歌无端从头重播
            val hasSegment = tracks.getOrNull(index)?.let { HighlightStore.segmentOf(it.id) } != null
            val target = if (enteringHighlight && hasSegment) 0L else absoluteBefore
            // 本次装载自带起播位置，续播锚点用不上，清掉以免影响随后的过渡回调
            state.resumeAnchorPosition = -1L
            state.resumeAnchorTrackId = -1L
            applyPlaybackMode(controller, state.playMode)
            // 队列项没变时（例如整个歌单都没有可裁剪的曲目）不重载时间线，只把当前项的位置摆正 ——
            // 无谓的重载会带来一次可听见的中断
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

// 播放器中当前项的片段起点：只由已装载的那一项决定，与 playMode 无关 ——
// 切换模式时模式标志已经变了，靠它判断会算错切换前的坐标系
private fun currentClipStartMs(controller: MediaController): Long =
    controller.currentMediaItem?.clippingConfiguration?.startPositionMs
        ?.takeIf { it > 0L } ?: 0L

/**
 * 播放器里已装载的队列是否与目标项逐项一致。
 *
 * 同时校验 mediaId、URI 与裁剪区间。URI 必须比：在线曲目缓存完成后 URI 已指向本地文件，
 * 只比 mediaId 会误判一致，导致播放源无法重定向（在线/离线切换失效的根因）。
 * 裁剪区间也必须比：心动模式的片段带不进比较，就会被判成「队列没变」而跳过装载，
 * 表现为开了模式却照旧整曲播放。
 */
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

/**
 * 片段表更新后，把新片段补到**已装载队列的非当前项**上。
 *
 * 表的扫描在后台进行，队列可能先一步装载，于是那一版队列项还是整曲的 —— 这正是
 * 「开了心动模式却没有片段」的来源。这里只替换非当前项：扫描是后台事件，
 * 不该把正在听的歌打断或跳回片段起点；当前项的片段等它播完自然由下一次装载带上。
 *
 * 替换后作废项缓存：缓存此时已与播放器不一致，留着会让下一次装载拿旧项比对，
 * 得出「队列没变」而把补好的片段又丢掉。
 */
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

/**
 * 跳转到指定位置并开始播放。
 * 暂停状态下拖拽进度条或歌词跳转定位后需要直接起播；seek 与 play 分开派发时会互相竞争
 * （play 可能先于 seek 生效，出现从旧位置起播的瞬间），故合并到同一协程内顺序执行。
 * 播放中调用 play 不改变播放状态，因此本接口对「播放中跳转」同样适用。
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
