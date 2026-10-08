package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import android.util.Size
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 封面取图统一入口：内存缓存命中即复用，未命中才查系统略缩图或解码音频内嵌封面，并把结果回填内存缓存。
 *
 * 取图口径按展示场景分级：缩略图档（列表行、光碟、轮播）走 [load]，
 * 首页大封面走 [loadLarge]（内嵌原图优先）；两处都由本入口决定来源，显示端只负责按尺寸请求。
 * 两条口径都遵循「首选来源失败即回退音频内嵌原图」，全部来源失败时由 [CoverSkipRegistry] 记入跳过标记，
 * 使列表反复滚动同一首无封面的曲目不再重复尝试。
 *
 * 本入口自身不产生落盘产出：当前曲目的缩略图由 [CurrentCoverCache] 落盘，首页大封面由 [LargeCoverStore] 落盘。
 */
internal object MusicCoverLoader {

    /** 系统媒体略缩图自身的尺寸上限：请求更大档位只是插值放大 */
    const val SYSTEM_THUMBNAIL_MAX_SIZE_PX = 512

    private data class Key(val audioUri: String, val sizePx: Int)

    // 同键并发只取一次图：可视区行的即时加载与列表预取会同时请求同一首歌，
    // 不合并就会向 MediaProvider 发两次查询、解两次码，而结果只有一份
    private val lock = Mutex()
    private val inflight = mutableMapOf<Key, Deferred<Bitmap?>>()
    // 在飞取图不随任一调用方取消：同一结果通常还被另一个等待者共享
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 取曲目封面（按最长边 [sizePx] 请求解码）；曲目无可用封面或读取失败时返回 null */
    suspend fun load(context: Context, track: MusicTrack, sizePx: Int): Bitmap? {
        // 已被判定无封面可取：直接按无封面返回，不再重复查询系统略缩图与解析音频文件
        if (CoverSkipRegistry.isSkipped(track)) return null
        val key = Key(track.audioUri, sizePx)
        val (pending, owner) = lock.withLock {
            inflight[key]?.let { return@withLock it to false }
            scope.async { decode(context, track, sizePx) }
                .also { inflight[key] = it } to true
        }
        return try {
            val loaded = pending.await()
            if (loaded == null) {
                // 所有来源都取不到封面：记入跳过标记，列表反复滚动同一首时不再重复尝试
                CoverSkipRegistry.markSkipped(track)
            }
            loaded
        } catch (e: CancellationException) {
            // 发起方被取消时撤掉在飞取图，避免条目永久滞留在表中；仅等待的调用方直接退出等待
            if (owner) pending.cancel()
            throw e
        } finally {
            // 取消路径同样要清理，故以 NonCancellable 保证清理动作不被已取消的作业拦下
            if (owner) withContext(NonCancellable) {
                lock.withLock { if (inflight[key] === pending) inflight.remove(key) }
            }
        }
    }

    /**
     * 内存缓存命中查询：返回该曲目已驻留的缩略图，未命中返回 null。
     *
     * 供两处共用，且两处必须同一口径：显示端首帧同步取用它以避免先闪占位符，
     * 列表预取用它跳过已就位的项。判定若不一致，预取会空转 ——
     * 既没让行提前出图，还占着系统略缩图查询。
     *
     * 本函数不上锁也不触发取图，可在组合期同步调用。
     */
    fun cachedThumbnail(track: MusicTrack, sizePx: Int): Bitmap? =
        if (track.isMediaStoreIndexed) {
            SystemThumbnailCache.get(track.audioUri, sizePx)
        } else {
            EmbeddedCoverCache.peek(track.id, sizePx)
        }

    /**
     * 预取单曲封面：走与显示端相同的 [load] 入口，已驻留或正在取图的直接短路。
     *
     * 顺序与并发由调用方（列表邻域预取）掌管：它知道哪一项离视口最近、窗口何时移走，
     * 本入口只负责「取这一张，失败了也别把调用方打断」。
     */
    suspend fun prefetch(context: Context, track: MusicTrack, sizePx: Int) {
        try {
            load(context, track, sizePx)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 单首取图失败不影响后续预取：显示端滚入视口时仍会按需重试
        }
    }

