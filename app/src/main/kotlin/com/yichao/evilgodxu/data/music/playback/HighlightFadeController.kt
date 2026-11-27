package com.yichao.evilgodxu.data.music.playback

import androidx.media3.session.MediaController
import com.yichao.evilgodxu.data.music.highlight.HighlightStore
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.PlayMode

/** 播放器音量的唯一写入方：片段斜坡 × 音量增益 */
internal class HighlightFadeController(
    private val playMode: () -> PlayMode,
    private val currentTrack: () -> MusicTrack?,
    private val mediaController: () -> MediaController?,
    // 增益系数，关闭或无标签时为 1
    private val replayGain: () -> Float,
) {

    private companion object {
        // 片段首尾斜坡时长，掩掉硬切
        const val HIGHLIGHT_FADE_MS = 800L
    }

    // 是否为心动片段，只有它需要淡入淡出
    fun isClipActive(): Boolean =
        playMode() == PlayMode.Highlight &&
            currentTrack()?.let { HighlightStore.segmentOf(it.id) } != null

    /** 把片段首尾的淡入淡出写进播放器音量，位置以片段为原点 */
    fun syncFadeVolume() {
        val controller = mediaController() ?: return
        val fade = if (isClipActive()) {
            val duration = controller.duration
            val position = controller.currentPosition
            if (duration <= 0L || position < 0L) {
                1f
            } else {
                val fadeIn = position.toFloat() / HIGHLIGHT_FADE_MS
                val fadeOut = (duration - position).toFloat() / HIGHLIGHT_FADE_MS
                minOf(fadeIn, fadeOut).coerceIn(0f, 1f)
            }
        } else {
            1f
        }
        // 播放器音量限 0..1，增益只做衰减
        val target = fade * replayGain().coerceIn(0f, 1f)
        if (controller.volume != target) controller.volume = target
    }
}
