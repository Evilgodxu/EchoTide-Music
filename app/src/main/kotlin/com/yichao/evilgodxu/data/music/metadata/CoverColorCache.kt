package com.yichao.evilgodxu.data.music.metadata

import android.util.LruCache

/** 背景取色内存缓存，只驻留最近几首 */
internal object CoverColorCache {

    // 驻留 3 条，与 LargeCoverStore 同批
    private const val RESIDENT_CAPACITY = 3

    private val resident = object : LruCache<String, CoverBackgroundColors>(RESIDENT_CAPACITY) {}

    /** 取已算好的取色，未命中为 null */
    fun get(audioUri: String): CoverBackgroundColors? = resident.get(audioUri)

    /** 回填取色结果 */
    fun put(audioUri: String, colors: CoverBackgroundColors) {
        resident.put(audioUri, colors)
    }

    /** 清空全部结果 */
    fun clear() {
        resident.evictAll()
    }
}
