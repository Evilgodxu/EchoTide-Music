package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult

/**
 * 缓存落地前的详情补全：用平台详情接口补齐搜索响应里没有的标签字段。
 *
 * 只补标签字段，不换曲目身份与播放地址：详情响应里的封面、时长、歌手可能与该条目在列表中的取值不同，
 * 一旦替换，缓存文件名与用户选中的那一项就会错位。任一环节失败都原样返回入参 ——
 * 缓存必须能落盘，标签属于附加项。
 */
internal suspend fun enrichTrackDetail(result: NeteaseSongSearchResult): NeteaseSongSearchResult {
    val detail = runCatching {
        when (result.source) {
            MusicSearchSource.NETEASE -> NeteaseMusicApi.songDetail(result.id)
            MusicSearchSource.QQ -> QQMusicApi.songDetail(result.sourceId.orEmpty())
            // 酷狗与酷我的搜索响应已含可用标签，且没有稳定的详情接口，不额外请求
            else -> null
        }
    }.getOrNull() ?: return result
    return mergeTagFields(result, detail)
}

/** 详情字段逐项回退到搜索结果的取值：详情缺哪项就用搜索结果的那项，两者都缺则为 null */
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
