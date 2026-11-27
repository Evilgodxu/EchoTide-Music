package com.yichao.evilgodxu.data.music.metadata

import com.yichao.evilgodxu.data.music.model.MusicTrack

/** 无封面跳过标记：键含文件修改时间，重扫后自动失效 */
internal object CoverSkipRegistry {

    // 驻留上限，按最久未用淘汰
    private const val MAX_ENTRIES = 4_096

    // 访问序 LRU，纯标准库实现
    private val skipped = object : LinkedHashMap<Key, Boolean>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Boolean>): Boolean =
            size > MAX_ENTRIES
    }

    private data class Key(val audioUri: String, val contentVersion: Long)

    /** 是否已判定无封面 */
    @Synchronized
    fun isSkipped(track: MusicTrack): Boolean = skipped.containsKey(keyOf(track))

    /** 记入跳过：本次所有来源均失败 */
    @Synchronized
    fun markSkipped(track: MusicTrack) {
        skipped[keyOf(track)] = true
    }

    /** 作废全部标记 */
    @Synchronized
    fun clear() {
        skipped.clear()
    }

    private fun keyOf(track: MusicTrack): Key = Key(track.audioUri, track.fileModifiedMs)
}
