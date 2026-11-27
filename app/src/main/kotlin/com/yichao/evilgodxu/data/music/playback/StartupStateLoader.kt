package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.yichao.evilgodxu.data.music.proxy.ProxySourceStore
import com.yichao.evilgodxu.data.settings.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/** 冷启动读取侧：并发读出各份存储，产出初始快照 */
internal class StartupStateLoader(
    private val searchHistoryStore: SearchHistoryStore,
    private val recentPlayedStore: RecentPlayedStore,
    private val playlistPersistence: PlaylistPersistence,
    private val proxySourceStore: ProxySourceStore,
) {

    data class Result(
        val preferences: Preferences,
        val queue: PlaylistPersistence.StoredQueue,
    )

    suspend fun load(context: Context): Result = coroutineScope {
        val preferencesRead = async(Dispatchers.IO) {
            context.settingsDataStore.data.first()
        }
        val queueRead = async(Dispatchers.IO) {
            playlistPersistence.read()
        }
        // 搜索历史与常听由收集侧回流，只等首次读盘
        searchHistoryStore.awaitLoaded()
        recentPlayedStore.awaitLoaded()
        // 音源快照须先就绪，供平台校验
        proxySourceStore.awaitLoaded()
        Result(
            preferences = preferencesRead.await(),
            queue = queueRead.await(),
        )
    }
}
