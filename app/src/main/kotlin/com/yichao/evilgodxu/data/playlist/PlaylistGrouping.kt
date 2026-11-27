package com.yichao.evilgodxu.data.playlist

import com.yichao.evilgodxu.data.music.api.stableIdFromString
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
import com.yichao.evilgodxu.data.music.playback.parseTrackArtists
import kotlin.math.abs

// 按 id 集合解析曲目，先建索引避免 O(n×m)
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

// 按来源 key 解析曲目，套用各自存档曲序
internal fun resolveSourceTracks(
    all: List<MusicTrack>,
    playlists: List<Playlist>,
    likedIds: Set<Long>,
    recentPlayedIds: List<Long>,
    source: PlaylistSource,
    albumOrders: Map<String, List<Long>>,
    artistOrders: Map<String, List<Long>>,
    favoriteOrder: List<Long>,
): List<MusicTrack> = when {
    source.key == "smart:RECENT" -> recentTracks(all, recentPlayedIds)
    source.key == "smart:FAVORITE" ->
        applySavedTrackOrder(resolveTracks(all, likedIds), favoriteOrder)
    source.key.startsWith("custom:") -> {
        val playlistId = source.key.removePrefix("custom:").toLongOrNull()
        resolveTracks(all, playlists.firstOrNull { it.id == playlistId }?.trackIds.orEmpty())
    }
    source.key.startsWith("album:") -> {
        val albumId = source.key.removePrefix("album:").toLongOrNull()
        applySavedTrackOrder(
            tracks = all.filter { albumId != null && it.albumId == albumId },
            savedIds = albumOrders[source.key].orEmpty(),
        )
    }
    source.key.startsWith("artist:") -> {
        val artist = source.key.removePrefix("artist:")
        applySavedTrackOrder(
            tracks = all.filter { artist in parseTrackArtists(it.artist) },
            savedIds = artistOrders[artist].orEmpty(),
        )
    }
    else -> emptyList()
}

// 来源是否有效：系统歌单可为空，分组须有曲目
internal fun isViewSourceValid(
    playlists: List<Playlist>,
    resolved: List<MusicTrack>,
    source: PlaylistSource,
): Boolean = when {
    source.key.startsWith("smart:") -> true
    source.key.startsWith("custom:") -> {
        val playlistId = source.key.removePrefix("custom:").toLongOrNull()
        playlists.any { it.id == playlistId }
    }
    else -> resolved.isNotEmpty()
}

internal fun distinctAlbumCount(all: List<MusicTrack>): Int = all.map { it.albumId }.distinct().size

internal fun distinctArtistCount(all: List<MusicTrack>): Int =
    all.flatMap { parseTrackArtists(it.artist) }.distinct().size

/**
 * 专辑改名后的 albumId：同名并入，否则取哈希负值
 * MediaStore id 恒为正，负值不冲突；空名归 0
 */
internal fun albumIdentity(all: List<MusicTrack>, album: String): Long {
    val name = album.trim()
    if (name.isEmpty()) return 0L
    all.firstOrNull { it.albumName.trim().equals(name, ignoreCase = true) }?.let { return it.albumId }
    return -abs(stableIdFromString(name.lowercase()))
}

// 按专辑分组，组名回退未知专辑
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

// 按艺术家分组；多歌手各自归属
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

// 单个艺术家分组，避免构建全库分组表
internal fun artistGroup(all: List<MusicTrack>, artist: String): PlaylistGroup =
    PlaylistGroup(
        key = "artist:$artist",
        name = artist,
        trackIds = all.filter { artist in parseTrackArtists(it.artist) }.map { it.id },
    )

// 按存档曲序重排；未收录的曲目保持原序附后
internal fun applySavedTrackOrder(tracks: List<MusicTrack>, savedIds: List<Long>): List<MusicTrack> {
    if (savedIds.isEmpty()) return tracks
    val rank = savedIds.withIndex().associate { (index, id) -> id to index }
    // sortedBy 稳定，未收录曲目排在后面
    return tracks.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
}

// 歌单封面：套存档曲序后取第一首
internal fun playlistCoverTrack(
    tracksById: Map<Long, MusicTrack>,
    trackIds: List<Long>,
    savedTrackIds: List<Long>,
): MusicTrack? = applySavedTrackOrder(
    tracks = trackIds.mapNotNull { tracksById[it] },
    savedIds = savedTrackIds,
).firstOrNull()
