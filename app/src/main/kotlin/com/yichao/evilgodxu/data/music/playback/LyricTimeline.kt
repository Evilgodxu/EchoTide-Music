package com.yichao.evilgodxu.data.music.playback

/**
 * 歌词面板重建时的进度快照。
 *
 * 歌词面板在旋转、悬浮窗建立、页面回切时会整体重建。面板本地按真实流逝时间推进的相位
 * 若不能在重建时接上，就会退回重建那一刻的播放位置，表现为「重建一次、歌词落后数百毫秒」；
 * 故快照由播放状态持有：面板每轮跟随回写，重建时接着走。
 *
 * 用普通字段而非 Compose 状态：每秒数十次写入，不该牵动其它面板重组。
 */
internal class LyricTimeline {

    private var trackId: Long? = null
    private var positionMs: Long = 0L

    /**
     * 界面重建时的歌词起步位置。
     *
     * 取「上一块面板推进到的进度」与「当前即时播放位置」的较大者：前者保住面板本地已按真实流逝时间
     * 推进的相位（即时位置随位置回锚回退后可能已低于它），后者保证离开页面较久、快照明显落后时
     * 仍从当前进度起步。
     */
    fun seed(trackId: Long?, currentPositionMs: Long): Long {
        val snapshot = positionMs
            .takeIf { trackId != null && trackId == this.trackId } ?: 0L
        return maxOf(currentPositionMs, snapshot)
    }

    // 面板每轮跟随回写当前进度，供界面重建时接着推进
    fun record(trackId: Long?, positionMs: Long) {
        if (trackId == null || trackId != this.trackId) {
            this.trackId = trackId
            this.positionMs = positionMs
            return
        }
        // 同一曲目内只前进：多个面板可能同时在场，落后的一方回写不应把已推进的进度拉回
        if (positionMs > this.positionMs) this.positionMs = positionMs
    }

    // 真实跳变（拖动进度、歌词拖拽跳转、切曲、循环回卷）后作废快照：
    // 下一块面板改以跳变后的即时播放位置重新起步，避免沿用跳变前的旧进度
    fun invalidate() {
        trackId = null
        positionMs = 0L
    }
}
