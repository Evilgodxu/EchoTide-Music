package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlinx.serialization.Serializable

/**
 * 播放列表落盘的曲目形态。
 *
 * 不直接序列化 [MusicTrack]：歌词内容不落盘（显示时按缓存文件懒加载），
 * 歌词缓存路径读回时还要按文件是否仍可用做一次校验，这两点必须落在编解码处而非模型上。
 */
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
    // 歌词内容延迟到显示时按需从缓存文件读取（含偏移），
    // 冷启动不逐首解析歌词，避免大歌单的数百次文件读取拖慢所有界面首帧
    lyricCachePath = lyricCachePath.takeIf { MusicMetadataCache.isValid(context, it) }.orEmpty(),
    lyricLines = emptyList(),
    lyricOffsetMs = lyricOffsetMs,
    lyricFailed = lyricFailed,
    fileModifiedMs = fileModifiedMs,
)
