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
 * 「无法定位」与「确认没有副歌」必须是两个不同的结论：前者只是数据不足（没有歌词、歌词无时间轴、
 * 音频暂不可读），据此跳过会让整库在数据就绪前被跳过；后者才是真的没有高潮段，只有它可以跳过该曲。
 */
internal sealed interface HighlightEntry {

    /** 已定位出片段 */
    data class Segment(val highlight: Highlight) : HighlightEntry

    /** 无法定位：数据不足，调用方据此按整曲播放，**不得跳过** */
    data object Unresolved : HighlightEntry

    /** 有可用歌词与音频且确认没有高潮段：调用方据此跳过该曲 */
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
 * 表按「歌词指纹 + 算法版本」判定新鲜度：任一变化即重算。指纹并入算法版本是有意的 ——
 * 定位算法升级后，旧结果必须整体作废重算，而这一步靠版本号触发，不靠人去清缓存。
 *
 * 条目另记「是否已精修」（[Stored.refined]）：粗扫只用歌词、不碰音频，廉价；精修才按需解码音频
 * 补足歌词判不了的曲子。于是「从不心动模式的用户」永远只付粗扫的代价，进入模式后才做精修。
 */
internal object HighlightStore {

    private const val FILE_NAME = "highlight_segments.json"
    private const val KEY_VERSION = "version"
    private const val CURRENT_VERSION = 1

    // 定位算法版本：参与指纹，算法改动后旧结果自动作废重算
    private const val ALGORITHM_VERSION = "v2"

    // 条目类型落盘标识
    private const val KIND_SEGMENT = "segment"
    private const val KIND_UNRESOLVED = "unresolved"
    private const val KIND_NO_CHORUS = "no_chorus"
    // 旧版「无歌词」标识：语义等同 Unresolved，读取时一并归一
    private const val KIND_LEGACY_NO_LYRICS = "no_lyrics"

    private class Stored(
        val fingerprint: String,
        /** 是否已在「可解码音频」的前提下判定过，见 [isUpToDate] */
        val refined: Boolean,
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
     * 歌词与曲目属性指纹：行数、首末时间戳与时长足以识别歌词或时间轴是否被替换。
     *
     * 时长参与指纹是因为片段边界以它为上限：时长先缺后补时，片段区间可能随之变化，须重算。
     */
    fun fingerprintOf(track: MusicTrack): String {
        val lines = track.lyricLines
        val base = if (lines.isEmpty()) {
            "none:${track.lyricCachePath}"
        } else {
            "${lines.size}:${lines.first().timeMs}:${lines.last().timeMs}"
        }
        return "$ALGORITHM_VERSION:${track.duration}:$base"
    }

    /**
     * 该曲是否已按当前口径扫描过。
     *
     * 粗扫（[audioRefinement] 为 false）只认「指纹未变」；精修扫描额外要求该条已精修过，
     * 于是粗扫留下的未精修条目会在进入心动模式后被重算，而已精修条目不会被反复重算。
     */
    fun isUpToDate(track: MusicTrack, audioRefinement: Boolean): Boolean {
        val stored = table[track.id] ?: return false
        if (stored.fingerprint != fingerprintOf(track)) return false
        return stored.refined || !audioRefinement
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
                merged[trackId] = Stored(result.fingerprint, result.refined, result.entry)
            }
            table = merged
            write(context, merged)
        }
    }

    /** 单曲扫描结果：指纹、是否已精修、判定条目三者一并落盘 */
    class ScanResult(
        val fingerprint: String,
        val refined: Boolean,
        val entry: HighlightEntry,
    )

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
                    put(id, Stored(fingerprint, item.optBoolean("refined", false), entry))
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
        put("refined", stored.refined)
        when (val entry = stored.entry) {
            is HighlightEntry.Segment -> {
                put("kind", KIND_SEGMENT)
                put("start", entry.highlight.startMs)
                put("end", entry.highlight.endMs)
            }
            HighlightEntry.Unresolved -> put("kind", KIND_UNRESOLVED)
            HighlightEntry.NoChorus -> put("kind", KIND_NO_CHORUS)
        }
    }

    private fun entryFrom(item: JSONObject): HighlightEntry? = when (item.optString("kind")) {
        KIND_SEGMENT -> {
            val start = item.optLong("start", -1L)
            val end = item.optLong("end", -1L)
            if (start in 0L until end) HighlightEntry.Segment(Highlight(start, end)) else null
        }
        KIND_UNRESOLVED, KIND_LEGACY_NO_LYRICS -> HighlightEntry.Unresolved
        KIND_NO_CHORUS -> HighlightEntry.NoChorus
        else -> null
    }
}
