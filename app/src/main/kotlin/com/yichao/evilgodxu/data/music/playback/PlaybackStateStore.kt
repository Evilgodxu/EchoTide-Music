package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yichao.evilgodxu.data.music.model.PlayMode
import com.yichao.evilgodxu.data.settings.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 播放状态快照落盘：调用时取快照，异步写入不回读 */
internal class PlaybackStateStore(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
) {

    data class Snapshot(
        val audioUri: String,
        val position: Long,
        val modeOrdinal: Int,
    )

    data class Restored(
        val audioUri: String?,
        val position: Long,
        val modeOrdinal: Int,
    )

    private val uriKey = stringPreferencesKey("music_saved_uri")
    private val positionKey = longPreferencesKey("music_saved_position")
    private val modeKey = intPreferencesKey("music_saved_mode")

    private var pendingSnapshot: Snapshot? = null
    private var writeJob: Job? = null
    private val persistenceMutex = Mutex()

    /** 在途写入任务，定时关闭退出前等它 */
    val inFlightWrite: Job? get() = writeJob

    fun persist(audioUri: String, position: Long, modeOrdinal: Int) {
        val context = context() ?: return
        pendingSnapshot = Snapshot(audioUri, position, modeOrdinal)
        if (writeJob?.isActive == true) return
        writeJob = scope().launch {
            persistenceMutex.withLock {
                while (true) {
                    val snapshot = pendingSnapshot ?: break
                    pendingSnapshot = null
                    withContext(Dispatchers.IO) {
                        context.settingsDataStore.edit { preferences ->
                            preferences[uriKey] = snapshot.audioUri
                            preferences[positionKey] = snapshot.position
                            preferences[modeKey] = snapshot.modeOrdinal
                        }
                    }
                }
            }
        }
    }

    fun read(preferences: Preferences): Restored = Restored(
        audioUri = preferences[uriKey],
        position = preferences[positionKey] ?: 0L,
        modeOrdinal = preferences[modeKey] ?: PlayMode.RepeatAll.ordinal,
    )

    /** 曲目不可用时清掉续播目标 */
    suspend fun clear(context: Context) {
        withContext(Dispatchers.IO) {
            context.settingsDataStore.edit { preferences ->
                preferences.remove(uriKey)
                preferences.remove(positionKey)
            }
        }
    }
}
