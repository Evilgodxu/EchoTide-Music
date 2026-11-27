package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult

// 缓存落地前用详情接口补齐标签字段
// 不换曲目身份与播放地址，避免缓存文件名错位；失败原样返回
internal suspend fun enrichTrackDetail(result: NeteaseSongSearchResult): NeteaseSongSearchResult {
    val detail = runCatching {
        when (result.source) {
            MusicSearchSource.NETEASE -> NeteaseMusicApi.songDetail(result.id)
            MusicSearchSource.QQ -> QQMusicApi.songDetail(result.sourceId.orEmpty())
            // 酷狗/酷我搜索已含标签，无稳定详情接口，不请求
            else -> null
        }
    }.getOrNull() ?: return result
    return mergeTagFields(result, detail)
}

// 详情字段逐项回退到搜索结果，两者都缺为 null
internal fun mergeTagFields(
    base: NeteaseSongSearchResult,
    detail: NeteaseSongSearchResult,
): NeteaseSongSearchResult = base.copy(
    album = detail.album ?: base.album,
    releaseDate = detail.releaseDate ?: base.releaseDate,
    trackNumber = detail.trackNumber ?: base.trackNumber,
    discNumber = detail.discNumber ?: base.discNumber,
    copyright = detail.copyright ?: base.copyright,
    replayGainTrackGainDb = detail.replayGainTrackGainDb ?: base.replayGainTrackGainDb,
    replayGainTrackPeak = detail.replayGainTrackPeak ?: base.replayGainTrackPeak,
)
