package com.yichao.evilgodxu.data.music.playback

import androidx.media3.common.Player
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.R
import kotlinx.coroutines.launch

/**
 * 播放器事件到播放状态的迁移。
 *
 * 只做「事件该让状态发生什么」的编排：切曲落地、插队队列消费、定时关闭收尾、
 * 播放结束续播与错误复位；具体持久化、封面、进度、心动模式各自交给对应组件。
 */
internal fun handleMediaItemTransition(
    state: MusicPlaybackState,
    mediaItem: androidx.media3.common.MediaItem?,
    reason: Int,
) {
    // 换曲（含单曲循环重播同一曲目）后上一曲的歌词进度不再适用
    state.lyricTimeline.invalidate()
    // 曲目自然播完即计一次完整播放，作为常听收录依据：
    // AUTO=自动续播/单曲结束切下一首；REPEAT=单曲循环重播当前曲目。
    // 手动切歌(SEEK)、列表变更(PLAYLIST_CHANGED)非自然结束，不计入。
    // 需在更新 currentTrack 前记录，此处 currentTrack 仍为刚播完的上一首。
    // 心动模式播的是 30–45 秒的副歌片段，不是一次完整播放：计入会把每首都刷成「听过的」，
    // 污染常听歌单
    if (state.playMode != PlayMode.Highlight &&
        (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
    ) {
        state.currentTrack?.id?.let { state.recordPlayed(it) }
    }
    if (state.sleepTimer.stopAfterCurrentTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
        // 定时关闭：当前曲目自然结束 → 停止播放
        state.sleepTimer.complete(songFinished = true)
        return
    }
    // 插队队列：仅自然切换时消费队列；队列播完后接续原播放位置
    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
        if (state.playNext.tracks.isNotEmpty()) {
            val queued = state.playNext.consumeFirst() ?: return
            val queuedIndex = state.playlist.indexOfFirst { it.id == queued.id }
            if (queuedIndex >= 0) {
                state.playbackScope.launch {
                    playTrackAt(
                        state.appContext ?: return@launch,
                        state,
                        queuedIndex,
                        clearQueue = false,
                        switchKind = TrackSwitchKind.Next,
                    )
                }
                return
            }
        } else {
            val resumeTrackId = state.playNext.takeResumeTrackId()
            if (resumeTrackId != null) {
                val resumeIndex = state.playlist.indexOfFirst { it.id == resumeTrackId }
                val next = state.calculateIndex(direction = 1, repeatOne = true, from = resumeIndex)
                if (next in state.playlist.indices && next != state.currentIndex) {
                    state.playbackScope.launch {
                        playTrackAt(
                            state.appContext ?: return@launch,
                            state,
                            next,
                            clearQueue = false,
                            switchKind = TrackSwitchKind.Next,
                        )
                    }
                    return
                }
            }
        }
    }
    // 待接入队列（切换歌单时当前曲目仍在播放）：播放器自然接续的曲目仍属旧队列，
    // 改由新队列的待接入位置接管，避免切歌单后继续播上一个歌单
    val pendingStart = state.pendingQueueStartIndex
    if (pendingStart != null && state.playlist.isNotEmpty() &&
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
    ) {
        state.pendingQueueStartIndex = null
        state.playbackScope.launch {
            playTrackAt(
                state.appContext ?: return@launch,
                state,
                pendingStart.coerceIn(0, state.playlist.size - 1),
                clearQueue = false,
                switchKind = TrackSwitchKind.Next,
            )
        }
        return
    }
    val id = mediaItem?.mediaId?.toLongOrNull() ?: return
    val index = state.playlist.indexOfFirst { it.id == id }
    if (index >= 0) {
        // 续播锚点：playTrackAt 以保存位置起播时已记录目标位置，本回调（异步派发）据此保留
        // 已还原的进度，避免把进度清空到 0 再回填；真实切歌（无锚点）才复位到起点。
        // 仅在锚点归属曲目上消费：无损升级就地换源时，旧源的过渡回调可能后到，
        // 提前消费会使新文件丢失续播位置
        val resumePos = if (state.resumeAnchorTrackId == id) {
            state.resumeAnchorPosition.also {
                state.resumeAnchorPosition = -1L
                state.resumeAnchorTrackId = -1L
            }
        } else {
            -1L
        }
        // 播放器自行接续（自动下一首）不经切歌入口，此处补记类型：漏记则会沿用上一次的
        // 类型，本次接续的横移方向随之失真。单曲循环重播同一曲目标识未变，不重记
        if (state.currentTrack?.id != id) state.beginTrackSwitch(TrackSwitchKind.Next)
        // 自动接续同样要在起播前落定源位深与声道（见 recordSourceBitDepthBeforePlayback）
        state.recordSourceBitDepthBeforePlayback(state.playlist[index])
        state.currentIndex = index
        state.currentTrack = state.playlist[index]
        state.isPrepared = false
        state.currentPosition = if (resumePos > 0L) resumePos else 0L
        state.duration = if (resumePos > 0L) state.playlist[index].duration else 0L
        // 切歌后进度回到起点：复位进度单调基准
        state.progress.resetMonotonicBaseline()
        // 切换曲目即持久化最新 URI，确保后台自动下一首也能被冷启动恢复
        state.persistState()
        // 切歌即落盘该曲目封面与背景取色，冷启动首帧可直接出图出背景，不依赖退出时机
        state.artworkCache.cacheCurrentCoverAndColor(state.playlist[index])
        // 切歌后主动预读新曲源格式，避免信息条等待解码回填而长时间空白
        state.appContext?.let { state.refreshIdleTrackFormatInfo(it) }
    }
    // 切换曲目后清理未在播放的在线歌曲，避免在线播放曲目在播放列表中常驻；
    // 元数据补全只在缓存完成时执行一次，切歌不再触发，避免重复写封面并触发系统级文件扫描
    state.playlistRemoval.cleanupIdleOnlineTracks()
    // 再次从控制器校正当前曲目，确保 UI 与真实音频一致（在线曲目切换时尤其关键）
    state.syncPlaybackState()
    // 心动模式：当前曲目定位不到副歌就跳过，否则为下一首预备片段
    state.highlightPolicy.onTrackSettled()
}

