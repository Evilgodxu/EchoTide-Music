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

/**
 * 播放进度跟踪：控制器回报到界面进度、周期轮询、单曲循环回卷检测。
 *
 * 进度是控制器回报的「峰值包络」——连续播放期间只增不减，规避流媒体位置回锚/时长修正导致进度条倒退；
 * 手动拖动、切歌、单曲循环回卷时复位基准，允许进度回落。
 *
 * 心动模式下控制器回报的是片段内进度，而歌词时间轴用整曲绝对时间；换算只在这里做一次，
 * 下游的歌词面板、悬浮窗与逐字对齐都按绝对时间工作。进度条反其道而行，取片段内相对进度。
 */
internal class PlaybackProgressTracker(
    private val scope: () -> CoroutineScope,
    private val controller: () -> MediaController?,
    private val currentTrack: () -> MusicTrack?,
    private val isPrepared: () -> Boolean,
    private val isPlaying: () -> Boolean,
    private val isHighlightClipActive: () -> Boolean,
    private val onPlayingChanged: (Boolean) -> Unit,
    private val onPeriodicPersist: () -> Unit,
    private val onPlaybackCompleted: (trackId: Long) -> Unit,
    private val onTick: () -> Unit,
) {

    private companion object {
        // 播放期间周期性持久化间隔：保证冷启动/异常退出也能恢复当前曲目与进度
        const val STATE_PERSIST_INTERVAL_MS = 3000L
        // 常规进度轮询周期：位置只喂进度条与持久化，200ms 足够
        const val POSITION_TICK_MS = 200L
        // 片段播放期间的进度轮询周期：首尾的音量淡入淡出要逐格推进，
        // 沿用 200ms 的台阶会让斜坡听成几次跳变
        const val HIGHLIGHT_TICK_MS = 40L
        // 单曲循环回卷判定：位置回退超过该值且曾越过曲目中部，视为一次完整播放
        const val LOOP_RESTART_MIN_JUMP_MS = 3000L
        // 回卷检测与过渡回调记录的去重冷却：同一次循环只计入一次完整播放
        const val AUTO_COUNT_COOLDOWN_MS = 2000L
        // 进度单调复位兜底：未触发切歌/拖动回调但位置大幅回退（如切换歌单重载同 ID 曲目）时视为重置；
        // 小幅回退仍按流媒体回锚处理，保持进度单调
        const val MONO_REBASELINE_JUMP_MS = 3000L
    }

    var duration by mutableLongStateOf(0L)
    // 播放位置：按控制器回报单调推进（真实跳变除外，见 syncPosition），供进度条使用。
    // 它是控制器回报的「峰值包络」——只在回报超过历史最大值时才前进，
    // 故表现为一跳一跳的台阶，不适合直接驱动歌词渲染
    var currentPosition by mutableLongStateOf(0L)

    // 上次持久化播放状态的时刻，用于播放期间节流写入
    private var lastStatePersistAt = 0L

    // 单曲循环回卷检测状态：追踪上次 tick 的播放位置，位置大幅回退即一次完整播放
    private var lastTickTrackId: Long? = null
    private var lastTickPosition = 0L
    // 最近一次完整播放记录时间与曲目，供回卷检测与过渡回调去重
    private var lastAutoCountAtMs = 0L
    private var lastAutoCountTrackId: Long? = null
    // 进度展示单调性：连续播放期间进度与时长只增不减，规避流媒体位置回锚/时长修正导致进度条倒退；
    // 手动拖动(SEEK)、切歌、单曲循环回卷时复位基准，允许进度回落
    private var lastMonoMediaId: Long? = null
    private var lastMonoPosition = 0L

    // 全局进度刷新协程：播放期间由播放器状态驱动，避免多个 UI 各自轮询重复写状态
    private var positionTickerJob: Job? = null

    // 即时播放位置：控制器在位时读其当前值，比按固定周期刷新的 currentPosition 更贴近此刻进度，
    // 且连续推进（控制器按时间外推），不会出现台阶；控制器未连接/已释放或回报非法值时回退到状态值。
    // 供歌词的跟随锚点取用：锚点用于判断「是否需要向上对齐」，用台阶值会让歌词跟着台阶一跳一跳
    val livePositionMs: Long
        get() = controller()?.currentPosition?.takeIf { it >= 0L } ?: currentPosition

    /**
     * 当前项的片段起点相对整曲的偏移（毫秒），未裁剪时恒为 0。
     *
     * 裁剪生效后控制器回报的进度以**片段起点**为原点，而歌词时间轴用的是曲目内的绝对时间。
     * 判据取「播放器里那一项」而非播放模式：切换模式的瞬间模式标志已经变了而队列还没换，
     * 按标志判断会算错坐标系。
     */
    val highlightPositionOffsetMs: Long
        get() = controller()?.currentMediaItem?.clippingConfiguration
            ?.startPositionMs?.takeIf { it > 0L } ?: 0L

    /** 歌词时间轴所用的播放位置：曲目内的**绝对**时间。 */
    val lyricPositionMs: Long
        get() = livePositionMs + highlightPositionOffsetMs

    /** 把整曲的绝对时间换算到播放器当前项的坐标系，供以歌词行为目标的跳转使用。 */
    fun toPlayerPosition(absoluteMs: Long): Long =
        (absoluteMs - highlightPositionOffsetMs).coerceIn(0L, duration.coerceAtLeast(0L))

    /**
     * 可落盘的播放位置。
     *
     * 心动模式下控制器回报的是片段内进度，落盘须换算回整曲的绝对位置 ——
     * 恢复时以同一套换算还原到片段坐标系，两个方向对称。
     */
    val persistablePositionMs: Long
        get() = currentPosition + highlightPositionOffsetMs

    // 启动全局进度刷新，重复调用不重复创建
    fun ensureTicker() {
        if (positionTickerJob?.isActive == true) return
        positionTickerJob = scope().launch {
            while (isActive) {
                if (isPlaying()) {
                    updatePosition()
                    onTick()
                }
                // 片段播放期间步进要细：首尾的音量斜坡靠它逐格推进
                delay(if (isHighlightClipActive()) HIGHLIGHT_TICK_MS else POSITION_TICK_MS)
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
        // 播放期间周期性持久化当前曲目与进度，避免冷启动/异常退出后丢失播放状态
        if (isActive && controller.isPlaying) {
            val now = System.currentTimeMillis()
            if (now - lastStatePersistAt >= STATE_PERSIST_INTERVAL_MS) {
                lastStatePersistAt = now
                onPeriodicPersist()
            }
            // 单曲循环播完回卷时计入一次完整播放（不受 onMediaItemTransition 触发与否影响）
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
                // 播放器尚未 READY（冷启动续播/切歌预载阶段）时控制器可能短暂回报 0 或失真位置；
                // 若会显著回退当前已还原的进度则保留现状，避免续播瞬间进度条从还原位置清空到 0 再回填。
                // 真实切歌路径的 currentPosition 已被切歌回调复位到 0，不会误命中本守卫。
                if (!isPrepared() && currentPosition > 0L &&
                    raw < currentPosition - MONO_REBASELINE_JUMP_MS
                ) {
                    onPlayingChanged(controller.isPlaying)
                    return
                }
                // 换项(mediaId 变化)或位置大幅回退（如切换歌单重载同 ID 曲目未触发切歌回调）时复位单调基准；
                // 小幅回退仍按流媒体回锚处理，保持进度单调，避免进度条倒退
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
        onPlayingChanged(controller.isPlaying)
    }

    // 拖动进度后为真实跳变：复位回卷判定与单调基准，允许进度回落
    fun resetAfterSeek() {
        lastTickPosition = 0L
        lastMonoMediaId = null
    }

    // 切歌后进度回到起点：复位单调基准，避免新曲进度被旧基准钳到高位
    fun resetMonotonicBaseline() {
        lastMonoMediaId = null
    }

    // 拖动进度条直接改写位置：复位单调基准，避免被钳回拖动前的位置
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

    // 单曲循环回卷检测：当前曲目播放位置从越过中部瞬间回退到开头即一次完整播放。
    // Media3 部分配置下 REPEAT_MODE_ONE 不投递 REPEAT 过渡回调，此处作为兜底收录，
    // 与过渡回调记录通过冷却去重，避免同一次循环计数两次
    private fun detectLoopRestart() {
        val track = currentTrack() ?: return
        if (duration <= 0L) return
        // 以控制器原始位置检测回卷，避免被进度单调钳制掩盖导致单曲循环漏记
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
        onPlaybackCompleted(track.id)
    }
}
