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

// 驻留比例：封面缓存该占多大取决于设备给了应用多少堆，2GB 与 12GB 机型相差数倍，
// 写死字节数必然在低内存机型上偏大、在高内存机型上偏小。口径对齐 Coil 的 MemoryCache.maxSizePercent ——
// 按可用堆取比例，再夹在上下限之间
private const val COVER_CACHE_SIZE_PERCENT = 0.10

// 驻留下限：改造前的固定值。列表行封面按显示尺寸取图后约 16–36KB，该预算可容纳约 900 张，
// 覆盖常见的一屏来回滚动；低内存机型不再因此变小
private const val COVER_CACHE_MIN_BYTES = 32 * 1024 * 1024

// 驻留上限：再高的堆也只用到这里。本应用同时驻留 ExoPlayer 的高解析度音频缓冲与解码峰值，
// 封面缓存与 Coil 自身的图片缓存（见 App 的 0.10）合计不超过可用堆的两成，留出余量
private const val COVER_CACHE_MAX_BYTES = 64 * 1024 * 1024

/**
 * 索引曲目略缩图内存缓存的驻留上限（字节）：按应用可用堆 [maxHeapBytes] 的比例计算，
 * 并夹在上下限之间。抽成纯函数以便单测覆盖边界（低内存机型落到下限、高内存机型落到上限）。
 *
 * 返回 Int 而非 Long：消费方 [android.util.LruCache] 的容量就是 Int，
 * 而上限（[COVER_CACHE_MAX_BYTES]）远在 Int 量程之内，不必让调用方各做一次窄化转换。
 */
internal fun coverThumbnailCacheBytes(maxHeapBytes: Long): Int =
    (maxHeapBytes * COVER_CACHE_SIZE_PERCENT).toLong()
        .coerceIn(COVER_CACHE_MIN_BYTES.toLong(), COVER_CACHE_MAX_BYTES.toLong())
        .toInt()