internal fun handlePositionDiscontinuity(state: MusicPlaybackState, reason: Int) {
    // 位置不连续即真实跳变：作废歌词时间轴快照，改由新的播放位置重新起步
    state.lyricTimeline.invalidate()
    // 手动拖动进度会触发 SEEK 类位置不连续：重置回卷检测基准，
    // 避免把"拖回开头"误判为单曲循环完整播放
    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
        state.progress.resetAfterSeek()
    }
}

internal fun handlePlaybackStateChanged(state: MusicPlaybackState, playbackState: Int) {
    state.mediaController ?: return
    when (playbackState) {
        Player.STATE_READY -> {
            state.isPrepared = true
            state.progress.ensureTicker()
            state.searchPanel.onPlaybackReady()
            state.syncPlaybackState()
        }
        Player.STATE_ENDED -> {
            state.isPlaying = false
            state.currentPosition = state.duration
            // REPEAT_MODE_OFF 播完整个时间线末尾（无切歌回调）时兜底计入完整播放；
            // 常规自然播完/单曲循环已在 onMediaItemTransition 中记录，此处不会重复。
            // 心动模式同理不计入：片段播完不构成完整播放
            if (state.playMode != PlayMode.Highlight) {
                state.currentTrack?.id?.let { state.recordPlayed(it) }
            }
            if (state.suppressAutoNext) {
                state.suppressAutoNext = false
                return
            }
            if (state.sleepTimer.stopAfterCurrentTrack) {
                // 定时关闭：曲目播毕停止播放
                state.sleepTimer.complete(songFinished = true)
                return
            }
            val next = state.autoNextIndex()
            if (next >= 0) {
                state.playbackScope.launch {
                    playTrackAt(
                        state.appContext ?: return@launch,
                        state,
                        next,
                        clearQueue = false,
                        switchKind = TrackSwitchKind.Next,
                    )
                }
            }
        }
        // 缓冲中：状态流转由 READY/ENDED 驱动，此处无需额外处理
        Player.STATE_BUFFERING -> Unit
        // 空闲态：错误/停止路径已各自复位，此处无需额外处理
        Player.STATE_IDLE -> Unit
    }
}

internal fun handlePlayerError(state: MusicPlaybackState) {
    // 音质试播失败：移除刚加入的试播曲目（不残留播放列表），保留对话框供用户换其它音质
    state.searchPanel.onPlaybackFailed(state.appContext?.getString(R.string.music_panel_quality_failed))
        ?.let(state::removeTrack)
    // errorMsg 为可空类型，appContext 为空时置 null（播放不会发生，正常显示无错误）
    state.errorMsg = state.appContext?.getString(R.string.music_panel_play_failed)
    state.isPlaying = false
    state.isPrepared = false
    state.progress.stopTicker()
    state.searchPanel.onPlaybackAborted()
    state.suppressAutoNext = true
    state.mediaController?.stop()
}
