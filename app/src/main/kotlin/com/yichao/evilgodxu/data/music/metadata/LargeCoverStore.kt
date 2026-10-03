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

/**
 * 首页大封面（竖屏沉浸封面、横屏融合封面）的高清档缓存：内存驻留 + 落盘 WebP。
 *
 * 与 [CurrentCoverCache] 的分工：后者只留当前曲目的一张 512 缩略图，服务冷启动首帧与背景取色；
 * 本缓存服务铺满首屏的大封面，规格为长边 [MAX_EDGE_PX]、有损 WebP，
 * 并驻留当前曲及其前后各一首 —— 用户往返切歌时封面直接命中，不必现场解析内嵌原图。
 *
 * 落盘同样只保留这几张：整库封面落盘会随听过的曲目无限增长，而往返只发生在相邻几首之间。
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

    // 同曲目并发只解一次：显示端与切歌预取会同时请求同一首；
    // 与封面略缩图入口同构 —— 解码本身不占锁，不同曲目之间不互相排队
    private val lock = Mutex()
    private val inflight = mutableMapOf<String, Deferred<Bitmap?>>()
    // 在飞解码不随任一调用方取消：结果通常还被另一个等待者共享
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 作废代数：封面重写会清空缓存，此时仍在飞的高清解码拿到的是旧图，
    // 回填会把刚作废的封面重新写回内存与磁盘，故在回填前用它一票否决
    @Volatile
    private var generation = 0

    /** 缓存落点：供缓存台账统计与回收引用，路径只在此定义一次 */
    fun location(context: Context): File = File(context.cacheDir, DIR_NAME)

    /** 同步取已驻留的封面；未驻留返回 null，由调用方回退首帧缩略图或异步取图 */
    fun peek(audioUri: String): Bitmap? = resident.get(audioUri)

    /**
     * 一阶段取图：只取能立刻拿到的档位 —— 内存驻留 → 落盘高清档 → 系统最大档略缩图。
     *
     * 用户从列表直接点选任意曲目时，该曲目没有预取过，高清原图的读取与解码可能要 1–3 秒
     * （尤其是内嵌封面很大的无损文件）。先以系统略缩图出图，再由 [get] 解码高清原图无缝替换，
     * 封面首帧就不会空等。
     */
    suspend fun quick(context: Context, track: MusicTrack): Bitmap? {
        resident.get(track.audioUri)?.let { return it }
        withContext(Dispatchers.IO) { decodeFile(fileFor(context, track.audioUri)) }?.let { cached ->
            resident.put(track.audioUri, cached)
            return cached
        }
        return MusicCoverLoader.load(context, track, MusicCoverLoader.SYSTEM_THUMBNAIL_MAX_SIZE_PX)
    }

    /** 二阶段取图：解码内嵌原图得到长边至 [MAX_EDGE_PX] 的高清档；无可用封面时返回 null，由显示端显示占位图 */
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
            // 发起方被取消时撤掉在飞解码，避免条目永久滞留在表中
            if (owner) pending.cancel()
            // 等待方自身未取消时，只是共享的那次解码被发起方撤掉：按「本次未取到」返回。
            // 不能把别人的取消当成自己的取消抛出去 —— 那会静默终止调用方（如切歌预取）的协程
            currentCoroutineContext().ensureActive()
            null
        } finally {
            // 取消路径同样要清理，故以 NonCancellable 保证清理动作不被已取消的作业拦下
            if (owner) withContext(NonCancellable) {
                lock.withLock { if (inflight[key] === pending) inflight.remove(key) }
            }
        }
    }

    /**
     * 预取首页大封面：切歌后调用，使往返切歌不必现场解析音频内嵌原图。
     *
     * 入参顺序即解码优先级，由调用方给出（当前曲 → 下一曲 → 上一曲）：
     * 解码很重，逐个排队执行才能保证当前曲先出高清，也才给可视区的即时取图让出资源。
     *
     * 高清档落盘的同时顺手算出各曲目的背景渲染取色并记入 [CoverColorCache]：
     * 取色与高清封面同源，显示端切歌首帧就能同步拿到新曲目的色调，
     * 不必再等背景自己的 64px 略缩图解码 —— 否则封面已换、背景还停在上一首。
     *
     * 取完即把落盘产出裁剪到这几张，磁盘占用与内存驻留保持同一口径。
     */
    suspend fun prefetch(context: Context, tracks: List<MusicTrack>) {
        if (tracks.isEmpty()) return
        tracks.forEach { track ->
            val cover = get(context, track) ?: return@forEach
            extractCoverBackgroundColors(cover)?.let { CoverColorCache.put(track.audioUri, it) }
        }
        prune(context, tracks.map { it.audioUri }.toSet())
    }

    /** 作废驻留结果与落盘产出：封面被重写后旧图不再成立 */
    suspend fun clear(context: Context) {
        generation++
        resident.evictAll()
        withContext(Dispatchers.IO) { runCatching { location(context).deleteRecursively() } }
    }

    // 在飞解码体：落盘命中即复用，否则解内嵌原图并落盘
    private suspend fun loadHighRes(context: Context, track: MusicTrack): Bitmap? {
        val startGeneration = generation
        resident.get(track.audioUri)?.let { return it }
        withContext(Dispatchers.IO) { decodeFile(fileFor(context, track.audioUri)) }?.let { cached ->
            resident.put(track.audioUri, cached)
            return cached
        }
        val loaded = MusicCoverLoader.loadLarge(context, track, MAX_EDGE_PX) ?: return null
        // 到此为止都没有新的挂起点：解码期间被取消（切歌、封面重写）时，上一行的挂起调用
        // 会直接把取消抛上来，落盘编码与内存回填都不会执行 —— 重活就此让给接替的新解码
        if (startGeneration != generation) return null
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

    // 落盘裁剪：只保留给定曲目的文件，其余一并清掉。
    // 未改名的中转文件跳过：写入方的改名与裁剪可能并发，删掉正在写的那份会让落盘静默失败；
    // 这类残留由下一次写入同名文件覆盖，也随缓存清理整目录回收
    private suspend fun prune(context: Context, keepAudioUris: Set<String>) = withContext(Dispatchers.IO) {
        val keep = keepAudioUris.map { fileFor(context, it).name }.toSet()
        location(context).listFiles()?.forEach {
            if (it.name !in keep && !it.name.endsWith(TEMP_SUFFIX)) it.delete()
        }
    }

    // 文件名由音频 URI 推出：换歌即写到新名字，下次按同一推导取回，不会读到别首曲目的封面
    private fun fileFor(context: Context, audioUri: String): File =
        File(location(context), "large_cover_${audioUri.hashCode().toUInt().toString(16)}.webp")

    private fun decodeFile(file: File): Bitmap? = runCatching {
        if (!file.isFile) return@runCatching null
        BitmapFactory.decodeFile(file.absolutePath)
    }.getOrNull()
}
