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

// 非索引曲目封面内存缓存，不落盘；键含尺寸
internal object EmbeddedCoverCache {

    // 驻留上限按位图真实内存计
    private const val MAX_BYTES = 12 * 1024 * 1024

    // 「无封面」记录按固定小值计入预算
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

    // 同键并发只解码一次
    private val lock = Mutex()
    private val inflight = mutableMapOf<Key, Deferred<EmbeddedCoverReader.Result>>()
    // 在飞读取不随调用方取消
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 同步取已缓存封面，未命中 null */
    fun peek(trackId: Long, sizePx: Int): Bitmap? =
        (cache.get(Key(trackId, sizePx)) as? Entry.Cover)?.bitmap

    /** 取内嵌封面；无封面或失败返回 null */
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
                    // 仅「确实无图」记否定结论；读取失败不入缓存
                    if (result is EmbeddedCoverReader.Result.Found) Entry.Cover(result.bitmap) else Entry.NoCover,
                )
            }
            (result as? EmbeddedCoverReader.Result.Found)?.bitmap
        } catch (e: CancellationException) {
            // 发起方取消时撤掉在飞读取
            if (owner) lock.withLock { inflight.remove(key)?.cancel() }
            throw e
        }
    }

    /** 作废全部驻留结果 */
    fun clear() {
        cache.evictAll()
    }
}
