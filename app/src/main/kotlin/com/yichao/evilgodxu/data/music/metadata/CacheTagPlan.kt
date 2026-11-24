package com.yichao.evilgodxu.data.music.metadata

import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult

/**
 * 缓存文件落地时要写入的标签集合。
 *
 * 平台能给多少写多少，缺项不参与本次写入：`TrackTagFields` 里「缺失」表示保留文件既有值，
 * 因此拿不到的字段不会把已有标签清空。标题与艺术家以播放列表中的曲目为准 ——
 * 它是用户当前看到的那一项，与缓存文件名同源。
 */
internal fun cachedTagFields(
    track: MusicTrack,
    result: NeteaseSongSearchResult,
    lyrics: String?,
): TrackTagFields = TrackTagFields.build {
    put(TagField.TITLE, track.title.ifBlank { result.title })
    put(TagField.ARTIST, track.artist.ifBlank { result.artist })
    put(TagField.ALBUM, result.album.nonBlankOr(track.albumName))
    put(TagField.DATE, result.releaseDate.nonBlank())
    put(TagField.TRACK_NUMBER, result.trackNumber?.toString())
    put(TagField.DISC_NUMBER, result.discNumber?.toString())
    put(TagField.COPYRIGHT, result.copyright.nonBlank())
    // 平台自带响度数据时一并写入，免去用户事后逐首自算
    put(TagField.REPLAY_GAIN_TRACK_GAIN, result.replayGainTrackGainDb?.let(::formatReplayGainDb))
    put(TagField.REPLAY_GAIN_TRACK_PEAK, result.replayGainTrackPeak?.let(::formatReplayGainPeak))
    put(TagField.LYRICS, lyrics.nonBlank())
}

private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

private fun String?.nonBlankOr(fallback: String): String? =
    this?.takeIf { it.isNotBlank() } ?: fallback.takeIf { it.isNotBlank() }
