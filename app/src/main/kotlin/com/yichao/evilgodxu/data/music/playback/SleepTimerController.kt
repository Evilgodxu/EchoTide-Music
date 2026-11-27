package com.yichao.evilgodxu.data.music.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.yichao.evilgodxu.data.music.model.PlayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 定时关闭：倒计时与「播完本曲即停」布防；收尾交宿主 */
internal class SleepTimerController(
    private val isPlaying: () -> Boolean,
    private val playMode: () -> PlayMode,
    private val mediaController: () -> MediaController?,
    private val onCountdownFinished: (songFinished: Boolean) -> Unit,
) {

    var timerMinutes by mutableIntStateOf(10)
    var timerRemaining by mutableIntStateOf(0)

    // 收尾完成后的退出请求，由应用外壳接管
    var onSleepTimerFinished: (() -> Unit)? = null

    private val timerJob = SupervisorJob()
    private val timerScope = CoroutineScope(timerJob + Dispatchers.Main)
    private var countdownJob: Job? = null

    /** 到点后播完本曲即停的布防标志 */
    var stopAfterCurrentTrack = false
        private set

    fun setMinutes(minutes: Int) {
        timerMinutes = minutes.coerceIn(
            MusicPlaybackState.TIMER_MIN_MINUTES,
            MusicPlaybackState.TIMER_MAX_MINUTES,
        )
    }

    // 启动定时关闭（分钟）：播完本曲即停并退出
    fun start(minutes: Int) {
        val target = minutes.coerceIn(
            MusicPlaybackState.TIMER_MIN_MINUTES,
            MusicPlaybackState.TIMER_MAX_MINUTES,
        )
        stop()
        timerMinutes = target
        timerRemaining = target
        countdownJob = timerScope.launch {
            while (timerRemaining > 0) {
                delay(60_000L)
                timerRemaining--
            }
            // 计时结束：在播则布防，否则直接收尾
            if (isPlaying()) {
                stopAfterCurrentTrack = true
                withContext(Dispatchers.Main) {
                    mediaController()?.let { controller ->
                        // 关循环与随机，播毕不再续播
                        controller.repeatMode = Player.REPEAT_MODE_OFF
                        controller.shuffleModeEnabled = false
                    }
                }
            } else {
                stopAfterCurrentTrack = false
                onCountdownFinished(false)
            }
        }
    }

    fun stop() {
        val wasArmed = stopAfterCurrentTrack
        stopAfterCurrentTrack = false
        countdownJob?.cancel()
        countdownJob = null
        timerRemaining = 0
        // 已布防则还原用户播放模式
        if (wasArmed) {
            mediaController()?.let { applyPlaybackMode(it, playMode()) }
        }
    }

    /** 消费布防并收尾；songFinished 落到下一首 */
    fun complete(songFinished: Boolean) {
        stopAfterCurrentTrack = false
        onCountdownFinished(songFinished)
    }
}
