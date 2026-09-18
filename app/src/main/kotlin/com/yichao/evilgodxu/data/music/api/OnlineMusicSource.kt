package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.R

// 在线音乐源统一搜索接口，新增平台时实现该接口并加入 sourceOf 映射
interface OnlineMusicSource {
    suspend fun search(keyword: String, page: Int, pageSize: Int): List<NeteaseSongSearchResult>

    /**
     * 内置榜单解析：取该平台默认榜单的前 [limit] 首歌曲。
     *
     * 榜单是推荐算法的新歌候选来源 —— 搜索结果取决于用户输入，无法产生用户未曾想到的歌，
     * 榜单则提供与用户历史无关的当期热门，两者在召回上的作用不可互换。
     * 榜单不可用（接口变更、风控、平台未提供）时返回空列表，由调用方按平台维度容忍缺失。
     *
     * 调用方不应在每次生成推荐时直接调用本方法：榜单按周更新，由候选池统一做周期刷新与落盘。
     */
    suspend fun chart(limit: Int): List<NeteaseSongSearchResult>
}

// 按平台枚举映射到对应实现，供单平台搜索使用
internal fun sourceOf(type: MusicSearchSource): OnlineMusicSource = when (type) {
    MusicSearchSource.NETEASE -> NeteaseMusicApi
    MusicSearchSource.QQ -> QQMusicApi
    MusicSearchSource.KUGOU -> KugouMusicApi
    MusicSearchSource.KUWO -> KuwoMusicApi
    MusicSearchSource.MIGU -> MiguMusicApi
}

// 平台显示名：候选无封面时占位及来源标签使用
internal fun MusicSearchSource.sourceNameRes(): Int = when (this) {
    MusicSearchSource.NETEASE -> R.string.music_panel_search_source
    MusicSearchSource.QQ -> R.string.music_panel_search_source_qq
    MusicSearchSource.KUGOU -> R.string.music_panel_search_source_kugou
    MusicSearchSource.KUWO -> R.string.music_panel_search_source_kuwo
    MusicSearchSource.MIGU -> R.string.music_panel_search_source_migu
}
