package com.yichao.evilgodxu.data.music.metadata

import android.graphics.Bitmap
import android.util.LruCache

// 略缩图内存缓存，不落盘；键含尺寸，封面重写后需清空
internal object SystemThumbnailCache {

    private data class Key(val audioUri: String, val sizePx: Int)

    private val cache = object : LruCache<Key, Bitmap>(coverThumbnailCacheBytes(Runtime.getRuntime().maxMemory())) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
    }

    /** 取已解码略缩图，未命中返回 null */
    fun get(audioUri: String, sizePx: Int): Bitmap? = cache.get(Key(audioUri, sizePx))

    /** 回填解码结果 */
    fun put(audioUri: String, sizePx: Int, bitmap: Bitmap) {
        cache.put(Key(audioUri, sizePx), bitmap)
    }

    /** 换 URI 时把略缩图改指新 URI，避免闪占位符 */
    fun remap(fromUri: String, toUri: String) {
        for ((key, bitmap) in cache.snapshot().filterKeys { it.audioUri == fromUri }) {
            cache.remove(key)
            cache.put(Key(toUri, key.sizePx), bitmap)
        }
    }

    /** 清空全部驻留结果 */
    fun clear() {
        cache.evictAll()
    }
}

// 堆预算先扣 300MB 瞬时峰值，余量才归封面缓存
private const val COVER_CACHE_HEAP_RESERVE_BYTES = 300 * 1024 * 1024

// 驻留下限 32MB，低内存机型用
private const val COVER_CACHE_MIN_BYTES = 32 * 1024 * 1024

// 驻留上限 128MB，图片类常驻不能随堆无限放大
private const val COVER_CACHE_MAX_BYTES = 128 * 1024 * 1024

/** 缓存容量：堆扣 300MB 后夹在 32–128MB */
internal fun coverThumbnailCacheBytes(maxHeapBytes: Long): Int =
    (maxHeapBytes - COVER_CACHE_HEAP_RESERVE_BYTES)
        .coerceIn(COVER_CACHE_MIN_BYTES.toLong(), COVER_CACHE_MAX_BYTES.toLong())
        .toInt()
