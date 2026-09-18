package com.yichao.evilgodxu.data.music.blacklist

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 歌曲黑名单持久化。
 *
 * 以「歌名 + 歌手」归一化后的文本键存储，而非曲目 ID：曲目 ID 由扫描/下载过程生成，
 * 文件被删除或重新扫描后即失效，而拉黑意图与文件是否存在无关。文本键使黑名单与曲库解耦，
 * 因此曲目删除后无需同步清理黑名单条目。
 *
 * 拉黑条目与跳过反馈累积的特征同属黑名单算法输入，一并落盘：二者表达的都是用户的长期偏好，
 * 没有「只在本次进程内有效」的说法，重启后应当继续生效。
 */
internal object BlacklistStore {

    private const val PREFS_NAME = "music_blacklist"
    private const val KEY_ENTRIES = "blacklist_entries"
    private const val KEY_SKIP_FEATURES = "blacklist_skip_features"

    // 进程内快照：播放列表与推荐算法共用同一份状态，避免各页面各存一份后相互漂移
    var keys by mutableStateOf<Set<String>>(emptySet())
        private set

    // 是否已从磁盘载入：页面在载入完成前不应据空快照判定「无黑名单」
    var isLoaded by mutableStateOf(false)
        private set

    // 跳过反馈：记录被跳过曲目的概念特征与命中次数，用于对同类内容降权
    var skippedFeatures by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /** 记录一次跳过：曲目特征计入黑名单，同特征在后续排序中被降权 */
    suspend fun recordSkip(context: Context, features: Set<String>) {
        if (features.isEmpty()) return
        ensureLoaded(context)
        val updated = skippedFeatures.toMutableMap()
        features.forEach { updated[it] = (updated[it] ?: 0) + 1 }
        writeSkipFeatures(context, updated)
        skippedFeatures = updated
    }

    /** 首次访问时从磁盘载入一次，后续读取走内存快照 */
    suspend fun ensureLoaded(context: Context) {
        if (isLoaded) return
        val stored = read(context)
        keys = stored.entries
        skippedFeatures = stored.skipFeatures
        isLoaded = true
    }

    suspend fun add(context: Context, track: MusicTrack) = add(context, track.title, track.artist)

    /** 拉黑一首歌，返回是否新增（重复拉黑不产生写入） */
    suspend fun add(context: Context, title: String, artist: String): Boolean {
        // 未载入即写入会以空快照为基准覆盖磁盘，丢掉既有条目
        ensureLoaded(context)
        val key = keyOf(title, artist).takeIf { it.isNotEmpty() } ?: return false
        if (key in keys) return false
        val updated = keys + key
        write(context, updated)
        keys = updated
        return true
    }

    suspend fun reset(context: Context) {
        ensureLoaded(context)
        write(context, emptySet())
        writeSkipFeatures(context, emptyMap())
        keys = emptySet()
        // 重置覆盖算法全部输入：拉黑条目与跳过反馈累积的特征一并清空
        skippedFeatures = emptyMap()
    }

    /** 归一化黑名单键：忽略大小写、括号补充信息与空白标点，使同一首歌跨平台命中同一键 */
    fun keyOf(title: String, artist: String): String {
        val normalizedTitle = normalize(title)
        if (normalizedTitle.isEmpty()) return ""
        return "$normalizedTitle|${normalize(artist)}"
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("\\([^)]*\\)|（[^）]*）|\\[[^]]*]|【[^】]*】"), "")
        .replace(Regex("[\\s\\p{Punct}、，。！？·—～]+"), "")
        .trim()

    private suspend fun read(context: Context): StoredBlacklist = withContext(Dispatchers.IO) {
        try {
            val preferences = prefs(context)
            val entries = preferences.getString(KEY_ENTRIES, null)?.let { raw ->
                val array = JSONArray(raw)
                List(array.length()) { array.optString(it).trim() }.filter { it.isNotEmpty() }.toSet()
            }.orEmpty()
            val skipFeatures = preferences.getString(KEY_SKIP_FEATURES, null)?.let { raw ->
                val json = JSONObject(raw)
                json.keys().asSequence().associateWith { json.optInt(it) }.filterValues { it > 0 }
            }.orEmpty()
            StoredBlacklist(entries, skipFeatures)
        } catch (e: Exception) {
            CrashLogManager.logException("BlacklistStore", "读取黑名单失败", e)
            StoredBlacklist(emptySet(), emptyMap())
        }
    }

    private suspend fun write(context: Context, values: Set<String>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        values.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private suspend fun writeSkipFeatures(context: Context, values: Map<String, Int>) =
        withContext(Dispatchers.IO) {
            val json = JSONObject()
            values.forEach { (feature, count) -> json.put(feature, count) }
            prefs(context).edit().putString(KEY_SKIP_FEATURES, json.toString()).apply()
        }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 黑名单落盘内容：拉黑条目 + 跳过反馈累积的特征 */
    private data class StoredBlacklist(
        val entries: Set<String>,
        val skipFeatures: Map<String, Int>,
    )
}