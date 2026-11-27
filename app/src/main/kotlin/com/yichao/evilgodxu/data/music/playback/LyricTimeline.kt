package com.yichao.evilgodxu.data.music.playback

/** 歌词面板重建时的进度快照：面板每轮回写，重建时接着走 */
internal class LyricTimeline {

    private var trackId: Long? = null
    private var positionMs: Long = 0L

    /** 重建起步位置，取快照与即时位置的较大者 */
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
        // 同曲内只前进，防落后面板回拉
        if (positionMs > this.positionMs) this.positionMs = positionMs
    }

    // 真实跳变后作废快照
    fun invalidate() {
        trackId = null
        positionMs = 0L
    }
}
