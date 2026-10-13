package com.yichao.evilgodxu.data.music.metadata

import android.graphics.Bitmap
import android.util.LruCache

// 索引曲目的系统略缩图内存缓存：封面显示每次滚入视口都会重新执行媒体库查询与解码，
// 以内存换重复读取。本缓存只驻留内存，进程结束即失效，不产生任何落盘产出
// （当前曲目的一张缩略图由 CurrentCoverCache 另行落盘）。
// 尺寸参与缓存键：解码输出随请求尺寸变化（与 EmbeddedCoverCache 一致），不同尺寸的结果不可互相顶替。
// 封面重写后同一 URI 的略缩图已更新，由 bumpCoverRevision 清空本缓存。
internal object SystemThumbnailCache {

    private data class Key(val audioUri: String, val sizePx: Int)

    private val cache = object : LruCache<Key, Bitmap>(coverThumbnailCacheBytes(Runtime.getRuntime().maxMemory())) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.allocationByteCount
    }

    /** 取已解码的略缩图；未命中返回 null，由调用方解码后回填 */
    fun get(audioUri: String, sizePx: Int): Bitmap? = cache.get(Key(audioUri, sizePx))

    /** 回填解码结果 */
    fun put(audioUri: String, sizePx: Int, bitmap: Bitmap) {
        cache.put(Key(audioUri, sizePx), bitmap)
    }

    /** 无损升级替换音频文件时 URI 变化但封面不变：把已驻留的略缩图改指到新 URI，
     *  使新文件系统略缩图就绪前显示端仍承接同一张封面，不闪占位符。 */
    fun remap(fromUri: String, toUri: String) {
        for ((key, bitmap) in cache.snapshot().filterKeys { it.audioUri == fromUri }) {
            cache.remove(key)
            cache.put(Key(toUri, key.sizePx), bitmap)
        }
    }

    /** 作废全部驻留结果：封面被重写后，旧位图不再成立 */
    fun clear() {
        cache.evictAll()
    }
}

// 预算口径：可用堆先扣掉封面之外的那一段瞬时峰值，余下的才归封面缓存。
// 这段峰值由大封面还原内嵌原图（长边 2048）、音频内嵌封面解码与 ExoPlayer 的高解析度缓冲构成，
// 实测在 200 MB 以内，取 300 MB 是给机型与曲目差异留的余量。
// 它不随堆大小等比放大，故不按堆的比例划分 —— 比例在高堆机上给不出合适的上界，
// 在低堆机型上又会把本该归缓存的额度压掉
private const val COVER_CACHE_HEAP_RESERVE_BYTES = 300 * 1024 * 1024

// 驻留下限：改造前的固定值。堆扣完余量后不足这里（低内存机型）仍按下限给，不因换口径而变小；
// 列表行封面按显示尺寸取图后单张约 16–64 KB
private const val COVER_CACHE_MIN_BYTES = 32 * 1024 * 1024

// 驻留上限：再高的堆也只用到这里。封面缓存之外还有 Coil 的图片缓存（见 App 的 0.10）、
// 大封面常驻档与音频内嵌封面缓存，图片类常驻不能无限跟着堆放大
private const val COVER_CACHE_MAX_BYTES = 128 * 1024 * 1024

/**
 * 索引曲目略缩图内存缓存的驻留上限（字节）：可用堆 [maxHeapBytes] 扣除 [COVER_CACHE_HEAP_RESERVE_BYTES]
 * 后的余量即为本缓存的额度，再夹在上下限之间。
 * 抽成纯函数以便单测覆盖边界（低内存机型落到下限、高内存机型落到上限）。
 *
 * 返回 Int 而非 Long：消费方 [android.util.LruCache] 的容量就是 Int，
 * 而上限（[COVER_CACHE_MAX_BYTES]）远在 Int 量程之内，不必让调用方各做一次窄化转换。
 */
internal fun coverThumbnailCacheBytes(maxHeapBytes: Long): Int =
    (maxHeapBytes - COVER_CACHE_HEAP_RESERVE_BYTES)
        .coerceIn(COVER_CACHE_MIN_BYTES.toLong(), COVER_CACHE_MAX_BYTES.toLong())
        .toInt()
