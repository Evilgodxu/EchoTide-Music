package com.yichao.evilgodxu.data.music.playback

import androidx.media3.session.MediaController
import com.yichao.evilgodxu.data.music.highlight.HighlightStore
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.PlayMode

/**
 * 播放器音量的唯一写入方：心动模式的片段斜坡 × 音量增益。
 *
 * 两项相乘而不是各写一次 `MediaController.volume`：音量只有一层，谁后写谁生效；分成两个写入方时
 * 切歌或开关切换会把另一项冲掉，表现为「开了增益心动淡入就没了」这类互斥假象。
 */
internal class HighlightFadeController(
    private val playMode: () -> PlayMode,
    private val currentTrack: () -> MusicTrack?,
    private val mediaController: () -> MediaController?,
    // 音量增益的线性系数：关闭或无标签时为 1
    private val replayGain: () -> Float,
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
        // 播放器音量的合法区间是 0..1，故这里的增益只承担衰减；需要抬升的曲目（增益为正）
        // 得靠写出侧的 PCM 增益，那是音量之外的另一层，不在这里伪造 >1 的系数
        val target = fade * replayGain().coerceIn(0f, 1f)
        if (controller.volume != target) controller.volume = target
    }
}
