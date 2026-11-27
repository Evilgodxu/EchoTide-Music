package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult

// 按候选平台取内置歌词接口的歌词
// 逐字优先由各平台自理，代理优先、内置兜底由调用方决定
// 自定义平台无内置接口，返回空列表
internal suspend fun fetchPlatformLyrics(result: NeteaseSongSearchResult): List<LyricLine> =
    when (result.source) {
        MusicSearchSource.NETEASE -> NeteaseMusicApi.lyric(result.id).lines
        MusicSearchSource.QQ -> QQMusicApi.lyricLines(result).orEmpty()
        MusicSearchSource.KUGOU -> KugouMusicApi.lyricLines(result).orEmpty()
        MusicSearchSource.KUWO -> KuwoMusicApi.lyricLines(result).orEmpty()
        else -> emptyList()
    }
