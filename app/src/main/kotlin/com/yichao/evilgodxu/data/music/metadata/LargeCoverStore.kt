package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 首页大封面（竖屏沉浸封面、横屏融合封面）的高清档缓存：内存驻留 + 落盘 WebP。
 *
 * 与 [CurrentCoverCache] 的分工：后者只留当前曲目的一张 512 缩略图，服务冷启动首帧与背景取色；
 * 本缓存服务铺满首屏的大封面，规格为长边 [MAX_EDGE_PX]、有损 WebP，
 * 并驻留「上一曲、当前曲、下一曲」三张 —— 用户在这三首之间往返时封面直接命中，切歌不必现场解析内嵌原图。
 *
 * 落盘同样只保留这三张：整库封面落盘会随听过的曲目无限增长，而往返只发生在这三首之间。
 * 该产出可由音频文件重建，属可随时回收的系统缓存；封面被重写后由 [clear] 作废。
 */
internal object LargeCoverStore {

    /** 大封面的长边上限：铺满首屏（含横屏全宽）所需的分辨率，再大只是浪费内存与磁盘 */
    const val MAX_EDGE_PX = 2048

    private const val DIR_NAME = "large_cover"
    private const val TEMP_SUFFIX = ".tmp"

    // 有损 WebP：与同质量的 JPEG 相比体积更小，且原生支持透明通道
    private const val WEBP_QUALITY = 90

    // 内存驻留张数：上一曲、当前曲、下一曲
    private const val RESIDENT_CAPACITY = 3

    private const val TAG = "LargeCoverStore"

    private val resident = object : LruCache<String, Bitmap>(RESIDENT_CAPACITY) {}

    // 取图与落盘的互斥：同曲目的并发请求（首页大图与切歌预取）串行化，后来的那次直接命中缓存
    private val lock = Mutex()

    /** 缓存落点：供缓存台账统计与回收引用，路径只在此定义一次 */
    fun location(context: Context): File = File(context.cacheDir, DIR_NAME)

    /** 同步取已驻留的封面；未驻留返回 null，由调用方回退首帧缩略图或异步取图 */
    fun peek(audioUri: String): Bitmap? = resident.get(audioUri)

    /** 取曲目的首页大封面（长边至 [MAX_EDGE_PX]）；无可用封面时返回 null，由显示端显示占位图 */
    suspend fun get(context: Context, track: MusicTrack): Bitmap? = lock.withLock { getLocked(context, track) }

    /**
     * 预取「上一曲、当前曲、下一曲」三张：切歌后调用，使往返切歌不必现场解析音频内嵌原图。
     *
     * 三张取完即把落盘产出裁剪到这三张，磁盘占用与内存驻留保持同一口径。
     */
    suspend fun prefetch(context: Context, tracks: List<MusicTrack>) {
        if (tracks.isEmpty()) return
        lock.withLock {
            tracks.forEach { getLocked(context, it) }
            prune(context, tracks.map { it.audioUri }.toSet())
        }
    }

    /** 作废驻留结果与落盘产出：封面被重写后旧图不再成立 */
    suspend fun clear(context: Context) {
        resident.evictAll()
        withContext(Dispatchers.IO) { runCatching { location(context).deleteRecursively() } }
    }

    // 调用方已持有 lock：内部不再自行加锁，避免同一协程重复进入互斥量
    private suspend fun getLocked(context: Context, track: MusicTrack): Bitmap? {
        resident.get(track.audioUri)?.let { return it }
        withContext(Dispatchers.IO) { decodeFile(fileFor(context, track.audioUri)) }?.let { cached ->
            resident.put(track.audioUri, cached)
            return cached
        }
        val loaded = MusicCoverLoader.loadLarge(context, track, MAX_EDGE_PX) ?: return null
        persist(context, track.audioUri, loaded)
        resident.put(track.audioUri, loaded)
        return loaded
    }

    // 落盘：先写中转文件再改名，进程在写入中被杀不会留下半截封面
    private suspend fun persist(context: Context, audioUri: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        // 硬件位图不可直接压缩，转为软件位图；改造结果与显示端同一张图
        val software = bitmap.takeIf { it.config != Bitmap.Config.HARDWARE }
            ?: bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: return@withContext
        runCatching {
            val dir = location(context)
            if (!dir.isDirectory && !dir.mkdirs()) return@runCatching
            val target = fileFor(context, audioUri)
            val temp = File(dir, target.name + TEMP_SUFFIX)
            temp.outputStream().use { software.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, it) }
            if (!temp.renameTo(target)) {
                temp.delete()
                return@runCatching
            }
        }.onFailure {
            CrashLogManager.logException(TAG, "首页大封面落盘失败: $audioUri", it)
        }
    }

    // 落盘裁剪：只保留给定曲目的文件，其余（含未改名的中转文件）一并清掉
    private suspend fun prune(context: Context, keepAudioUris: Set<String>) = withContext(Dispatchers.IO) {
        val keep = keepAudioUris.map { fileFor(context, it).name }.toSet()
        location(context).listFiles()?.forEach { if (it.name !in keep) it.delete() }
    }

    // 文件名由音频 URI 推出：换歌即写到新名字，下次按同一推导取回，不会读到别首曲目的封面
    private fun fileFor(context: Context, audioUri: String): File =
        File(location(context), "large_cover_${audioUri.hashCode().toUInt().toString(16)}.webp")

    private fun decodeFile(file: File): Bitmap? = runCatching {
        if (!file.isFile) return@runCatching null
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()
}
