package com.yichao.evilgodxu.data.music.analysis

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

// 曲目判定结果缓存：内存表 + 持久化 JSON，键含文件大小与时长，文件变化即失效。
// 音质异常、AI 识别与全曲分析锁定表共用：重启后直接复用；仅对新增/变更文件增量解码；
// 无法判定（解码不可用/时长无效）的结果同样入缓存为 false，避免同批文件每次重开反复分析，
// 识别策略升级后由调用方「刷新」对未锁定曲目强制重算（见 analyzeLibraryCombined 的 forceRecompute）
internal class TrackVerdictCache(
    private val cacheFileName: String,
) {
    val map = ConcurrentHashMap<String, Boolean>()

    // 进程内缓存状态：加载仅一次，落盘串行互斥，单曲写入去抖合并
    @Volatile
    private var loaded = false
    private val loadMutex = Mutex()
    private val persistMutex = Mutex()
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistScheduled = AtomicBoolean(false)

    // 批量增量校验期间每分析多少首新增文件落盘一次，收窄中断导致的缓存丢失窗口
    private val persistInterval = 20
    // 单曲校验路径的落盘去抖窗口：合并密集写入，避免逐曲全量重写
    private val persistDebounceMs = 800L

    fun get(key: String): Boolean? = map[key]

    // 首用时从私有目录加载持久化缓存，进程内仅加载一次
    suspend fun awaitLoaded(context: Context) {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) { loadCache(context) }
            loaded = true
        }
    }

    // 立即落盘（批量校验结束/周期触发），与去抖写共用互斥锁防并发交织
    suspend fun flush(context: Context) {
        persistMutex.withLock {
            withContext(Dispatchers.IO) { writeCache(context) }
        }
    }

    // 单曲校验结果异步落盘：去抖合并密集写入，任一时刻仅排一个写任务
    fun schedulePersist(context: Context) {
        if (!persistScheduled.compareAndSet(false, true)) return
        persistScope.launch {
            delay(persistDebounceMs)
            persistScheduled.set(false)
            flush(context.applicationContext)
        }
    }

    // 读取持久化缓存：文件缺失或损坏视为空缓存，逐条容错不影响整体
    private fun loadCache(context: Context) {
        val file = cacheFile(context)
        if (!file.isFile) return
        runCatching {
            val obj = JSONObject(file.readText())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                runCatching { map[key] = obj.getBoolean(key) }
            }
        }
    }

    private fun cacheFile(context: Context): File = File(context.filesDir, cacheFileName)

    // 全量快照写盘：先写临时文件再原子重命名，避免进程中断残留半截 JSON
    private fun writeCache(context: Context) {
        runCatching {
            val file = cacheFile(context)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$cacheFileName.tmp")
            val content = JSONObject().apply {
                map.forEach { (key, value) -> put(key, value) }
            }.toString()
            tmp.writeText(content)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                file.writeText(content)
            }
        }
    }

    companion object {
        /** 音质异常判定缓存文件名 */
        internal const val FILE_NAME_FAKE_LOSSLESS = "fake_lossless_cache.json"

        /** AI 识别判定缓存文件名 */
        internal const val FILE_NAME_AI_MUSIC = "ai_music_cache.json"

        /** 全曲分析锁定表文件名 */
        internal const val FILE_NAME_FULL_ANALYSIS = "full_analysis_lock.json"

        /** 已登记的判定缓存文件名：缓存台账据此统计占用，新增判定缓存必须在此登记 */
        internal val KNOWN_FILE_NAMES = listOf(
            FILE_NAME_FAKE_LOSSLESS,
            FILE_NAME_AI_MUSIC,
            FILE_NAME_FULL_ANALYSIS,
        )
    }
}