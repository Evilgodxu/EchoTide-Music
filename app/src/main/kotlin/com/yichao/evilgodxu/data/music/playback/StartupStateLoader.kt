package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.settings.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 冷启动恢复的读取侧：并发读出各份存储，并给出可直接套用的初始快照。
 *
 * 各段读取互不依赖，总耗时取最慢一段而非各段之和；排序依赖读出的缓存与排序规则，
 * 故放在并发收尾处单独执行。应用状态仍由宿主负责——读取只产出数据，不触碰播放状态。
 */
internal class StartupStateLoader(
    private val searchHistoryStore: SearchHistoryStore,
    private val recentPlayedStore: RecentPlayedStore,
    private val playlistPersistence: PlaylistPersistence,
) {

    data class Result(
        val searchHistory: List<String>,
        val recentPlayEvents: List<PlayEvent>,
        val preferences: Preferences,
        val cachedPlaylist: List<MusicTrack>,
        val orderedPlaylist: List<MusicTrack>,
        val cachedBackup: List<MusicTrack>,
        val savedSource: PlaylistSource?,
        val savedSortField: PlaylistSortField,
        val savedSortDescending: Boolean,
        val savedFollowsQueue: Boolean,
        val savedViewedSource: PlaylistSource?,
    )

    suspend fun load(context: Context): Result = coroutineScope {
        val searchHistoryRead = async(Dispatchers.IO) {
            searchHistoryStore.read(context)
        }
        val recentPlayRead = async(Dispatchers.IO) {
            recentPlayedStore.read(context)
        }
        val preferencesRead = async(Dispatchers.IO) {
            context.settingsDataStore.data.first()
        }
        val cachedPlaylistRead = async(Dispatchers.IO) {
            playlistPersistence.readCachedPlaylist(context)
        }
        // 恢复上次的过滤来源、排序规则与默认队列，扫描刷新后保持过滤与排序
        val playlistPrefsRead = async(Dispatchers.IO) {
            playlistPersistence.readSourceSettings(context)
        }
        // 恢复面板浏览态：未显式浏览过歌单时跟随播放队列
        val viewedPrefsRead = async(Dispatchers.IO) {
            playlistPersistence.readViewedSettings(context)
        }
        val cachedBackupRead = async(Dispatchers.IO) {
            playlistPersistence.readCachedBackup(context)
        }

        val cachedPlaylist = cachedPlaylistRead.await()
        val (savedSource, savedSortField, savedSortDescending) = playlistPrefsRead.await()
        // 无过滤时缓存列表即默认队列，套用保存的排序规则；大库排序开销明显，放 IO 执行。
        // 有过滤时缓存列表是过滤结果，保持来源自身顺序
        val orderedCachedPlaylist = withContext(Dispatchers.IO) {
            if (savedSource == null) {
                sortTracks(cachedPlaylist, savedSortField, savedSortDescending)
            } else {
                cachedPlaylist
            }
        }
        val (savedFollowsQueue, savedViewedSource) = viewedPrefsRead.await()
        Result(
            searchHistory = searchHistoryRead.await(),
            recentPlayEvents = recentPlayRead.await(),
            preferences = preferencesRead.await(),
            cachedPlaylist = cachedPlaylist,
            orderedPlaylist = orderedCachedPlaylist,
            cachedBackup = cachedBackupRead.await(),
            savedSource = savedSource,
            savedSortField = savedSortField,
            savedSortDescending = savedSortDescending,
            savedFollowsQueue = savedFollowsQueue,
            savedViewedSource = savedViewedSource,
        )
    }
}
