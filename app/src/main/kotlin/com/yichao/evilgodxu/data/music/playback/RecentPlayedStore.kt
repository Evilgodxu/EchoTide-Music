package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.yichao.evilgodxu.data.persistence.JsonSerializer
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import com.yichao.evilgodxu.data.persistence.catchPersistenceIo
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

internal val RECENT_PLAYED_STORE = PersistedData("recent_played", PersistedFileKind.DATA_STORE_JSON)

@Serializable
private data class StoredPlayEvent(
    val trackId: Long,
    val timestamp: Long,
)

@Serializable
private data class RecentPlayedArchive(
    val events: List<StoredPlayEvent> = emptyList(),
)

private val Context.recentPlayedDataStore: DataStore<RecentPlayedArchive> by dataStore(
    fileName = "${RECENT_PLAYED_STORE.name}.json",
    serializer = JsonSerializer(RecentPlayedArchive.serializer(), RecentPlayedArchive()),
    corruptionHandler = ReplaceFileCorruptionHandler { throwable ->
        CrashLogManager.logException("RecentPlayedStore", "常听记录数据损坏，已重置为空", throwable)
        RecentPlayedArchive()
    },
)

/**
 * 常听记录的持有与落盘。
 *
 * 「常听」= 近 3 天内完整播放不少于 2 次的歌曲，按最近一次播放时间倒序。
 * 记录随播放事件增长，故窗口内的旧记录在每次写入时顺带清理。
 */
internal class RecentPlayedStore(context: Context, scope: CoroutineScope) {

    private companion object {
        const val WINDOW_DAYS = 3L
        const val MIN_PLAYS = 2
        const val WINDOW_MS = WINDOW_DAYS * 24L * 60 * 60 * 1000
    }

    private val appContext = context.applicationContext
    private val writeQueue = Channel<List<PlayEvent>>(Channel.CONFLATED)
    private val loaded = CompletableDeferred<Unit>()

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

    init {
        scope.launch {
            appContext.recentPlayedDataStore.data
                .catchPersistenceIo("RecentPlayedStore") { RecentPlayedArchive() }
                .collect { archive ->
                    events = archive.events.map { PlayEvent(it.trackId, it.timestamp) }
                    loaded.complete(Unit)
                }
        }
        scope.launch {
            for (value in writeQueue) {
                appContext.recentPlayedDataStore.updateData { archive ->
                    RecentPlayedArchive(value.map { StoredPlayEvent(it.trackId, it.timestamp) })
                }
            }
        }
    }

    suspend fun awaitLoaded() {
        loaded.await()
    }

    fun record(trackId: Long) {
        val now = System.currentTimeMillis()
        events = listOf(PlayEvent(trackId, now)) + events.filter { it.timestamp >= now - WINDOW_MS }
        writeQueue.trySend(events)
    }

    fun remove(trackId: Long) {
        events = events.filterNot { it.trackId == trackId }
        writeQueue.trySend(events)
    }

    /** 无损升级换曲目 ID 后的关联迁移；返回是否确有记录被改写。 */
    fun migrateTrackId(oldId: Long, newId: Long): Boolean {
        if (events.none { it.trackId == oldId }) return false
        events = events.map { if (it.trackId == oldId) it.copy(trackId = newId) else it }
        writeQueue.trySend(events)
        return true
    }
}
