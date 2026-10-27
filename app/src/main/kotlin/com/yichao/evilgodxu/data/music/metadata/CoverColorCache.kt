package com.yichao.evilgodxu.data.music.metadata

import android.util.LruCache

/**
 * 背景渲染取色的内存缓存：沉浸背景按曲目取用封面边缘的三段色。
 *
 * 取色本身便宜，昂贵的是它依赖的图：等背景自己的 64px 略缩图解码完再取色，封面已换成新曲目、
 * 背景却还停在上一首的色调。故切歌预取高清封面时顺手把相邻几首的取色一并算出记在这里
 * （见 [LargeCoverStore.prefetch]），冷启动则由已持久化的结果回填（见 MusicPlaybackState 的
 * savedBackgroundColors），背景帧与底色同取这一份。
 *
 * 与封面缓存同口径只驻留最近几首；封面被重写后由播放状态在自增封面版本时清空。
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
