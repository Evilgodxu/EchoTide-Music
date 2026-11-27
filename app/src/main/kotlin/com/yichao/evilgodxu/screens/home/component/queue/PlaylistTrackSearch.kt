package com.yichao.evilgodxu.screens.home.component.queue

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack

/** 播放列表搜索命中项；副标题仅歌词命中时非空 */
internal data class PlaylistSearchHit(
    val index: Int,
    val lyricSnippet: String?,
)

/**
 * 搜索播放列表：直击优先，歌词按行数降序；空词全量。
 */
internal fun searchPlaylistTracks(
    tracks: List<MusicTrack>,
    query: String,
): List<PlaylistSearchHit> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return tracks.indices.map { PlaylistSearchHit(it, lyricSnippet = null) }
    val directHits = mutableListOf<PlaylistSearchHit>()
    val lyricHits = mutableListOf<Pair<Int, LyricHit>>()
    tracks.forEachIndexed { index, track ->
        if (track.title.contains(keyword, ignoreCase = true) ||
            track.artist.contains(keyword, ignoreCase = true)
        ) {
            directHits += PlaylistSearchHit(index, lyricSnippet = null)
            return@forEachIndexed
        }
        val hit = matchTrackLyrics(track.lyricLines, keyword)
        if (hit != null) lyricHits += index to hit
    }
    return directHits + lyricHits
        .sortedByDescending { (_, hit) -> hit.matchCount }
        .map { (index, hit) -> PlaylistSearchHit(index, hit.snippet) }
}

// 歌词命中：片段取首个命中行，行数作相关度
private class LyricHit(val snippet: String, val matchCount: Int)

// 查原文与翻译；仅翻译命中时取翻译，空行不计
private fun matchTrackLyrics(lines: List<LyricLine>, keyword: String): LyricHit? {
    var matchCount = 0
    var snippet: String? = null
    lines.forEach { line ->
        val textMatched = line.text.contains(keyword, ignoreCase = true)
        val translation = line.translation
        val translationMatched = translation?.contains(keyword, ignoreCase = true) == true
        if (textMatched || translationMatched) {
            matchCount++
            if (snippet == null) {
                snippet = (if (textMatched) line.text else translation.orEmpty()).trim().ifEmpty { null }
            }
        }
    }
    val firstLine = snippet ?: return null
    return LyricHit(firstLine, matchCount)
}