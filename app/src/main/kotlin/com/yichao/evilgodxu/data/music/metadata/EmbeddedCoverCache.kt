package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// 非索引曲目的封面临时缓存：只驻留内存，进程结束即失效，不产生任何落盘产出。
// 这类曲目没有系统略缩图可供读取（见 rememberSystemThumbnail），显示端每次重新进入视口都要重读音频文件并解码，
// 同一首歌还会按列表行与音乐面板两种尺寸各解一次，故以内存换重复读取。
// 尺寸参与缓存键：解码行为对齐系统略缩图（整数级降采样，输出随请求尺寸变化），不同尺寸的结果不可互相顶替
internal object EmbeddedCoverCache {

    // 驻留上限：按解码后位图的真实内存占用计，不随可用内存推算。
    // AOSP 等价采样可能输出大于请求尺寸的位图，单张偏大时由该上限整体收口
    private const val MAX_BYTES = 12 * 1024 * 1024

    // 「无内嵌封面」结论的记账值：这类记录按固定小值计入同一预算，
    // 使其数量同样受上限约束，不会被逐曲累积成无界表
    private const val NO_COVER_COST_BYTES = 1024

    private sealed interface Entry {
        class Cover(val bitmap: Bitmap) : Entry
        data object NoCover : Entry
    }

    private data class Key(val trackId: Long, val sizePx: Int)

    private val cache = object : LruCache<Key, Entry>(MAX_BYTES) {
        override fun sizeOf(key: Key, value: Entry): Int = when (value) {
            is Entry.Cover -> value.bitmap.allocationByteCount
            Entry.NoCover -> NO_COVER_COST_BYTES
        }
    }

    // 同键并发读取只解码一次（列表与面板可能同时请求同一首歌）
    private val lock = Mutex()
    private val inflight = mutableMapOf<Key, Deferred<EmbeddedCoverReader.Result>>()
    // 在飞读取不随任一调用方取消：同一结果可能正被多个等待者共享
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 取曲目的内嵌封面（按最长边 [sizePx] 请求解码）；确无内嵌封面或读取失败时返回 null，由调用方显示占位符 */
    suspend fun cover(context: Context, track: MusicTrack, sizePx: Int): Bitmap? {
        val key = Key(track.id, sizePx)
        lock.withLock {
            when (val cached = cache.get(key)) {
                is Entry.Cover -> return cached.bitmap
                Entry.NoCover -> return null
                null -> Unit
            }
        }
        val (pending, owner) = lock.withLock {
            inflight[key]?.let { return@withLock it to false }
            scope.async { EmbeddedCoverReader.read(context, track.audioUri, track.path, sizePx) }
                .also { inflight[key] = it } to true
        }
        return try {
            val result = pending.await()
            lock.withLock {
                inflight.remove(key)
                cache.put(
                    key,
                    // 只有「文件正常且确实没有内嵌图片」值得记为否定结论；读取失败是暂时状态，不入缓存
                    if (result is EmbeddedCoverReader.Result.Found) Entry.Cover(result.bitmap) else Entry.NoCover,
                )
            }
            (result as? EmbeddedCoverReader.Result.Found)?.bitmap
        } catch (e: CancellationException) {
            // 发起方被取消时撤掉在飞读取，避免条目永久滞留在表中；仅等待的调用方直接退出等待
            if (owner) lock.withLock { inflight.remove(key)?.cancel() }
            throw e
        }
    }

    /** 作废全部驻留结果：封面被重写后，旧位图与「无封面」的旧结论都不再成立 */
    fun clear() {
        cache.evictAll()
    }
}
