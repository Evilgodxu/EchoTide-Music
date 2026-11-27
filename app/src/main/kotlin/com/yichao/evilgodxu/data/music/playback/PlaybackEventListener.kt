package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.media3.common.Player
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.data.music.analysis.TrackAudioInfoReader
import com.yichao.evilgodxu.data.music.analysis.formatDisplayName
import com.yichao.evilgodxu.data.music.playback.engine.EngineAudioTrackInfo
import com.yichao.evilgodxu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 播放器事件到状态的迁移：切曲、插队、定时关闭等 */
internal fun handleMediaItemTransition(
    state: MusicPlaybackState,
    mediaItem: androidx.media3.common.MediaItem?,
    reason: Int,
) {
    // 换曲后歌词进度不再适用
    state.lyricTimeline.invalidate()
    // 自然播完计一次完整播放；手动切歌与片段不计
    // 须先记录，此刻 currentTrack 仍是上一首
    if (state.playMode != PlayMode.Highlight &&
        (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
    ) {
        state.currentTrack?.id?.let { state.recordPlayed(it) }
    }
    if (state.sleepTimer.stopAfterCurrentTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
        // 定时关闭：本曲自然结束即停止
        state.sleepTimer.complete(songFinished = true)
        return
    }
    // 自然切换时消费插队队列，播完接回原位
    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
        if (state.playNext.tracks.isNotEmpty()) {
            val queued = state.playNext.consumeFirst() ?: return
            state.syncPlayNextHold()
            val queuedIndex = state.playlist.indexOfFirst { it.id == queued.id }
            if (queuedIndex >= 0) {
                state.playbackScope.launch {
                    playTrackAt(
                        state.appContext,
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
                            state.appContext,
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
    // 待接入队列接管自然接续，避免续播旧来源
    val pendingStart = state.pendingQueueStartIndex
    if (pendingStart != null && state.playlist.isNotEmpty() &&
        reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
    ) {
        state.pendingQueueStartIndex = null
        state.playbackScope.launch {
            playTrackAt(
                state.appContext,
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
        // 续播锚点：仅归属曲目上消费，避免旧源回调抢先
        val resumePos = if (state.resumeAnchorTrackId == id) {
            state.resumeAnchorPosition.also {
                state.resumeAnchorPosition = -1L
                state.resumeAnchorTrackId = -1L
            }
        } else {
            -1L
        }
        // 播放器自行接续须补记切换类型
        if (state.currentTrack?.id != id) state.beginTrackSwitch(TrackSwitchKind.Next)
        // 自动接续也须起播前落定源位深
        state.recordSourceBitDepthBeforePlayback(state.playlist[index])
        state.currentIndex = index
        state.currentTrack = state.playlist[index]
        state.isPrepared = false
        state.currentPosition = if (resumePos > 0L) resumePos else 0L
        state.duration = if (resumePos > 0L) state.playlist[index].duration else 0L
        // 复位进度单调基准
        state.progress.resetMonotonicBaseline()
        // 立即持久化 URI，供冷启动恢复
        state.persistState()
        // 落盘封面与取色，冷启动首帧出图
        state.artworkCache.cacheCurrentCoverAndColor(state.playlist[index])
        // 预读新曲源格式，避免信息条空白
        state.refreshIdleTrackFormatInfo(state.appContext)
        // 预读下一曲容器头：自动接续时配置期就要定档
        val nextIndex = state.player?.nextMediaItemIndex ?: (index + 1)
        state.playlist.getOrNull(nextIndex)?.let(state::preloadSourceFormat)
    }
    // 清理未播放的在线曲，避免常驻列表
    state.playlistRemoval.cleanupIdleOnlineTracks()
    // 从控制器校正当前曲目
    state.syncPlaybackState()
    // 心动模式：定不到副歌则跳过
    state.highlightPolicy.onTrackSettled()
}

/** 曲末暂停后的接管：记一次完整播放并消费插队队首 */
internal fun handleEndOfMediaItem(state: MusicPlaybackState) {
    // 自然播完计一次完整播放，心动不计
    if (state.playMode != PlayMode.Highlight) {
        state.currentTrack?.id?.let { state.recordPlayed(it) }
    }
    if (state.sleepTimer.stopAfterCurrentTrack) {
        state.sleepTimer.complete(songFinished = true)
        return
    }
    val queued = state.playNext.consumeFirst()
    // 先撤下曲末暂停，下一首无缝接续
    state.syncPlayNextHold()
    if (queued == null) {
        state.mediaController?.play()
        return
    }
    val queuedIndex = state.playlist.indexOfFirst { it.id == queued.id }
    if (queuedIndex < 0) {
        state.mediaController?.play()
        return
    }
    state.playbackScope.launch {
        playTrackAt(
            state.appContext,
            state,
            queuedIndex,
            clearQueue = false,
            switchKind = TrackSwitchKind.Next,
        )
    }
}

internal fun handlePositionDiscontinuity(state: MusicPlaybackState, reason: Int) {
    // 作废歌词时间轴快照
    state.lyricTimeline.invalidate()
    // 手动拖动进度：重置回卷检测基准
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
            // 无切歌回调时兜底计入完整播放，不重复
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
                        state.appContext,
                        state,
                        next,
                        clearQueue = false,
                        switchKind = TrackSwitchKind.Next,
                    )
                }
            }
        }
        // 缓冲中无需处理，由 READY/ENDED 驱动
        Player.STATE_BUFFERING -> Unit
        // 空闲态无需处理
        Player.STATE_IDLE -> Unit
    }
}

internal fun handlePlayerError(state: MusicPlaybackState) {
    // 试播失败：移除试播曲目并保留对话框
    state.searchPanel.onPlaybackFailed(state.appContext.getString(R.string.music_panel_quality_failed))
        ?.let(state::removeTrack)
    // 写入可展示的失败文案
    state.errorMsg = state.appContext.getString(R.string.music_panel_play_failed)
    state.isPlaying = false
    state.isPrepared = false
    state.progress.stopTicker()
    state.searchPanel.onPlaybackAborted()
    state.suppressAutoNext = true
    state.mediaController?.stop()
}

/** 音频轨信息迁移：解码格式、解码器归属与源格式落到播放状态 */
internal fun handleTracksChanged(
    context: Context,
    audioTrack: EngineAudioTrackInfo?,
    state: MusicPlaybackState,
) {
    val selectedFormat = audioTrack?.selectedFormat
    // 无选中项时取 0 号轨编码，说明不出声原因
    val audioMime = selectedFormat?.mimeType ?: audioTrack?.firstTrackMimeType
    val format = selectedFormat
    // 用引擎回报的当前项定位曲目，避免归属错位
    val currentTrack = audioTrack?.currentToken?.toLongOrNull()
        ?.let { mediaId -> state.playlist.firstOrNull { it.id == mediaId } }
        ?: state.currentTrack
    // 轨未被选中或换了编码时清掉残留解码器名；整组缺失时不动
    if (audioTrack != null) {
        if (selectedFormat == null ||
            (state.audioTrackMimeType != null && state.audioTrackMimeType != audioMime)
        ) {
            state.audioDecoderName = null
        }
        state.audioTrackMimeType = audioMime
        state.audioTrackDecodable = if (audioTrack.trackCount == 0) null else selectedFormat != null
        if (selectedFormat == null && audioMime != null) {
            // 无解码器时不报错，须在面板明确告知
            val formatName = TrackAudioInfoReader.mimeToFormatName(audioMime) ?: audioMime
            state.errorMsg = context.getString(
                R.string.music_panel_decode_unsupported,
                formatName,
            )
        }
    }
    val fileFormat = format?.let { f ->
        formatDisplayName(currentTrack?.path, f.mimeType)
    }
    if (format != null) {
        // 采样率/声道取解码实际值，缺失留空
        val decodedSampleRate = format.sampleRate.takeIf { it > 0 }
        // 优先沿用源位深/声道，归属含音源 URI
        val sourceFormat = state.audioSignalPathFormat
            .takeIf {
                state.audioSignalPathTrackId == currentTrack?.id &&
                    state.audioSignalPathSourceUri == currentTrack?.audioUri
            }
        val bitDepth = sourceFormat?.bitDepth
            ?: currentTrack?.takeIf { it.isLocalAudioSource }
                ?.let { TrackAudioInfoReader.readContainerFormat(context, it)?.bitDepth }
        state.audioSignalPathFormat = AudioSignalPathFormat(
            format = fileFormat,
            sampleRate = decodedSampleRate,
            outputRate = decodedSampleRate,
            bitDepth = bitDepth,
            channels = sourceFormat?.channels ?: format.channelCount.takeIf { it > 0 },
            // bitrate 单位 bps；VBR 取均值
            bitrate = maxOf(format.bitrate, format.averageBitrate)
                .takeIf { it > 0 }
                ?.let { it / 1000 },
        )
        state.audioSignalPathTrackId = currentTrack?.id
        state.audioSignalPathSourceUri = currentTrack?.audioUri
    }
    // 未给出比特率时异步读取回填（FLAC/VBR 常见）
    if (state.audioSignalPathFormat?.bitrate == null) {
        val track = currentTrack
        state.playbackScope.launch(Dispatchers.IO) {
            if (track != null && track.isLocalAudioSource &&
                state.audioSignalPathTrackId == track.id &&
                state.audioSignalPathSourceUri == track.audioUri
            ) {
                TrackAudioInfoReader.readBitrateKbps(context, track)?.let { bitrate ->
                    if (state.audioSignalPathFormat?.bitrate == null) {
                        state.audioSignalPathFormat = state.audioSignalPathFormat?.copy(bitrate = bitrate)
                    }
                }
            }
        }
    }
}
