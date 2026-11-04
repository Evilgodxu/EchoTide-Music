package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.core.content.edit
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.trackIdentityKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放列表相关落盘：过滤结果、过滤来源、默认队列、浏览态、排序规则与曲库签名。
 *
 * 这些键同属一份偏好文件，写入合并在同一次 commit 中完成——拆开写会重复重写整份文件，
 * 且中途进程被杀会留下「列表已更新、来源未更新」的不一致。
 * 落盘任务按快照合并：新调用取消尚未开始的上一次写入，只保留最后一次。
 */
internal class PlaylistPersistence(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
) {

    private companion object {
        const val KEY_PLAYLIST = "music_playlist_cache"
        const val KEY_DEFAULT_BACKUP = "music_default_playlist_cache"
        const val KEY_SOURCE_KEY = "music_playlist_source_key"
        const val KEY_SOURCE_NAME = "music_playlist_source_name"
        const val KEY_VIEWED_FOLLOWS_QUEUE = "music_viewed_follows_queue"
        const val KEY_VIEWED_SOURCE_KEY = "music_viewed_source_key"
        const val KEY_VIEWED_SOURCE_NAME = "music_viewed_source_name"
        const val KEY_SORT_FIELD = "music_playlist_sort_field"
        const val KEY_SORT_DESCENDING = "music_playlist_sort_descending"
        const val KEY_LIBRARY_SIGNATURE = "music_library_signature"
    }

    data class Snapshot(
        val tracks: List<MusicTrack>,
        val source: PlaylistSource?,
        val defaultPlaylist: List<MusicTrack>,
        val followsQueue: Boolean,
        val viewedSource: PlaylistSource?,
        val sortField: PlaylistSortField,
        val sortDescending: Boolean,
    )

    data class SourceSettings(
        val source: PlaylistSource?,
        val sortField: PlaylistSortField,
        val sortDescending: Boolean,
    )

    data class ViewedSettings(
        val followsQueue: Boolean,
        val viewedSource: PlaylistSource?,
    )

    private var persistJob: Job? = null

    fun persist(snapshot: Snapshot) {
        val context = context() ?: return
        persistJob?.cancel()
        persistJob = scope().launch {
            withContext(Dispatchers.IO) {
                context.getSharedPreferences(MusicPlaybackState.PLAYLIST_CACHE_STORE.name, Context.MODE_PRIVATE)
                    .edit(commit = true) {
                        putString(KEY_PLAYLIST, PlaylistCodec.encode(snapshot.tracks))
                        putString(KEY_SOURCE_KEY, snapshot.source?.key)
                        putString(KEY_SOURCE_NAME, snapshot.source?.name)
                        putBoolean(KEY_VIEWED_FOLLOWS_QUEUE, snapshot.followsQueue)
                        putString(KEY_VIEWED_SOURCE_KEY, snapshot.viewedSource?.key)
                        putString(KEY_VIEWED_SOURCE_NAME, snapshot.viewedSource?.name)
                        putString(KEY_SORT_FIELD, snapshot.sortField.name)
                        putBoolean(KEY_SORT_DESCENDING, snapshot.sortDescending)
                        // 无过滤时过滤结果即默认队列，无需重复编码整份全量列表；
                        // 有过滤时才单独保存默认队列，供冷启动恢复完整队列
                        if (snapshot.source != null) {
                            putString(KEY_DEFAULT_BACKUP, PlaylistCodec.encode(snapshot.defaultPlaylist))
                        } else {
                            remove(KEY_DEFAULT_BACKUP)
                        }
                    }
            }
        }
    }

    // 历史缓存可能残留同文件不同 URI 形态的重复条目，按真实文件路径去重，避免冷启动直接展示重名歌曲
    fun readCachedPlaylist(context: Context): List<MusicTrack> =
        PlaylistCodec.load(context, KEY_PLAYLIST).distinctBy { trackIdentityKey(context, it) }

    fun readCachedBackup(context: Context): List<MusicTrack> =
        PlaylistCodec.load(context, KEY_DEFAULT_BACKUP).distinctBy { trackIdentityKey(context, it) }

    fun readSourceSettings(context: Context): SourceSettings {
        val prefs = context.getSharedPreferences(MusicPlaybackState.PLAYLIST_CACHE_STORE.name, Context.MODE_PRIVATE)
        val source = prefs.getString(KEY_SOURCE_KEY, null)?.let { key ->
            PlaylistSource(key, prefs.getString(KEY_SOURCE_NAME, "") ?: "")
        }
        val field = prefs.getString(KEY_SORT_FIELD, null)
            ?.let { name -> runCatching { PlaylistSortField.valueOf(name) }.getOrNull() }
            ?: PlaylistSortField.DEFAULT
        return SourceSettings(source, field, prefs.getBoolean(KEY_SORT_DESCENDING, false))
    }

    // 恢复面板浏览态：未显式浏览过歌单时跟随播放队列
    fun readViewedSettings(context: Context): ViewedSettings {
        val prefs = context.getSharedPreferences(MusicPlaybackState.PLAYLIST_CACHE_STORE.name, Context.MODE_PRIVATE)
        return ViewedSettings(
            followsQueue = prefs.getBoolean(KEY_VIEWED_FOLLOWS_QUEUE, true),
            viewedSource = prefs.getString(KEY_VIEWED_SOURCE_KEY, null)?.let { key ->
                PlaylistSource(key, prefs.getString(KEY_VIEWED_SOURCE_NAME, "") ?: "")
            },
        )
    }

    /** 是否有已落盘的曲库缓存：首启动没有，恢复与差分都无从谈起。 */
    fun hasLibraryCache(context: Context): Boolean = runCatching {
        context.getSharedPreferences(MusicPlaybackState.PLAYLIST_CACHE_STORE.name, Context.MODE_PRIVATE)
            .contains(KEY_PLAYLIST)
    }.getOrDefault(false)

    /** 落盘本轮扫描所用的曲库签名，供下次冷启动比对。 */
    fun writeLibrarySignature(context: Context, signature: String) {
        runCatching {
            context.getSharedPreferences(MusicPlaybackState.PLAYLIST_CACHE_STORE.name, Context.MODE_PRIVATE)
                .edit(commit = true) { putString(KEY_LIBRARY_SIGNATURE, signature) }
        }
    }

    /** 上次扫描时的曲库签名：为空表示从未扫完过，需按首启动处理。 */
    fun readLibrarySignature(context: Context): String? = runCatching {
        context.getSharedPreferences(MusicPlaybackState.PLAYLIST_CACHE_STORE.name, Context.MODE_PRIVATE)
            .getString(KEY_LIBRARY_SIGNATURE, null)
    }.getOrNull()
}
