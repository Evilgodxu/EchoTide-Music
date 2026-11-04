package com.yichao.evilgodxu.data.music.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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

/**
 * 定时关闭：倒计时、到点后「播完当前曲即停」的布防与取消。
 *
 * 到点后的实际收尾（落盘续播目标、释放播放器、请求退出）属于宿主职责，
 * 由 [onCountdownFinished] 回调交给宿主编排；本类只负责计时与布防状态。
 */
internal class SleepTimerController(
    private val isPlaying: () -> Boolean,
    private val playMode: () -> PlayMode,
    private val mediaController: () -> MediaController?,
    private val onCountdownFinished: (songFinished: Boolean) -> Unit,
) {

    var timerMinutes by mutableIntStateOf(10)
    var timerRemaining by mutableIntStateOf(0)

    // 定时关闭收尾完成后的退出请求：结束应用属应用外壳职责，此处只发起请求，由外壳接管退出编排
    var onSleepTimerFinished: (() -> Unit)? = null

    private val timerJob = SupervisorJob()
    private val timerScope = CoroutineScope(timerJob + Dispatchers.Main)
    private var countdownJob: Job? = null

    /**
     * 到点后当前曲目播毕即停的布防标志。
     *
     * 置位后播放器被改为不循环、不随机，等自然过渡或播放结束时由宿主消费。
     */
    var stopAfterCurrentTrack = false
        private set

    fun setMinutes(minutes: Int) {
        timerMinutes = minutes.coerceIn(
            MusicPlaybackState.TIMER_MIN_MINUTES,
            MusicPlaybackState.TIMER_MAX_MINUTES,
        )
    }

    // 启动定时关闭（分钟）：到点后播完当前整曲即停止播放并退出应用
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
            // 计时结束：当前歌曲播放完成后停止并退出；未在播放则直接收尾（曲目未播完，保留当前位置供下次续播）
            if (isPlaying()) {
                stopAfterCurrentTrack = true
                withContext(Dispatchers.Main) {
                    mediaController()?.let { controller ->
                        // 关掉循环与随机，当前曲目播毕即让播放器停止续播，交由收尾流程退出应用
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
        // 已到点并改写过播放器循环/随机模式时还原用户设定，避免取消后播放模式与界面显示不一致
        if (wasArmed) {
            mediaController()?.let { applyPlaybackMode(it, playMode()) }
        }
    }

    /**
     * 消费布防并进入收尾。
     *
     * songFinished 表示当前曲目已完整播完，此时「当前曲目」要落到它的下一首——
     * 播完的这一首已不属于待播内容，留作当前曲目会让下次启动点击播放时重播它。
     */
    fun complete(songFinished: Boolean) {
        stopAfterCurrentTrack = false
        onCountdownFinished(songFinished)
    }
}
