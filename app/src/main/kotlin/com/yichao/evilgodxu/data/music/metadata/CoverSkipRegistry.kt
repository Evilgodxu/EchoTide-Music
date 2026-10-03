package com.yichao.evilgodxu.data.music.metadata

import com.yichao.evilgodxu.data.music.model.MusicTrack

/**
 * 封面取图失败的「跳过」标记：某首曲目在所有取图来源都取不到封面、最终只能显示占位图时记入本表。
 *
 * 列表滚动时同一个曲目会被反复请求封面，没有这层标记就会对同一首无封面的曲目反复查询系统略缩图、
 * 反复解析音频文件，形成无效开销与掉帧。命中标记的曲目直接按「无封面」返回。
 *
 * 标记键含曲目的内容版本（[MusicTrack.fileModifiedMs]）：文件被外部修改、重扫或替换后版本变化，
 * 标记自然失效并重新走一遍取图流程，无需额外的解除通知。应用内重写封面（不必然改变扫描到的版本）
 * 由播放状态在自增封面版本时统一清空本表。
 */
internal object CoverSkipRegistry {

    // 驻留上限：只用于挡住「当前正在滚动的一批曲目」的重复请求，超出后按最久未用淘汰
    private const val MAX_ENTRIES = 4_096

    // 访问序 LRU：纯标准库实现，标记与判定都不依赖 Android 运行时
    private val skipped = object : LinkedHashMap<Key, Boolean>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Boolean>): Boolean =
            size > MAX_ENTRIES
    }

    private data class Key(val audioUri: String, val contentVersion: Long)

    /** 该曲目是否已被判定为无封面可取 */
    @Synchronized
    fun isSkipped(track: MusicTrack): Boolean = skipped.containsKey(keyOf(track))

    /** 记入「跳过」：该曲目本次所有取图来源均失败 */
    @Synchronized
    fun markSkipped(track: MusicTrack) {
        skipped[keyOf(track)] = true
    }

    /** 作废全部标记：封面被重写后旧结论不再成立 */
    @Synchronized
    fun clear() {
        skipped.clear()
    }

    private fun keyOf(track: MusicTrack): Key = Key(track.audioUri, track.fileModifiedMs)
}
