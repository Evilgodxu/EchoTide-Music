package com.yichao.evilgodxu.data.music.remote

// 按占用字节淘汰的 LRU，不足时丢最久未读项
// 被淘汰值只解引用不回收，交给 GC
// 值泛型化以便用字符串单测容量逻辑
internal class SizeBoundedLru<K, V>(
    private val maxBytes: Int,
    private val sizeOf: (V) -> Int,
) {

    // accessOrder 为真，迭代顺序即最久未读到最近
    private val entries = LinkedHashMap<K, V>(0, LOAD_FACTOR, true)

    var sizeBytes: Int = 0
        private set

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        val size = sizeOf(value)
        // 单张超总容量则不驻留，避免挤掉其余
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
