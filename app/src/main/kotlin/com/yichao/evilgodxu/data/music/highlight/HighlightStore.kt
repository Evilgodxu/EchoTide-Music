package com.yichao.evilgodxu.data.music.highlight

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单曲的片段判定结果。
 *
 * 「没有歌词」与「有歌词但没有副歌」必须是两个不同的结论：前者只是数据尚未就绪，
 * 据此跳过该曲会让整库在歌词补齐前被跳过；后者才是真的没有副歌。
 */
internal sealed interface HighlightEntry {

    /** 已定位出片段 */
    data class Segment(val highlight: Highlight) : HighlightEntry

    /** 没有任何歌词可用。不是「没有副歌」，调用方据此按整曲播放 */
    data object NoLyrics : HighlightEntry

    /** 有歌词但定位不出重复段：确实是「没有副歌」，调用方据此跳过该曲 */
    data object NoChorus : HighlightEntry
}

/**
 * 全库副歌片段表：一次扫描算全库、落盘，播放路径只查表。
 *
 * 片段曾放在播放路径上按需解析（切歌时读歌词、定位、再替换队列项），代价有三：
 * 每次切歌都要读盘与计算；解析是异步的，片段何时生效取决于歌词何时就绪，行为不确定；
 * 切换模式要逐项替换队列，队列长时可见卡顿。改为「扫描一次、查表使用」后这三项同时消失 ——
 * 播放路径只剩一次内存查表，切模式与切歌都不再触解析。
 *
 * 表按「歌词指纹」判定新鲜度：指纹未变即不重算，歌词被补全或手动刷新后指纹改变，重新扫描。
 * 所以 [NoLyrics] 只表示「扫描时没歌词」，后续歌词到位后会被重扫覆盖，不会永久卡在整曲播放。
 *
 * 落盘规模与曲库同阶（每曲一条），写入按批进行（见 [com.yichao.evilgodxu.data.music.highlight.HighlightScanner]）。
 */
internal object HighlightStore {

    private const val FILE_NAME = "highlight_segments.json"
    private const val KEY_VERSION = "version"
    private const val CURRENT_VERSION = 1

    // 条目类型落盘标识
    private const val KIND_SEGMENT = "segment"
    private const val KIND_NO_LYRICS = "no_lyrics"
    private const val KIND_NO_CHORUS = "no_chorus"

    private class Stored(val fingerprint: String, val entry: HighlightEntry)

    @Volatile
    private var table: Map<Long, Stored> = emptyMap()

    @Volatile
    var isLoaded = false
        private set

    private val loadMutex = Mutex()
    private val writeMutex = Mutex()

    /** 歌词指纹：行数与首末时间戳足以识别歌词是否被替换 */
    fun fingerprintOf(track: MusicTrack): String {
        val lines = track.lyricLines
        if (lines.isEmpty()) return "none:${track.lyricCachePath}"
        return "${lines.size}:${lines.first().timeMs}:${lines.last().timeMs}"
    }

    /** 该曲是否已按当前歌词扫描过。歌词一变即为过期，须重算 */
    fun isUpToDate(track: MusicTrack): Boolean =
        table[track.id]?.fingerprint == fingerprintOf(track)

    /** 该曲应播放的片段；未扫描、无歌词或判定无副歌时返回 null（调用方据此整曲播放） */
    fun segmentOf(trackId: Long): Highlight? =
        (table[trackId]?.entry as? HighlightEntry.Segment)?.highlight

    /**
     * 该曲是否「有歌词但确实没有副歌」。
     *
     * 只有这一种情况才跳过该曲；未扫描与无歌词都不能据此跳过，
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
    suspend fun commit(context: Context, updates: Map<Long, Pair<String, HighlightEntry>>) {
        if (updates.isEmpty()) return
        writeMutex.withLock {
            val merged = table.toMutableMap()
            updates.forEach { (trackId, value) ->
                merged[trackId] = Stored(value.first, value.second)
            }
            table = merged
            write(context, merged)
        }
    }

    private suspend fun read(context: Context): Map<Long, Stored> = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.isFile) return@withContext emptyMap()
            val root = JSONObject(file.readText())
            // 版本不认识时按空表处理：下次扫描会整表重建，不做迁移尝试
            if (root.optInt(KEY_VERSION) != CURRENT_VERSION) return@withContext emptyMap()
            val entries = root.optJSONArray("entries") ?: JSONArray()
            buildMap {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    val id = item.optLong("id")
                    val fingerprint = item.optString("fp")
                    val entry = entryFrom(item) ?: continue
                    put(id, Stored(fingerprint, entry))
                }
            }
        } catch (e: Exception) {
            // 结构损坏时按空表处理：下一轮扫描整表重建即可恢复，不做修复尝试
            CrashLogManager.logException("HighlightStore", "读取副歌片段表失败", e)
            emptyMap()
        }
    }

    private suspend fun write(context: Context, values: Map<Long, Stored>) = withContext(Dispatchers.IO) {
        try {
            val array = JSONArray()
            values.forEach { (trackId, stored) -> array.put(itemTo(trackId, stored)) }
            val root = JSONObject().put(KEY_VERSION, CURRENT_VERSION).put("entries", array)
            val file = File(context.filesDir, FILE_NAME)
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            val content = root.toString()
            temp.writeText(content)
            // 先写中转文件再改名：更新期间读表的装载不会读到半截 JSON
            if (!temp.renameTo(file)) {
                temp.delete()
                file.writeText(content)
            }
        } catch (e: Exception) {
            CrashLogManager.logException("HighlightStore", "写入副歌片段表失败", e)
        }
    }

    private fun itemTo(trackId: Long, stored: Stored): JSONObject = JSONObject().apply {
        put("id", trackId)
        put("fp", stored.fingerprint)
        when (val entry = stored.entry) {
            is HighlightEntry.Segment -> {
                put("kind", KIND_SEGMENT)
                put("start", entry.highlight.startMs)
                put("end", entry.highlight.endMs)
            }
            HighlightEntry.NoLyrics -> put("kind", KIND_NO_LYRICS)
            HighlightEntry.NoChorus -> put("kind", KIND_NO_CHORUS)
        }
    }

    private fun entryFrom(item: JSONObject): HighlightEntry? = when (item.optString("kind")) {
        KIND_SEGMENT -> {
            val start = item.optLong("start", -1L)
            val end = item.optLong("end", -1L)
            if (start in 0L until end) HighlightEntry.Segment(Highlight(start, end)) else null
        }
        KIND_NO_LYRICS -> HighlightEntry.NoLyrics
        KIND_NO_CHORUS -> HighlightEntry.NoChorus
        else -> null
    }
}
