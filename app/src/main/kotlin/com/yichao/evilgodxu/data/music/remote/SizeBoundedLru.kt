package com.yichao.evilgodxu.data.music.remote

/**
 * 按占用字节淘汰的 LRU：容量不足时从最久未读的一项开始丢弃。
 *
 * 被淘汰的值只解除引用、不主动回收：图像可能仍被上一帧持有，回收会让绘制读到已释放的位图，
 * 释放交由 GC 决定。
 *
 * 值类型做成泛型是为了可测：容量核算与淘汰顺序都是纯逻辑，单测用字符串当值即可覆盖，
 * 不必构造位图。
 */
internal class SizeBoundedLru<K, V>(
    private val maxBytes: Int,
    private val sizeOf: (V) -> Int,
) {

    // accessOrder 为真：读取即刷新最近使用顺序，迭代顺序因此就是「最久未读 → 最近读过」
    private val entries = LinkedHashMap<K, V>(0, LOAD_FACTOR, true)

    var sizeBytes: Int = 0
        private set

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        val size = sizeOf(value)
        // 单张超过总容量：收下它只会把其余全部挤掉，下一次还得重取，不如不驻留
        if (size > maxBytes) return
        entries.put(key, value)?.let { sizeBytes -= sizeOf(it) }
        sizeBytes += size
        trimToSize()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        sizeBytes = 0
    }

    private fun trimToSize() {
        if (sizeBytes <= maxBytes) return
        val iterator = entries.entries.iterator()
        while (sizeBytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            sizeBytes -= sizeOf(entry.value)
            iterator.remove()
        }
    }

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}
