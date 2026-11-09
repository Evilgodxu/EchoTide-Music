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

// 心动高亮段的落盘落点：名字即持久化标识，读写与存储页统计共用这一处声明
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
 * 单曲的片段判定结果。
 *
 * 「无法定位」与「确认没有副歌」必须是两个不同的结论：前者只是数据不足（没有歌词、歌词无时间轴），
 * 据此跳过会让整库在数据就绪前被跳过；后者才是真的没有高潮段，只有它可以跳过该曲。
 */
internal sealed interface HighlightEntry {

    /** 已定位出片段 */
    data class Segment(val highlight: Highlight) : HighlightEntry

    /** 无法定位：数据不足，调用方据此按整曲播放，**不得跳过** */
    data object Unresolved : HighlightEntry

    /** 有可用歌词时间轴且确认没有高潮段：调用方据此跳过该曲 */
    data object NoChorus : HighlightEntry
}

/**
 * 全库副歌片段表：一次扫描算全库、落盘，播放路径只查一次内存表，切歌与切模式都不再触解析。
 *
 * 表按「歌词指纹 + 算法版本」判定新鲜度，任一变化即重算。版本号并入指纹是有意的 ——
 * 定位算法升级后旧结果必须整体作废，这一步靠版本号触发，不靠人去清缓存。
 * 定位为纯歌词分析（不解码音频），故没有「粗扫 / 精修」之分。
 */
internal object HighlightStore {

    internal const val CURRENT_VERSION = 1

    // 定位算法版本：参与指纹，算法改动后旧结果自动作废重算
    private const val ALGORITHM_VERSION = "v5"

    private const val KIND_SEGMENT = "segment"
    private const val KIND_UNRESOLVED = "unresolved"
    private const val KIND_NO_CHORUS = "no_chorus"
    // 旧版「无歌词」标识：语义等同 Unresolved，读取时一并归一
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

    /**
     * 歌词与曲目属性指纹：行数、首末时间戳、全部行内容与时长。
     *
     * 副歌识别依赖歌词正文，只看行数与首末时间会让「行数相同、正文全换」的两份歌词得到同一指纹，
     * 旧的 NoChorus 结论会一直压住新歌词。时长参与指纹是因为片段边界以它为上限。
     */
    fun fingerprintOf(track: MusicTrack): String {
        val lines = track.lyricLines
        val base = if (lines.isEmpty()) {
            "none:${track.lyricCachePath}"
        } else {
            "${lines.size}:${lines.first().timeMs}:${lines.last().timeMs}:${lines.hashCode()}"
        }
        return "$ALGORITHM_VERSION:${track.duration}:$base"
    }

    /**
     * 该曲是否已按当前口径扫描过：指纹未变即为最新。
     */
    fun isUpToDate(track: MusicTrack): Boolean {
        val stored = table[track.id] ?: return false
        return stored.fingerprint == fingerprintOf(track)
    }

    /** 该曲应播放的片段；未扫描、无法定位或判定无副歌时返回 null（调用方据此整曲播放） */
    fun segmentOf(trackId: Long): Highlight? =
        (table[trackId]?.entry as? HighlightEntry.Segment)?.highlight

    /**
     * 该曲是否「有歌词与音频却确实没有高潮段」。
     *
     * 只有这一种情况才跳过该曲；无法定位与未扫描都不能据此跳过，
     * 否则歌词尚未补齐的曲库会被整库跳过。
     */
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

    /** 合并一批扫描结果并落盘。批写而非逐条写：整库扫描会产生成百上千条更新 */
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
        // 版本不认识时按空表处理：下次扫描会整表重建，不做迁移尝试
        if (archive.version != CURRENT_VERSION) return@withContext emptyMap()
        buildMap {
            archive.entries.forEach { item ->
                val entry = entryFrom(item) ?: return@forEach
                put(item.id, Stored(item.fingerprint, entry))
            }
        }
    }

    private suspend fun write(context: Context, values: Map<Long, Stored>) = withContext(Dispatchers.IO) {
        // 整表覆盖：调用方持有完整内存表，合并旧文件只会留下已作废的条目
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
