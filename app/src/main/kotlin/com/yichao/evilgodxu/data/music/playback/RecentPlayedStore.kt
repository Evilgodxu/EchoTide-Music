package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 常听记录的持有与落盘。
 *
 * 「常听」= 近 3 天内完整播放不少于 2 次的歌曲，按最近一次播放时间倒序。
 * 记录随播放事件增长，故窗口内的旧记录在每次写入时顺带清理。
 */
internal class RecentPlayedStore(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
) {

    private companion object {
        const val KEY = "music_recent_played_events"
        const val WINDOW_DAYS = 3L
        const val MIN_PLAYS = 2
        const val WINDOW_MS = WINDOW_DAYS * 24L * 60 * 60 * 1000
    }

    private var events by mutableStateOf<List<PlayEvent>>(emptyList())

    /** 窗口内达到收录次数的曲目，最近播放的在前。 */
    val ids: List<Long>
        get() {
            val cutoff = System.currentTimeMillis() - WINDOW_MS
            return events
                .filter { it.timestamp >= cutoff }
                .groupBy { it.trackId }
                .filterValues { it.size >= MIN_PLAYS }
                .entries
                .sortedByDescending { it.value.maxOf { e -> e.timestamp } }
                .map { it.key }
        }

    fun record(trackId: Long) {
        val now = System.currentTimeMillis()
        events = listOf(PlayEvent(trackId, now)) + events.filter { it.timestamp >= now - WINDOW_MS }
        persist()
    }

    fun remove(trackId: Long) {
        events = events.filterNot { it.trackId == trackId }
        persist()
    }

    /** 无损升级换曲目 ID 后的关联迁移；返回是否确有记录被改写。 */
    fun migrateTrackId(oldId: Long, newId: Long): Boolean {
        if (events.none { it.trackId == oldId }) return false
        events = events.map { if (it.trackId == oldId) it.copy(trackId = newId) else it }
        persist()
        return true
    }

    fun replaceAll(value: List<PlayEvent>) {
        events = value
    }

    /** 冷启动读盘；解析失败的行直接跳过，不影响其余记录。 */
    fun read(context: Context): List<PlayEvent> =
        context.getSharedPreferences(MusicPlaybackState.RECENT_PLAYED_STORE.name, Context.MODE_PRIVATE)
            .getString(KEY, "")
            ?.split(",")
            ?.mapNotNull { token ->
                val idx = token.lastIndexOf(':')
                if (idx <= 0) return@mapNotNull null
                val id = token.substring(0, idx).toLongOrNull() ?: return@mapNotNull null
                val timestamp = token.substring(idx + 1).toLongOrNull() ?: return@mapNotNull null
                PlayEvent(id, timestamp)
            }
            .orEmpty()

    private fun persist() {
        val context = context() ?: return
        scope().launch(Dispatchers.IO) {
            context.getSharedPreferences(MusicPlaybackState.RECENT_PLAYED_STORE.name, Context.MODE_PRIVATE)
                .edit(commit = true) {
                    putString(KEY, events.joinToString(",") { "${it.trackId}:${it.timestamp}" })
                }
        }
    }
}
