package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 大封面缓存：内存 + 落盘 WebP，只留相邻三首 */
internal object LargeCoverStore {

    /** 大封面长边上限 2048 */
    const val MAX_EDGE_PX = 2048

    private const val DIR_NAME = "large_cover"
    private const val TEMP_SUFFIX = ".tmp"

    // 有损 WebP，体积小且支持透明
    private const val WEBP_QUALITY = 90

    // 驻留上一曲、当前曲、下一曲
    private const val RESIDENT_CAPACITY = 3

    private const val TAG = "LargeCoverStore"

    private val resident = object : LruCache<String, Bitmap>(RESIDENT_CAPACITY) {}

    // 同曲目并发只解一次，不同曲目不排队
    private val lock = Mutex()
    private val inflight = mutableMapOf<String, Deferred<Bitmap?>>()
    // 在飞解码不随调用方取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 作废代数，挡住作废后回填的旧图
    @Volatile
    private var generation = 0

    /** 缓存落点，供台账统计与回收 */
    fun location(context: Context): File = File(context.cacheDir, DIR_NAME)

    /** 同步取已驻留封面，未驻留为 null */
    fun peek(audioUri: String): Bitmap? = resident.get(audioUri)

    /** 取高清档：内存 → 落盘 → 解内嵌原图；无封面返回 null */
    suspend fun get(context: Context, track: MusicTrack): Bitmap? {
        resident.get(track.audioUri)?.let { return it }
        val key = track.audioUri
        val (pending, owner) = lock.withLock {
            inflight[key]?.let { return@withLock it to false }
            scope.async { loadHighRes(context, track) }.also { inflight[key] = it } to true
        }
        return try {
            pending.await()
        } catch (e: CancellationException) {
            // 发起方取消时撤掉在飞解码
            if (owner) pending.cancel()
            // 别人的取消按本次未取到处理，不向外抛
            currentCoroutineContext().ensureActive()
            null
        } finally {
            // 清理不经已取消的作业
            if (owner) withContext(NonCancellable) {
                lock.withLock { if (inflight[key] === pending) inflight.remove(key) }
            }
        }
    }

    /** 预取首页大封面：入参顺序即解码优先级，并顺带算取色 */
    suspend fun prefetch(context: Context, tracks: List<MusicTrack>) {
        if (tracks.isEmpty()) return
        tracks.forEach { track ->
            val cover = get(context, track) ?: return@forEach
            // 已有取色直接复用，避免重算
            if (CoverColorCache.get(track.audioUri) != null) return@forEach
            extractCoverBackgroundColors(cover)?.let { CoverColorCache.put(track.audioUri, it) }
        }
        prune(context, tracks.map { it.audioUri }.toSet())
    }

    /** 作废驻留与落盘产出 */
    suspend fun clear(context: Context) {
        generation++
        resident.evictAll()
        withContext(Dispatchers.IO) { runCatching { location(context).deleteRecursively() } }
    }

    // 在飞解码：落盘命中即复用，否则解原图并落盘
    private suspend fun loadHighRes(context: Context, track: MusicTrack): Bitmap? {
        val startGeneration = generation
        resident.get(track.audioUri)?.let { return it }
        val cached = withContext(Dispatchers.IO) { decodeFile(fileFor(context, track.audioUri)) }
        // 作废后的落盘档不成立，改解原图
        if (cached != null && startGeneration == generation) {
            resident.put(track.audioUri, cached)
            return cached
        }
        val loaded = MusicCoverLoader.loadLarge(context, track, MAX_EDGE_PX) ?: return null
        // 解码被取消则不复用结果，让给新的解码
        if (startGeneration != generation) return null
        persist(context, track.audioUri, loaded)
        resident.put(track.audioUri, loaded)
        return loaded
    }

    // 先写中转再改名，避免半截封面
    private suspend fun persist(context: Context, audioUri: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        // 硬件位图不可压缩，转软件位图
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

    // 裁剪落盘只留给定曲目；跳过未改名的中转
    private suspend fun prune(context: Context, keepAudioUris: Set<String>) = withContext(Dispatchers.IO) {
        val keep = keepAudioUris.map { fileFor(context, it).name }.toSet()
        location(context).listFiles()?.forEach {
            if (it.name !in keep && !it.name.endsWith(TEMP_SUFFIX)) it.delete()
        }
    }

    /** 回收未改名的 .tmp 残留 */
    fun reclaimTempResidue(context: Context, olderThanMs: Long) {
        reclaimTempResidueIn(location(context), TEMP_SUFFIX, olderThanMs)
    }

    // 文件名由音频 URI 推导
    private fun fileFor(context: Context, audioUri: String): File =
        File(location(context), "large_cover_${audioUri.hashCode().toUInt().toString(16)}.webp")

    private fun decodeFile(file: File): Bitmap? = runCatching {
        if (!file.isFile) return@runCatching null
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()
}
