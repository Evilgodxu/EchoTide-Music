package com.yichao.evilgodxu.data.music.metadata

import android.graphics.Bitmap
import android.util.LruCache

// 索引曲目的系统略缩图内存缓存：封面显示每次滚入视口都会重新执行媒体库查询与解码，
// 以内存换重复读取。只驻留内存，进程结束即失效，不产生任何落盘产出。
// 尺寸参与缓存键：解码输出随请求尺寸变化（与 EmbeddedCoverCache 一致），不同尺寸的结果不可互相顶替。
// 封面重写后同一 URI 的略缩图已更新，由 bumpCoverRevision 清空本缓存。
internal object SystemThumbnailCache {

    // 驻留上限：按解码后位图的真实内存占用计，与 EmbeddedCoverCache 保持一致
    private const val MAX_BYTES = 12 * 1024 * 1024

    private data class Key(val audioUri: String, val sizePx: Int)

    private val cache = object : LruCache<Key, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
    }

    /** 取已解码的略缩图；未命中返回 null，由调用方解码后回填 */
    fun get(audioUri: String, sizePx: Int): Bitmap? = cache.get(Key(audioUri, sizePx))

    /** 回填解码结果 */
    fun put(audioUri: String, sizePx: Int, bitmap: Bitmap) {
        cache.put(Key(audioUri, sizePx), bitmap)
    }

    /** 作废全部驻留结果：封面被重写后，旧位图不再成立 */
    fun clear() {
        cache.evictAll()
    }
}
