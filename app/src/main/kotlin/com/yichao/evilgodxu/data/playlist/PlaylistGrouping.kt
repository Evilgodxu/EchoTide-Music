package com.yichao.evilgodxu.data.playlist

import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
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

// 按歌单来源 key 从全量库解析曲目，供播放列表面板按来源展示。
// 分支与生成来源 key 的分组逻辑保持一致：专辑/艺术家按各自分组 key 反查，
// 自定义歌单按 trackIds 查表，故全量库变化后重新解析即为最新内容
internal fun resolveSourceTracks(
    all: List<MusicTrack>,
    playlists: List<Playlist>,
    likedIds: Set<Long>,
    recentPlayedIds: List<Long>,
    source: PlaylistSource,
): List<MusicTrack> = when {
    source.key == "smart:RECENT" -> recentTracks(all, recentPlayedIds)
    source.key == "smart:FAVORITE" -> resolveTracks(all, likedIds)
    source.key.startsWith("custom:") -> {
        val playlistId = source.key.removePrefix("custom:").toLongOrNull()
        resolveTracks(all, playlists.firstOrNull { it.id == playlistId }?.trackIds.orEmpty())
    }
    source.key.startsWith("album:") -> {
        val albumId = source.key.removePrefix("album:").toLongOrNull()
        all.filter { albumId != null && it.albumId == albumId }
    }
    source.key.startsWith("artist:") -> {
        val artist = source.key.removePrefix("artist:")
        all.filter { artist in parseTrackArtists(it.artist) }
    }
    else -> emptyList()
}

// 浏览的来源歌单是否仍然有效。常听/收藏等系统歌单允许为空（无播放记录、无收藏都属正常），
// 自定义歌单须仍存在（空歌单也是有效状态），专辑/艺术家分组则以其是否还能解析出曲目为准
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

// 单个艺术家分组：与 artistGroups 同口径按多歌手分隔符归属，供点击歌手信息直达该歌手歌单，
// 避免为取一个分组而构建全库分组表
internal fun artistGroup(all: List<MusicTrack>, artist: String): PlaylistGroup =
    PlaylistGroup(
        key = "artist:$artist",
        name = artist,
        trackIds = all.filter { artist in parseTrackArtists(it.artist) }.map { it.id },
    )
