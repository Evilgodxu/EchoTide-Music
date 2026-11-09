package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.yichao.evilgodxu.data.settings.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * 冷启动恢复的读取侧：并发读出各份存储，并给出可直接套用的初始快照。
 *
 * 各段读取互不依赖，总耗时取最慢一段而非各段之和。
 * 应用状态仍由宿主负责——读取只产出数据，不触碰播放状态。
 */
internal class StartupStateLoader(
    private val searchHistoryStore: SearchHistoryStore,
    private val recentPlayedStore: RecentPlayedStore,
    private val playlistPersistence: PlaylistPersistence,
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
        // 搜索历史与常听由各自的收集侧持续回流，此处只等首次读盘完成
        searchHistoryStore.awaitLoaded()
        recentPlayedStore.awaitLoaded()
        Result(
            preferences = preferencesRead.await(),
            queue = queueRead.await(),
        )
    }
}
