package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * 曲目列表的文本编解码。
 *
 * 播放列表缓存与启动镜像共用同一份字段口径，解析规则只在此处维护一份；
 * 调用方只决定「写到哪个键」，不参与字段拼装。
 */
internal object PlaylistCodec {

    fun encode(tracks: List<MusicTrack>): String {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(JSONObject().apply {
                put("id", track.id)
                put("path", track.path)
                put("audioUri", track.audioUri)
                put("title", track.title)
                put("artist", track.artist)
                put("duration", track.duration)
                put("albumId", track.albumId)
                put("albumName", track.albumName)
                put("neteaseId", track.neteaseId)
                put("neteaseCoverUrl", track.neteaseCoverUrl)
                put("lyricCachePath", track.lyricCachePath)
                put("isOnlinePlay", track.isOnlinePlay)
                put("isFavorite", track.isFavorite)
                put("lyricOffsetMs", track.lyricOffsetMs)
                put("lyricFailed", track.lyricFailed)
                put("fileModifiedMs", track.fileModifiedMs)
            })
        }
        return array.toString()
    }

    fun decode(context: Context, json: String): List<MusicTrack> {
        return try {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val savedLyricPath = item.optString("lyricCachePath", "")
                val lyricOffset = item.optLong("lyricOffsetMs", 0L)
                // 歌词内容延迟到显示时按需从缓存文件读取（含偏移），
                // 冷启动不逐首解析歌词，避免大歌单的数百次文件读取拖慢所有界面首帧
                val lyricCachePath = savedLyricPath.takeIf { MusicMetadataCache.isValid(context, it) }.orEmpty()
                MusicTrack(
                    id = item.getLong("id"),
                    path = item.getString("path"),
                    audioUri = item.getString("audioUri"),
                    title = item.getString("title"),
                    artist = item.getString("artist"),
                    duration = item.getLong("duration"),
                    albumId = item.getLong("albumId"),
                    albumName = item.optString("albumName", ""),
                    neteaseId = item.optLong("neteaseId", 0L),
                    neteaseCoverUrl = item.optString("neteaseCoverUrl", ""),
                    isFavorite = item.optBoolean("isFavorite", false),
                    isOnlinePlay = item.optBoolean("isOnlinePlay", false),
                    lyricCachePath = lyricCachePath,
                    lyricLines = emptyList(),
                    lyricOffsetMs = lyricOffset,
                    lyricFailed = item.optBoolean("lyricFailed", false),
                    fileModifiedMs = item.optLong("fileModifiedMs", 0L),
                )
            }
        } catch (e: Exception) {
            CrashLogManager.logException("PlaylistCodec", "读取缓存的播放列表失败", e)
            emptyList()
        }
    }

    /** 读取播放列表缓存库中指定键下的曲目；无缓存或解析失败时为空列表。 */
    fun load(context: Context, cacheKey: String): List<MusicTrack> {
        val json = context
            .getSharedPreferences(MusicPlaybackState.PLAYLIST_CACHE_STORE.name, Context.MODE_PRIVATE)
            .getString(cacheKey, null) ?: return emptyList()
        return decode(context, json)
    }
}
