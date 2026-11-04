package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.PlayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 心动模式的准入与跳过策略：进入拦截、退出缘由、无副歌曲目的跳过与回绕保护。
 *
 * 模式切换的队列重建与扫描由会话与扫描协调器承担，本类只决定「这一首该不该跳过」「何时自行退出」。
 */
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

    // 心动模式自行退出的一次性原因，由界面消费后清空。退出有多种缘由（定位不到副歌、进入直出），
    // 界面据此说明模式为何自己变了 —— 否则用户会当成失灵
    var exitNotice by mutableStateOf<HighlightExitReason?>(null)

    // 连续跳过计数：整队列都无可定位副歌时防止回绕空转
    private var skipStreak = 0

    /** 播放模式变更：跳过计数只对当前这一段模式存续周期有意义。 */
    fun onModeChanged() {
        skipStreak = 0
    }

    /** 沉浸式输出期间不接受心动模式：界面据此说明缘由而不是静默不响应。 */
    fun notifyImmersiveBlocked() {
        exitNotice = HighlightExitReason.ImmersiveOutput
    }

    /**
     * USB 输出开关变化：一经启用即退出心动模式。
     *
     * 开关可以在曲中才被拨动，此时模式不能就这么留在片段播放上——片段首尾的音量淡入淡出在沉浸链路上
     * 无从实施，队列项也仍是裁过的那些。调用方须先写好开关状态再调本方法：判据读的是当前状态。
     * 成色回调来自播放线程与主线程两处，故统一切到主线程再改播放模式。
     */
    fun onImmersiveOutputChanged() {
        if (!immersiveOutputActive() || playMode() != PlayMode.Highlight) return
        scope().launch {
            if (playMode() != PlayMode.Highlight) return@launch
            exitNotice = HighlightExitReason.ImmersiveOutput
            exitToNormal()
        }
    }

    /**
     * 曲目落定后的收尾：不可定位副歌的曲目直接跳过。
     *
     * 跳过带回绕保护：整队列都定位不到时（纯音乐歌单、歌词全无），连续跳过会绕回原点空转，
     * 故连续跳过数超过队列长度即退出该模式交回普通播放，并置一次性提示供界面说明原因。
     *
     * 只有「已扫描、有歌词、且判定无副歌」才跳过。歌词尚未就绪不等于没有副歌 ——
     * 据此跳过会在冷启动时把整库都跳过，故未就绪时照常整曲播放，等扫描补齐后即是片段。
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
