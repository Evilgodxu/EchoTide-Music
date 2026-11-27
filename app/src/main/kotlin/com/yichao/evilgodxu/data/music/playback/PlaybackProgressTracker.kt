package com.yichao.evilgodxu.data.music.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 进度跟踪：控制器进度、轮询与循环回卷检测 */
internal class PlaybackProgressTracker(
    private val scope: () -> CoroutineScope,
    private val controller: () -> MediaController?,
    private val currentTrack: () -> MusicTrack?,
    private val isPrepared: () -> Boolean,
    private val isPlaying: () -> Boolean,
    private val onPeriodicPersist: () -> Unit,
    private val highlight: HighlightFadeController,
    private val recentPlayedStore: RecentPlayedStore,
) {

    private companion object {
        // 播放期间持久化间隔 3s
        const val STATE_PERSIST_INTERVAL_MS = 3000L
        // 常规轮询 200ms，只喂进度条与落盘
        const val POSITION_TICK_MS = 200L
        // 片段期间 40ms，才够音量斜坡
        const val HIGHLIGHT_TICK_MS = 40L
        // 回退超 3s 且越过中部即一次完整播放
        const val LOOP_RESTART_MIN_JUMP_MS = 3000L
        // 回卷与过渡回调去重冷却 2s
        const val AUTO_COUNT_COOLDOWN_MS = 2000L
        // 大幅回退视为重置，小幅回退按回锚处理
        const val MONO_REBASELINE_JUMP_MS = 3000L
    }

    var duration by mutableLongStateOf(0L)
    // 单调推进的进度，供进度条；不可直接驱动歌词
    var currentPosition by mutableLongStateOf(0L)

    // 上次持久化时刻，用于节流
    private var lastStatePersistAt = 0L

    // 回卷检测上次 tick 的曲目与位置
    private var lastTickTrackId: Long? = null
    private var lastTickPosition = 0L
    // 最近完整播放记录，供去重
    private var lastAutoCountAtMs = 0L
    private var lastAutoCountTrackId: Long? = null
    // 单调基准：拖动/切歌/回卷时复位
    private var lastMonoMediaId: Long? = null
    private var lastMonoPosition = 0L

    // 全局刷新协程，避免多处轮询重复写
    private var positionTickerJob: Job? = null

    // 即时位置，连续推进无台阶，供歌词锚点
    val livePositionMs: Long
        get() = controller()?.currentPosition?.takeIf { it >= 0L } ?: currentPosition

    /** 当前项片段起点偏移，未裁剪为 0 */
    val highlightPositionOffsetMs: Long
        get() = controller()?.currentMediaItem?.clippingConfiguration
            ?.startPositionMs?.takeIf { it > 0L } ?: 0L

    /** 歌词时间轴位置：曲目内绝对时间 */
    val lyricPositionMs: Long
        get() = livePositionMs + highlightPositionOffsetMs

    /** 整曲绝对时间换算到当前项坐标 */
    fun toPlayerPosition(absoluteMs: Long): Long =
        (absoluteMs - highlightPositionOffsetMs).coerceIn(0L, duration.coerceAtLeast(0L))

    /** 可落盘位置：换算回整曲绝对时间 */
    val persistablePositionMs: Long
        get() = currentPosition + highlightPositionOffsetMs

    // 启动全局刷新，重复调用不重建
    fun ensureTicker() {
        if (positionTickerJob?.isActive == true) return
        positionTickerJob = scope().launch {
            while (isActive) {
                if (isPlaying()) {
                    updatePosition()
                    highlight.syncFadeVolume()
                }
                // 片段期间步进要细，斜坡逐格推进
                delay(if (highlight.isClipActive()) HIGHLIGHT_TICK_MS else POSITION_TICK_MS)
            }
        }
    }

    fun stopTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    fun updatePosition() {
        val controller = controller() ?: return
        val playbackState = controller.playbackState
        val isActive = playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING
        syncFromController(isActive)
        // 播放期间周期落盘，异常退出可恢复
        if (isActive && controller.isPlaying) {
            val now = System.currentTimeMillis()
            if (now - lastStatePersistAt >= STATE_PERSIST_INTERVAL_MS) {
                lastStatePersistAt = now
                onPeriodicPersist()
            }
            // 单曲循环回卷计一次完整播放
            detectLoopRestart()
        }
    }

    fun syncFromController(isActive: Boolean) {
        val controller = controller() ?: return
        if (isActive) {
            val mediaId = controller.currentMediaItem?.mediaId?.toLongOrNull()
            val controllerDuration = controller.duration
            val controllerPosition = controller.currentPosition
            if (controllerPosition >= 0L && controllerDuration > 0L) {
                val raw = controllerPosition.coerceIn(0L, controllerDuration)
                // 未 READY 时控制器可能报 0，防清 0 再回填
                if (!isPrepared() && currentPosition > 0L &&
                    raw < currentPosition - MONO_REBASELINE_JUMP_MS
                ) {
                    return
                }
                // 换项或大幅回退复位基准，小幅回退按回锚
                val reset = mediaId != lastMonoMediaId ||
                    raw < lastMonoPosition - MONO_REBASELINE_JUMP_MS
                if (reset) {
                    lastMonoMediaId = mediaId
                    lastMonoPosition = raw
                    duration = controllerDuration
                    currentPosition = raw
                } else {
                    duration = maxOf(duration, controllerDuration)
                    currentPosition = maxOf(lastMonoPosition, raw).also { lastMonoPosition = it }
                }
            }
        }
    }

    // 拖动后复位回卷与单调基准
    fun resetAfterSeek() {
        lastTickPosition = 0L
        lastMonoMediaId = null
    }

    // 切歌后复位单调基准
    fun resetMonotonicBaseline() {
        lastMonoMediaId = null
    }

    // 拖动改写位置：复位单调基准，避免被钳回
    fun seekTo(position: Long) {
        lastMonoMediaId = null
        currentPosition = position
    }

    // 释放播放器后归零：避免恢复播放时进度被残留基准钳到高位
    fun reset() {
        currentPosition = 0L
        duration = 0L
        lastMonoMediaId = null
    }

    // 循环回卷：越过中部瞬间回退到开头即计一次
    // 单曲循环可能不回调，此处兜底收录
    // 与过渡回调记录通过冷却去重，避免同一次循环计数两次
    private fun detectLoopRestart() {
        val track = currentTrack() ?: return
        if (duration <= 0L) return
        // 用原始位置检测回卷，免被单调钳制漏记
        val cur = controller()?.currentPosition
            ?.coerceIn(0L, duration) ?: return
        val trackChanged = lastTickTrackId != track.id
        lastTickTrackId = track.id
        // 切歌后的首个 tick 仅建立基准，不判定
        if (trackChanged || lastTickPosition < 0L) {
            lastTickPosition = cur
            return
        }
        val prev = lastTickPosition
        lastTickPosition = cur
        if (prev - cur < LOOP_RESTART_MIN_JUMP_MS || prev <= duration / 2) return
        val now = System.currentTimeMillis()
        val deDuplicated = lastAutoCountTrackId == track.id &&
            now - lastAutoCountAtMs < AUTO_COUNT_COOLDOWN_MS
        if (deDuplicated) return
        lastAutoCountTrackId = track.id
        lastAutoCountAtMs = now
        // 回卷复位进度单调基准，让进度条回到起点
        lastMonoMediaId = null
        recentPlayedStore.record(track.id)
    }
}