    /**
     * 首页大封面取图口径：首选解码音频内嵌原图，并按长边不超过 [maxEdgePx] 收口。
     *
     * 首页大封面铺满首屏，系统略缩图上限 512 会被放大渲染而发虚，故不走 [load] 的「略缩图优先」口径；
     * 内嵌原图取不到时回退 [load]（系统略缩图），与其余封面显示处的来源一致。
     * 本函数不驻留结果：内存与落盘的往返复用由 [LargeCoverStore] 持有。
     */
    suspend fun loadLarge(context: Context, track: MusicTrack, maxEdgePx: Int): Bitmap? {
        if (CoverSkipRegistry.isSkipped(track)) return null
        EmbeddedCoverReader.readFitted(context, track.audioUri, track.path, maxEdgePx)?.let { return it }
        return load(context, track, SYSTEM_THUMBNAIL_MAX_SIZE_PX)
    }

    private suspend fun decode(context: Context, track: MusicTrack, sizePx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            if (track.isMediaStoreIndexed) {
                // 首选系统媒体库维护的略缩图（扫描时生成、音频内嵌封面被重写后随媒体扫描重建）
                val thumbnail = SystemThumbnailCache.get(track.audioUri, sizePx) ?: runCatching {
                    val loaded = context.contentResolver.loadThumbnail(
                        track.audioUri.toUri(),
                        Size(sizePx, sizePx),
                        null,
                    )
                    fitToSize(loaded, sizePx)
                }.getOrNull()?.also { SystemThumbnailCache.put(track.audioUri, sizePx, it) }
                if (thumbnail != null) return@withContext thumbnail
            }
            // 一级兜底：首选来源缺席或读取失败时回退音频文件的内嵌原图，按同一请求尺寸自行缩放。
            // 平台对部分容器（WAV/AIFF/DSF/DFF/APE）读不到内嵌图片，这类曲目只能由此取到；
            // 其余容器即便平台结论是「无封面」，这里也只多付一次读取 —— 失败结论由 CoverSkipRegistry
            // 记住，同一首曲目不会反复付这笔开销。
            // 读取与解码的去重、往返复用由缓存持有，调用方只负责按需请求
            EmbeddedCoverCache.cover(context, track, sizePx)
        }

    // 系统略缩图以请求尺寸为解码目标，但「实际以系统返回为准」：取不到更小条目时，
    // 提供方会直接把自身缓存的原图交回（最大 512×512）。此处统一收口到请求尺寸——
    // 否则一次 28dp 列表行取图就会驻留近 1MB 的位图，且缓存键声称的尺寸与实际内容不符。
    // 缩放失败（如内存不足）时退回原图，与未收口前的表现一致，不因此丢掉整张封面
    private fun fitToSize(bitmap: Bitmap, sizePx: Int): Bitmap {
        val software = toSoftware(bitmap)
        // 发生了转换说明原位图是硬件位图：本入口是它唯一的持有者，转换出的副本已接替它，
        // 立即释放以免其显存滞留到 GC（复制失败时软件副本就是它自己，不能释放）
        if (software !== bitmap) bitmap.recycle()
        return if (max(software.width, software.height) <= sizePx) software
        else runCatching { scaleDown(software, sizePx) }.getOrDefault(software)
    }

    // 硬件位图不能参与软件绘制：界面侧会为主题切换的整屏快照以软件画布重绘一次，
    // 层级里混入硬件位图会直接抛 IllegalArgumentException 打断快照。
    // 系统略缩图在 API 29+ 上返回的正是硬件位图，故取图的唯一出口处收口为软件位图；
    // 复制失败（内存不足）时退回原位图，宁可让快照降级也不要丢掉整张封面
    private fun toSoftware(bitmap: Bitmap): Bitmap =
        if (bitmap.config != Bitmap.Config.HARDWARE) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap

    private fun scaleDown(bitmap: Bitmap, sizePx: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        val scale = sizePx.toFloat() / longest
        val scaled = bitmap.scale(
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            filter = true,
        )
        // 缩放产生的是新位图，原位图在本入口内不再被引用，立即释放以免大图滞留到 GC
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}
