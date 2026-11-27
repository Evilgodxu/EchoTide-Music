package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.serialization.Serializable

/** 播放列表落盘的曲目形态：歌词不落盘，路径读回时校验 */
@Serializable
internal data class StoredTrack(
    val id: Long,
    val path: String,
    val audioUri: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val albumId: Long,
    val albumName: String = "",
    val neteaseId: Long = 0L,
    val neteaseCoverUrl: String = "",
    val lyricCachePath: String = "",
    val isFavorite: Boolean = false,
    val lyricOffsetMs: Long = 0L,
    val lyricFailed: Boolean = false,
    val isOnlinePlay: Boolean = false,
    val fileModifiedMs: Long = 0L,
)

internal fun MusicTrack.toStoredTrack(): StoredTrack = StoredTrack(
    id = id,
    path = path,
    audioUri = audioUri,
    title = title,
    artist = artist,
    duration = duration,
    albumId = albumId,
    albumName = albumName,
    neteaseId = neteaseId,
    neteaseCoverUrl = neteaseCoverUrl,
    lyricCachePath = lyricCachePath,
    isFavorite = isFavorite,
    lyricOffsetMs = lyricOffsetMs,
    lyricFailed = lyricFailed,
    isOnlinePlay = isOnlinePlay,
    fileModifiedMs = fileModifiedMs,
)

internal fun StoredTrack.toMusicTrack(context: Context): MusicTrack = MusicTrack(
    id = id,
    path = path,
    audioUri = audioUri,
    title = title,
    artist = artist,
    duration = duration,
    albumId = albumId,
    albumName = albumName,
    neteaseId = neteaseId,
    neteaseCoverUrl = neteaseCoverUrl,
    isFavorite = isFavorite,
    isOnlinePlay = isOnlinePlay,
    // 歌词延迟到显示时按需读取，冷启动不逐首解析
    lyricCachePath = lyricCachePath.takeIf { MusicMetadataCache.isValid(context, it) }.orEmpty(),
    lyricLines = emptyList(),
    lyricOffsetMs = lyricOffsetMs,
    lyricFailed = lyricFailed,
    fileModifiedMs = fileModifiedMs,
)
