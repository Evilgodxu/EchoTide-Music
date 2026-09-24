package com.yichao.evilgodxu.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.yichao.evilgodxu.data.settings.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val miniPlayerEnabledKey = booleanPreferencesKey("mini_player_enabled")
private val wordByWordRenderingKey = booleanPreferencesKey("word_by_word_rendering")
private val swipeToChangeTrackKey = booleanPreferencesKey("swipe_to_change_track")
private val backgroundFlowEnabledKey = booleanPreferencesKey("background_flow_enabled")

// 迷你模式默认关闭
fun Context.miniPlayerEnabledFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[miniPlayerEnabledKey] ?: false }

suspend fun Context.saveMiniPlayerEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
    settingsDataStore.edit { it[miniPlayerEnabledKey] = enabled }
}

// 逐字渲染默认开启：关闭后歌词退化为整行高亮
fun Context.wordByWordRenderingFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[wordByWordRenderingKey] ?: true }

suspend fun Context.saveWordByWordRendering(enabled: Boolean) = withContext(Dispatchers.IO) {
    settingsDataStore.edit { it[wordByWordRenderingKey] = enabled }
}

// 滑动切歌默认开启：关闭后首页上下滑动不再切换歌曲
fun Context.swipeToChangeTrackFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[swipeToChangeTrackKey] ?: true }

suspend fun Context.saveSwipeToChangeTrack(enabled: Boolean) = withContext(Dispatchers.IO) {
    settingsDataStore.edit { it[swipeToChangeTrackKey] = enabled }
}

// 背景流动默认关闭：关闭时首页背景只渲染一帧静态的封面衍生背景，开启后按固定默认值缓慢流动
fun Context.backgroundFlowEnabledFlow(): Flow<Boolean> =
    settingsDataStore.data.map { it[backgroundFlowEnabledKey] ?: false }

suspend fun Context.saveBackgroundFlowEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
    settingsDataStore.edit { it[backgroundFlowEnabledKey] = enabled }
}
