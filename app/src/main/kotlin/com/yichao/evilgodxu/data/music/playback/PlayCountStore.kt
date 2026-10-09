package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 累计播放次数持久化。
 *
 * 与常听（[MusicPlaybackState.recentPlayedIds]）是两套口径、服务两个目的：
 * 常听是「最近 3 天内播够 2 次」的短期窗口，表达当下在听什么；
 * 本表是**跨窗口不清零**的长期累计，表达一首歌被听过多少次。回忆模式要在曲库里找出
 * 「被尘封的歌」，判据只能是后者 —— 短期窗口天生表达不了「买了半年一次没听」。
 *
 * 递增与常听记录在同一时刻发生（曲目自然播完），但**心动模式的片段播放不得计入**：
 * 40 秒的副歌片段不是一次完整播放，计入会把每首都刷成「听过的」，直接毁掉本表的判据。
 * 该闸断由 [MusicPlaybackState] 在调用处显式判断，不在此处兜底 —— 收录时机属于播放语义，
 * 不属于存储层。
 *
 * 键为曲目 ID。与黑名单以「歌名+歌手」为键不同：黑名单要与曲库解耦（拉黑意图与文件是否存在无关），
 * 而播放次数本就依附于具体曲目，曲目从曲库消失后其计数一并回收（见 [prune]）。
 */
internal object PlayCountStore {

    private const val PREFS_NAME = "music_play_count"
    private const val KEY_COUNTS = "play_counts"

    // 进程内快照：曲库分析、回忆模式与设置页共用同一份，避免各读一次盘后相互漂移
    var counts by mutableStateOf<Map<Long, Int>>(emptyMap())
        private set

    // 是否已从磁盘载入：未载入即递增会以空快照为基准覆盖磁盘，丢掉全部历史计数
    var isLoaded by mutableStateOf(false)
        private set

    /**
     * 有播放计数的曲目数。
     *
     * 回忆模式的启用门槛以此为准：计数覆盖的曲目太少时，「最少播放」与「从没听过」无从区分，
     * 此时强行推荐只会把用户当下在听的歌也当成尘封曲推出来。
     */
    val trackedCount: Int get() = counts.size

    fun countOf(trackId: Long): Int = counts[trackId] ?: 0

    /** 首次访问时从磁盘载入一次，后续读取走内存快照 */
    suspend fun ensureLoaded(context: Context) {
        if (isLoaded) return
        counts = read(context)
        isLoaded = true
    }

    /**
     * 记一次完整播放。
     *
     * 未载入时先补载入，否则本次递增会以空快照为基准写盘、把历史计数清零。
     */
    suspend fun increment(context: Context, trackId: Long) {
        ensureLoaded(context)
        val updated = counts.toMutableMap()
        updated[trackId] = (updated[trackId] ?: 0) + 1
        write(context, updated)
        counts = updated
    }

    /**
     * 回收已不在曲库中的计数。
     *
     * 曲目被显式删除后其计数不再有引用，留着只会让表无限增长。与黑名单条目刻意保留不同：
     * 黑名单的键是文本，曲目删了再下回来仍应命中；计数依附于具体曲目身份，重下即新 ID，
     * 旧计数对其已无意义。
     */
    suspend fun prune(context: Context, validIds: Set<Long>) {
        ensureLoaded(context)
        val retained = counts.filterKeys { it in validIds }
        if (retained.size == counts.size) return
        write(context, retained)
        counts = retained
    }

    suspend fun reset(context: Context) {
        ensureLoaded(context)
        write(context, emptyMap())
        counts = emptyMap()
    }

    private suspend fun read(context: Context): Map<Long, Int> = withContext(Dispatchers.IO) {
        try {
            val raw = prefs(context).getString(KEY_COUNTS, null) ?: return@withContext emptyMap()
            raw.split(ENTRY_SEPARATOR)
                .mapNotNull { entry ->
                    val id = entry.substringBefore(FIELD_SEPARATOR).toLongOrNull() ?: return@mapNotNull null
                    val count = entry.substringAfter(FIELD_SEPARATOR, "").toIntOrNull()
                        ?.takeIf { it > 0 } ?: return@mapNotNull null
                    id to count
                }
                .toMap()
        } catch (e: Exception) {
            // 结构损坏时按无历史计数处理：判据退化为「全部尘封」，由门槛拦住，不冒险修复
            CrashLogManager.logException("PlayCountStore", "读取累计播放次数失败", e)
            emptyMap()
        }
    }

    private suspend fun write(context: Context, values: Map<Long, Int>) = withContext(Dispatchers.IO) {
        val encoded = values.entries.joinToString(ENTRY_SEPARATOR) { "${it.key}$FIELD_SEPARATOR${it.value}" }
        // 同步落盘：与常听记录同一时刻写入、同属播放统计，异步落盘在进程被杀时会丢计数，
        // 而本表的判据正是「累计了多少」，慢性丢失会让回忆模式长期偏向刚从窗口外掉出去的歌
        prefs(context).edit(commit = true) { putString(KEY_COUNTS, encoded) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val ENTRY_SEPARATOR = ","
    private const val FIELD_SEPARATOR = ":"
}
