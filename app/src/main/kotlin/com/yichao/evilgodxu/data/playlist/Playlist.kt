package com.yichao.evilgodxu.data.playlist


// 自定义歌单实体，trackIds 引用 MusicTrack.id
data class Playlist(
    val id: Long,
    val name: String,
    val trackIds: List<Long>,
    val createdAt: Long,
)

// 系统歌单类型
enum class SmartPlaylistType { RECENT, FAVORITE, ALBUM, ARTIST }

// 系统歌单分组（专辑/艺术家）
data class PlaylistGroup(
    val key: String,
    val name: String,
    val trackIds: List<Long>,
)
