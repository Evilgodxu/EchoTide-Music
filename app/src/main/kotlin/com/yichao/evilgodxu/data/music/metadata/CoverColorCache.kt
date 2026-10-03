package com.yichao.evilgodxu.data.music.metadata

import android.util.LruCache

/**
 * 背景渲染取色的内存缓存：沉浸背景按曲目取用封面边缘的三段色。
 *
 * 取色计算本身很便宜，昂贵的是它依赖的那张图：显示端原先要等背景自己的 64px 略缩图解码完才能取色，
 * 这段等待里封面已经换成新曲目、背景却还停在上一首的色调。切歌预取高清封面时顺手把相邻几首的取色
 * 一并算出记在这里（见 [LargeCoverStore.prefetch]），显示端切歌首帧即可同步取到新曲目的色调。
 *
 * 与封面缓存同口径，只驻留最近几首；封面被重写后由播放状态在自增封面版本时清空。
 */
internal object CoverColorCache {

    // 驻留条数：与 LargeCoverStore 驻留的封面同一批（当前曲及其前后各一首）
    private const val RESIDENT_CAPACITY = 3

    private val resident = object : LruCache<String, CoverBackgroundColors>(RESIDENT_CAPACITY) {}

    /** 取已算好的背景取色；未命中返回 null，由显示端自行从略缩图取色 */
    fun get(audioUri: String): CoverBackgroundColors? = resident.get(audioUri)

    /** 回填取色结果 */
    fun put(audioUri: String, colors: CoverBackgroundColors) {
        resident.put(audioUri, colors)
    }

    /** 作废全部结果：封面被重写后旧色调不再成立 */
    fun clear() {
        resident.evictAll()
    }
}
