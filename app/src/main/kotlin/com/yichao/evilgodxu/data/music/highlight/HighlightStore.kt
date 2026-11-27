package com.yichao.evilgodxu.data.music.highlight

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.persistence.JsonSerializer
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.PersistedFileKind
import com.yichao.evilgodxu.data.persistence.catchPersistenceIo
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

// 高亮存储落点，名字即持久化标识
internal val HIGHLIGHT_STORE = PersistedData("highlight", PersistedFileKind.DATA_STORE_JSON)

@Serializable
private data class StoredHighlight(
    val id: Long,
    val fingerprint: String,
    val kind: String,
    val start: Long = -1L,
    val end: Long = -1L,
)

@Serializable
private data class HighlightArchive(
    val version: Int = HighlightStore.CURRENT_VERSION,
    val entries: List<StoredHighlight> = emptyList(),
)

private val Context.highlightDataStore: DataStore<HighlightArchive> by dataStore(
    fileName = "${HIGHLIGHT_STORE.name}.json",
    serializer = JsonSerializer(HighlightArchive.serializer(), HighlightArchive()),
    corruptionHandler = ReplaceFileCorruptionHandler { throwable ->
        CrashLogManager.logException("HighlightStore", "副歌片段表损坏，已重置", throwable)
        HighlightArchive()
    },
)

/**
 * 单曲判定结果：无法定位与确无副歌须分开
 * 前者数据不足不得跳过，后者才可跳过该曲
 */
internal sealed interface HighlightEntry {

    /** 已定位出片段 */
    data class Segment(val highlight: Highlight) : HighlightEntry

    /** 数据不足，按整曲播放，不得跳过 */
    data object Unresolved : HighlightEntry

    /** 确认无高潮段，可跳过该曲 */
    data object NoChorus : HighlightEntry
}

/**
 * 全库副歌表：扫描后落盘，播放只查内存表
 * 按「歌词指纹 + 算法版本」判新鲜度，版本变更即整体重算
 */
internal object HighlightStore {

    internal const val CURRENT_VERSION = 1

    // 算法版本参与指纹，改动即重算
    private const val ALGORITHM_VERSION = "v5"

    private const val KIND_SEGMENT = "segment"
    private const val KIND_UNRESOLVED = "unresolved"
    private const val KIND_NO_CHORUS = "no_chorus"
    // 旧版标识，读取时归一为 Unresolved
    private const val KIND_LEGACY_NO_LYRICS = "no_lyrics"

    private class Stored(
        val fingerprint: String,
        val entry: HighlightEntry,
    )

    @Volatile
    private var table: Map<Long, Stored> = emptyMap()

    @Volatile
    var isLoaded = false
        private set

    private val loadMutex = Mutex()
    private val writeMutex = Mutex()

    /** 指纹：行数、首末时间、行内容哈希与时长 */
    fun fingerprintOf(track: MusicTrack): String {
        val lines = track.lyricLines
        val base = if (lines.isEmpty()) {
            "none:${track.lyricCachePath}"
        } else {
            "${lines.size}:${lines.first().timeMs}:${lines.last().timeMs}:${lines.hashCode()}"
        }
        return "$ALGORITHM_VERSION:${track.duration}:$base"
    }

    /** 指纹未变即为最新 */
    fun isUpToDate(track: MusicTrack): Boolean {
        val stored = table[track.id] ?: return false
        return stored.fingerprint == fingerprintOf(track)
    }

    /** 该曲片段；无则返回 null，整曲播放 */
    fun segmentOf(trackId: Long): Highlight? =
        (table[trackId]?.entry as? HighlightEntry.Segment)?.highlight

    /** 确认无高潮段才可跳过，避免整库跳过 */
    fun isKnownChorusMiss(trackId: Long): Boolean =
        table[trackId]?.entry == HighlightEntry.NoChorus

    suspend fun ensureLoaded(context: Context) {
        if (isLoaded) return
        loadMutex.withLock {
            if (isLoaded) return
            table = read(context)
            isLoaded = true
        }
    }

    /** 合并一批结果并落盘，避免逐条写 */
    suspend fun commit(context: Context, updates: Map<Long, ScanResult>) {
        if (updates.isEmpty()) return
        writeMutex.withLock {
            val merged = table.toMutableMap()
            updates.forEach { (trackId, result) ->
                merged[trackId] = Stored(result.fingerprint, result.entry)
            }
            table = merged
            write(context, merged)
        }
    }

    class ScanResult(
        val fingerprint: String,
        val entry: HighlightEntry,
    )

    private suspend fun read(context: Context): Map<Long, Stored> = withContext(Dispatchers.IO) {
        val archive = context.highlightDataStore.data
            .catchPersistenceIo("HighlightStore") { HighlightArchive() }
            .first()
        // 版本不符按空表处理，下次重建
        if (archive.version != CURRENT_VERSION) return@withContext emptyMap()
        buildMap {
            archive.entries.forEach { item ->
                val entry = entryFrom(item) ?: return@forEach
                put(item.id, Stored(item.fingerprint, entry))
            }
        }
    }

    private suspend fun write(context: Context, values: Map<Long, Stored>) = withContext(Dispatchers.IO) {
        // 整表覆盖，避免残留作废条目
        context.highlightDataStore.updateData {
            HighlightArchive(
                version = CURRENT_VERSION,
                entries = values.map { (trackId, stored) -> itemTo(trackId, stored) },
            )
        }
    }

    private fun itemTo(trackId: Long, stored: Stored): StoredHighlight = when (val entry = stored.entry) {
        is HighlightEntry.Segment -> StoredHighlight(
            id = trackId,
            fingerprint = stored.fingerprint,
            kind = KIND_SEGMENT,
            start = entry.highlight.startMs,
            end = entry.highlight.endMs,
        )
        HighlightEntry.Unresolved -> StoredHighlight(trackId, stored.fingerprint, KIND_UNRESOLVED)
        HighlightEntry.NoChorus -> StoredHighlight(trackId, stored.fingerprint, KIND_NO_CHORUS)
    }

    private fun entryFrom(item: StoredHighlight): HighlightEntry? = when (item.kind) {
        KIND_SEGMENT -> {
            if (item.start in 0L until item.end) {
                HighlightEntry.Segment(Highlight(item.start, item.end))
            } else {
                null
            }
        }
        KIND_UNRESOLVED, KIND_LEGACY_NO_LYRICS -> HighlightEntry.Unresolved
        KIND_NO_CHORUS -> HighlightEntry.NoChorus
        else -> null
    }
}
