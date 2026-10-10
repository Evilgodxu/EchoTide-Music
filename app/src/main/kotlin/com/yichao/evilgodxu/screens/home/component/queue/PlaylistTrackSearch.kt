package com.yichao.evilgodxu.screens.home.component.queue

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack

/**
 * 播放列表搜索命中项。
 *
 * [lyricSnippet] 仅在标题/歌手均未命中、由歌词命中时非空：
 * 此时列表副标题改显示该片段，让用户看出这首歌因哪句歌词被搜到。
 */
internal data class PlaylistSearchHit(
    val index: Int,
    val lyricSnippet: String?,
)

/**
 * 按关键词搜索播放列表。
 *
 * 标题/歌手是直接命中，排在歌词命中之前；歌词命中之间按相关度降序——以命中行数衡量，
 * 命中行数相同则保持列表原有次序（[sortedByDescending] 为稳定排序）。关键词为空即全量返回。
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

// 单曲歌词命中：片段取首个非空命中行，matchCount 为命中行数，用作相关度
private class LyricHit(val snippet: String, val matchCount: Int)

// 在歌词行原文与翻译中查找关键词；仅翻译命中时片段取翻译，行内两者皆空的行不计入、也不取作片段
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