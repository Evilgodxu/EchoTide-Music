package com.yichao.evilgodxu.data.music.metadata

import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult

/** 缓存写入的标签；缺项保留原值，标题以列表为准 */
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
    // 平台有响度则一并写入
    put(TagField.REPLAY_GAIN_TRACK_GAIN, result.replayGainTrackGainDb?.let(::formatReplayGainDb))
    put(TagField.REPLAY_GAIN_TRACK_PEAK, result.replayGainTrackPeak?.let(::formatReplayGainPeak))
    put(TagField.LYRICS, lyrics.nonBlank())
}

private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

private fun String?.nonBlankOr(fallback: String): String? =
    this?.takeIf { it.isNotBlank() } ?: fallback.takeIf { it.isNotBlank() }
