package com.yichao.evilgodxu.data.music.api

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

// 统一网络栈：各在线音乐源与更新检查共用同一 OkHttpClient，复用连接池
internal object MusicHttpClient {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // 音乐平台接口共用的浏览器 UA
    const val MUSIC_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
