package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.PlayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 心动模式准入与跳过：进入拦截、退出缘由、无副歌回绕 */
internal class HighlightPlaybackPolicy(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
    private val playMode: () -> PlayMode,
    private val currentTrack: () -> MusicTrack?,
    private val playlistSize: () -> Int,
    private val currentIndex: () -> Int,
    private val nextIndex: () -> Int,
    private val playAt: (context: Context, index: Int) -> Unit,
    private val exitToNormal: () -> Unit,
    private val immersiveOutputActive: () -> Boolean,
) {

    // 自行退出的一次性原因，界面消费后清空
    var exitNotice by mutableStateOf<HighlightExitReason?>(null)

    // 连续跳过计数，整队列无副歌时防空转
    private var skipStreak = 0

    /** 模式变更：跳过计数只在本段存续期有效 */
    fun onModeChanged() {
        skipStreak = 0
    }

    /** 沉浸式输出期间拒绝心动模式，界面据此说明 */
    fun notifyImmersiveBlocked() {
        exitNotice = HighlightExitReason.ImmersiveOutput
    }

    /** USB 开关启用即退出；须先写好开关状态，统一切主线程改模式 */
    fun onImmersiveOutputChanged() {
        if (!immersiveOutputActive() || playMode() != PlayMode.Highlight) return
        scope().launch {
            if (playMode() != PlayMode.Highlight) return@launch
            exitNotice = HighlightExitReason.ImmersiveOutput
            exitToNormal()
        }
    }

    /**
     * 曲目落定后跳过无副歌项，超队列长度即退出并提示。
     * 仅已扫描且有歌词时才跳过，未就绪照常整曲播放。
     */
    fun onTrackSettled() {
        if (playMode() != PlayMode.Highlight) {
            skipStreak = 0
            return
        }
        val context = context() ?: return
        val track = currentTrack() ?: return
        if (!isKnownHighlightMiss(track)) {
            skipStreak = 0
            return
        }
        if (skipStreak >= playlistSize()) {
            skipStreak = 0
            exitNotice = HighlightExitReason.NoChorus
            exitToNormal()
            return
        }
        skipStreak++
        val next = nextIndex()
        if (next in 0 until playlistSize() && next != currentIndex()) {
            playAt(context, next)
        }
    }
}
