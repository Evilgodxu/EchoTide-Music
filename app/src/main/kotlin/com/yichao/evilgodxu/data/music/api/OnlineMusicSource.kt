package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult

// 在线音乐源统一搜索接口，新增内置平台时实现该接口并加入 builtInSourceOf 映射
interface OnlineMusicSource {
    suspend fun search(keyword: String, page: Int, pageSize: Int): List<NeteaseSongSearchResult>

    /**
     * 内置榜单解析：取该平台默认榜单的前 [limit] 首歌曲。
     *
     * 榜单是推荐算法的新歌候选来源 —— 搜索结果取决于用户输入，无法产生用户未曾想到的歌，
     * 榜单则提供与用户历史无关的当期热门，两者在召回上的作用不可互换。
     * 榜单不可用（接口变更、风控、平台未提供）时返回空列表，由调用方按平台维度容忍缺失。
     *
     * 调用方不应在每次生成推荐时直接调用本方法：候选池按日换期，由它统一做刷新与落盘。
     */
    suspend fun chart(limit: Int): List<NeteaseSongSearchResult>
}

// 按平台键映射到内置实现，仅有内置平台有对应实现；返回 null 表示该平台只能由代理音源承担
internal fun builtInSourceOf(source: MusicSearchSource): OnlineMusicSource? = when (source) {
    MusicSearchSource.NETEASE -> NeteaseMusicApi
    MusicSearchSource.QQ -> QQMusicApi
    MusicSearchSource.KUGOU -> KugouMusicApi
    MusicSearchSource.KUWO -> KuwoMusicApi
    MusicSearchSource.MIGU -> MiguMusicApi
    else -> null
}
