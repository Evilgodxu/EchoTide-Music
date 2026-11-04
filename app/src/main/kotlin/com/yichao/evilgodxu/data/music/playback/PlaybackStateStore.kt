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

/**
 * 播放状态快照的落盘与读回：当前曲目 URI、进度与播放模式。
 *
 * 调用时刻立即采集快照，异步写入不再回读内存态 —— release/softRelease 随后会清空播放状态，
 * 回读会让落盘结果变成空值。写入在途时新调用只覆盖快照，由在途循环以最新快照收尾，
 * 避免取消旧任务产生「旧任务已取消、新任务未启动」的写入间隙。
 */
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

    /** 在途写入任务：定时关闭的退出要等它落盘后再终止进程。 */
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

    /** 当前曲目已不可用时清掉续播目标，避免下次启动定位到一首无法播放的曲目。 */
    suspend fun clear(context: Context) {
        withContext(Dispatchers.IO) {
            context.settingsDataStore.edit { preferences ->
                preferences.remove(uriKey)
                preferences.remove(positionKey)
            }
        }
    }
}
