package com.yichao.evilgodxu.data.music.playback

import androidx.media3.session.MediaController
import com.yichao.evilgodxu.data.music.highlight.HighlightStore
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.PlayMode

/**
 * 心动模式的音量斜坡：片段首尾的淡入淡出，以及「当前项是否确实被裁剪」的判定。
 */
internal class HighlightFadeController(
    private val playMode: () -> PlayMode,
    private val currentTrack: () -> MusicTrack?,
    private val mediaController: () -> MediaController?,
) {

    private companion object {
        // 片段首尾的音量斜坡时长：淡入与淡出各占一段，靠降低音量掩掉硬切
        const val HIGHLIGHT_FADE_MS = 800L
    }

    // 当前播放项是否为心动模式的裁剪片段：只有它有「首尾」，因而才需要音量淡入淡出
    fun isClipActive(): Boolean =
        playMode() == PlayMode.Highlight &&
            currentTrack()?.let { HighlightStore.segmentOf(it.id) } != null

    /**
     * 把片段首尾的音量斜坡写进播放器音量。
     *
     * 片段是靠裁剪切开的，首尾都是硬切；这里在片段开头把音量由 0 升到 1、在结尾前由 1 降到 0，
     * 用降低音量做出淡入淡出，进出不再是突兀的一下。
     *
     * 位置与时长都取控制器对**当前项**的回报：裁剪生效后它们本就以片段为原点，正是斜坡要的坐标系。
     * 仅对确有片段的曲目生效 —— 心动模式下没有片段的曲目照常整曲播放，不该被从头淡入到尾。
     */
    fun syncFadeVolume() {
        val controller = mediaController() ?: return
        val target = if (isClipActive()) {
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
        if (controller.volume != target) controller.volume = target
    }
}
