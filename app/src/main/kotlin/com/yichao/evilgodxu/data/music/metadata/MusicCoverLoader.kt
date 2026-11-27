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

/** 封面取图入口：内存命中复用，否则查系统缩略图或内嵌。
 *  缩略走 load，大图走 loadLarge。 */
internal object MusicCoverLoader {

    /** 系统媒体略缩图自身的尺寸上限：请求更大档位只是插值放大 */
    const val SYSTEM_THUMBNAIL_MAX_SIZE_PX = 512

    private data class Key(val audioUri: String, val sizePx: Int)

    // 同键并发只取一次图
    private val lock = Mutex()
    private val inflight = mutableMapOf<Key, Deferred<Bitmap?>>()
    // 在飞取图不随调用方取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 取曲目缩略图；无封面或失败返回 null */
    suspend fun load(context: Context, track: MusicTrack, sizePx: Int): Bitmap? {
        // 已判定无封面，不再重复查询
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
                // 全部来源失败则记入跳过标记
                CoverSkipRegistry.markSkipped(track)
            }
            loaded
        } catch (e: CancellationException) {
            // 发起方取消时撤掉在飞取图
            if (owner) pending.cancel()
            throw e
        } finally {
            // 清理不经已取消的作业
            if (owner) withContext(NonCancellable) {
                lock.withLock { if (inflight[key] === pending) inflight.remove(key) }
            }
        }
    }

    /** 内存命中查询；不上锁不触发取图，可同步调用 */
    fun cachedThumbnail(track: MusicTrack, sizePx: Int): Bitmap? =
        if (track.isMediaStoreIndexed) {
            SystemThumbnailCache.get(track.audioUri, sizePx)
        } else {
            EmbeddedCoverCache.peek(track.id, sizePx)
        }

    /** 预取单曲封面，失败不打断调用方 */
    suspend fun prefetch(context: Context, track: MusicTrack, sizePx: Int) {
        try {
            load(context, track, sizePx)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 单首失败不影响后续预取，滚入时仍会重试
        }
    }

    /** 大封面：优先内嵌原图并按长边收口，回退系统略缩图 */
    suspend fun loadLarge(context: Context, track: MusicTrack, maxEdgePx: Int): Bitmap? {
        if (CoverSkipRegistry.isSkipped(track)) return null
        EmbeddedCoverReader.readFitted(context, track.audioUri, track.path, maxEdgePx)?.let { return it }
        return load(context, track, SYSTEM_THUMBNAIL_MAX_SIZE_PX)
    }

    private suspend fun decode(context: Context, track: MusicTrack, sizePx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            if (track.isMediaStoreIndexed) {
                // 首选系统略缩图，随媒体扫描重建
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
            // 一级兜底：回退内嵌原图并按请求尺寸缩放
            EmbeddedCoverCache.cover(context, track, sizePx)
        }

    // 系统可能返回大于请求的图，统一收口到请求尺寸
    private fun fitToSize(bitmap: Bitmap, sizePx: Int): Bitmap {
        val software = toSoftware(bitmap)
        // 转换出副本后释放硬件位图，避免显存滞留
        if (software !== bitmap) bitmap.recycle()
        return if (max(software.width, software.height) <= sizePx) software
        else runCatching { scaleDown(software, sizePx) }.getOrDefault(software)
    }

    // 硬件位图会让主题快照绘制失败，统一转软件位图
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
        // 缩放产出新位图，立即释放原图
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }
}
