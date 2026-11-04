package com.yichao.evilgodxu.data.music.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yichao.evilgodxu.data.music.model.MusicTrack

/**
 * 「下一首播放」插队队列。
 *
 * 自然播完后依次播放队列曲目，队列播完后接续原播放位置：
 * 建立队列时记下当时的当前曲目，队列清空或消费到接续点时取回，由播放侧按列表顺序接续其后一首。
 */
internal class PlayNextQueue(
    private val currentTrackId: () -> Long?,
) {

    var tracks by mutableStateOf<List<MusicTrack>>(emptyList())

    // 建立队列时记录的当前曲目 ID，队列播完后据此接续原播放位置
    private var resumeTrackId: Long? = null

    fun isIn(trackId: Long): Boolean = tracks.any { it.id == trackId }

    // 切换下一首播放：已在队列则取消插队，否则加入
    fun toggle(track: MusicTrack) {
        if (isIn(track.id)) {
            tracks = tracks.filterNot { it.id == track.id }
            // 队列清空后无需再接续原播放位置
            if (tracks.isEmpty()) resumeTrackId = null
        } else {
            if (tracks.isEmpty() && resumeTrackId == null) {
                resumeTrackId = currentTrackId()
            }
            tracks = tracks + track
        }
    }

    // 手动切歌时清空插队队列
    fun clear() {
        tracks = emptyList()
        resumeTrackId = null
    }

    fun remove(trackId: Long) {
        tracks = tracks.filterNot { it.id == trackId }
        if (resumeTrackId == trackId) resumeTrackId = null
    }

    /** 取出队首并在队列中移除。 */
    fun consumeFirst(): MusicTrack? {
        val first = tracks.firstOrNull() ?: return null
        tracks = tracks.drop(1)
        return first
    }

    /** 队列已空且存在待接续位置时返回并消费它。 */
    fun takeResumeTrackId(): Long? {
        val id = resumeTrackId
        resumeTrackId = null
        return id
    }
}
