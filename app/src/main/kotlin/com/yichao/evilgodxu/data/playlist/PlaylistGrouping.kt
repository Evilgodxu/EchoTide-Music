package com.yichao.evilgodxu.data.playlist

import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.parseTrackArtists

// 按 id 集合从全量曲目中解析曲目，保持集合顺序。
// 先建 id 索引再查表：逐个线性扫描全库会让调用点退化为 O(曲目数 × 集合长度)
internal fun resolveTracks(all: List<MusicTrack>, ids: Collection<Long>): List<MusicTrack> {
    if (ids.isEmpty() || all.isEmpty()) return emptyList()
    val byId = all.associateBy { it.id }
    return ids.mapNotNull { byId[it] }
}

// 常听：按最近播放顺序解析
internal fun recentTracks(all: List<MusicTrack>, recentIds: List<Long>): List<MusicTrack> {
    if (recentIds.isEmpty() || all.isEmpty()) return emptyList()
    val byId = all.associateBy { it.id }
    return recentIds.mapNotNull { byId[it] }
}

internal fun smartTrackCount(all: List<MusicTrack>, ids: Collection<Long>): Int {
    if (ids.isEmpty() || all.isEmpty()) return 0
    val existingIds = all.mapTo(HashSet(all.size)) { it.id }
    return ids.count { it in existingIds }
}

internal fun distinctAlbumCount(all: List<MusicTrack>): Int = all.map { it.albumId }.distinct().size

internal fun distinctArtistCount(all: List<MusicTrack>): Int =
    all.flatMap { parseTrackArtists(it.artist) }.distinct().size

// 按专辑分组，组名回退为未知专辑文案
internal fun albumGroups(all: List<MusicTrack>, unknownAlbum: String): List<PlaylistGroup> =
    all.groupBy { it.albumId }
        .map { (albumId, list) ->
            PlaylistGroup(
                key = "album:$albumId",
                name = list.firstOrNull()?.albumName?.takeIf { it.isNotBlank() } ?: unknownAlbum,
                trackIds = list.map { it.id },
            )
        }
        .sortedBy { it.name }

// 按艺术家分组，组名回退为未知艺术家文案。
// 多歌手曲目（如 "A / B"）解析后同时归属到每位歌手名下，
// 避免把多个歌手视作单一歌手（原按整串 it.artist 分组会把 "A / B" 当作一个歌手）
internal fun artistGroups(all: List<MusicTrack>, unknownArtist: String): List<PlaylistGroup> =
    buildMap<String, MutableList<Long>> {
        all.forEach { track ->
            parseTrackArtists(track.artist).forEach { artist ->
                getOrPut("artist:$artist") { mutableListOf() }.add(track.id)
            }
        }
    }
        .map { (key, trackIds) ->
            val artist = key.removePrefix("artist:")
            PlaylistGroup(
                key = key,
                name = artist.ifBlank { unknownArtist },
                trackIds = trackIds,
            )
        }
        .sortedBy { it.name }
