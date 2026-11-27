package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult

// 在线音乐源搜索接口，新增内置平台需实现并注册
interface OnlineMusicSource {
    suspend fun search(keyword: String, page: Int, pageSize: Int): List<NeteaseSongSearchResult>
}

// 平台键映射内置实现，null 表示由代理音源承担
internal fun builtInSourceOf(source: MusicSearchSource): OnlineMusicSource? = when (source) {
    MusicSearchSource.NETEASE -> NeteaseMusicApi
    MusicSearchSource.QQ -> QQMusicApi
    MusicSearchSource.KUGOU -> KugouMusicApi
    MusicSearchSource.KUWO -> KuwoMusicApi
    else -> null
}
