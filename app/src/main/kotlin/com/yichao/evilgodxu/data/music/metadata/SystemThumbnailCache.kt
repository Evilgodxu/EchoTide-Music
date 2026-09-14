package com.yichao.evilgodxu.data.music.metadata

import android.graphics.Bitmap
import android.util.LruCache

// 索引曲目的系统略缩图内存缓存：封面显示每次滚入视口都会重新执行媒体库查询与解码，
// 以内存换重复读取。只驻留内存，进程结束即失效，不产生任何落盘产出。
// 尺寸参与缓存键：解码输出随请求尺寸变化（与 EmbeddedCoverCache 一致），不同尺寸的结果不可互相顶替。
// 封面重写后同一 URI 的略缩图已更新，由 bumpCoverRevision 清空本缓存。
internal object SystemThumbnailCache {

    // 驻留上限：按解码后位图的真实内存占用计，与 EmbeddedCoverCache 保持一致。
    // 进出页面时列表与其详情页会连续驻留多批略缩图：上限需同时容纳可见列表及展开的详情，
    // 否则详情解码会顶掉刚展示的列表封面，返回时重新解码造成闪烁。256px 位图约 256KB，64MB 约 250 张。
    private const val MAX_BYTES = 64 * 1024 * 1024

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
