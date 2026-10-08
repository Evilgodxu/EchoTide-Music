package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult

/**
 * 按候选所属平台取内置歌词接口的歌词。
 *
 * 逐字歌词优先的策略由各平台实现自行承担（取不到逐字时回退逐行），调用方无需区分，
 * 只需按「代理音源优先、内置接口兜底」的约定调一次：
 * `ProxySourceEngine.lyricLines(...) ?: fetchPlatformLyrics(...)`。
 * 自定义平台没有内置歌词接口，返回空列表。
 */
internal suspend fun fetchPlatformLyrics(result: NeteaseSongSearchResult): List<LyricLine> =
    when (result.source) {
        MusicSearchSource.NETEASE -> NeteaseMusicApi.lyric(result.id).lines
        MusicSearchSource.QQ -> QQMusicApi.lyricLines(result).orEmpty()
        MusicSearchSource.KUGOU -> KugouMusicApi.lyricLines(result).orEmpty()
        MusicSearchSource.KUWO -> KuwoMusicApi.lyricLines(result).orEmpty()
        else -> emptyList()
    }
