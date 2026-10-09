package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult

// 在线音乐源统一搜索接口，新增内置平台时实现该接口并加入 builtInSourceOf 映射
interface OnlineMusicSource {
    suspend fun search(keyword: String, page: Int, pageSize: Int): List<NeteaseSongSearchResult>
}

// 按平台键映射到内置实现，仅有内置平台有对应实现；返回 null 表示该平台只能由代理音源承担
internal fun builtInSourceOf(source: MusicSearchSource): OnlineMusicSource? = when (source) {
    MusicSearchSource.NETEASE -> NeteaseMusicApi
    MusicSearchSource.QQ -> QQMusicApi
    MusicSearchSource.KUGOU -> KugouMusicApi
    MusicSearchSource.KUWO -> KuwoMusicApi
    else -> null
}
