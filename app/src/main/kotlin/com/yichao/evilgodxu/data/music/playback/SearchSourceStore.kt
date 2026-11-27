package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.settings.SettingsKeys
import com.yichao.evilgodxu.data.settings.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 搜索平台落盘：单值选择，写入在途时只覆盖待写值 */
internal class SearchSourceStore(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
) {

    private var pendingSource: MusicSearchSource? = null
    private var writeJob: Job? = null

    /** 保存的平台，无或不可解析为 null */
    fun read(preferences: Preferences): MusicSearchSource? =
        preferences[SettingsKeys.SEARCH_SOURCE]
            ?.takeIf { it.isNotBlank() }
            ?.let(::MusicSearchSource)

    fun persist(source: MusicSearchSource) {
        val context = context() ?: return
        pendingSource = source
        if (writeJob?.isActive == true) return
        writeJob = scope().launch {
            while (true) {
                val target = pendingSource ?: break
                pendingSource = null
                withContext(Dispatchers.IO) {
                    context.settingsDataStore.edit { preferences ->
                        preferences[SettingsKeys.SEARCH_SOURCE] = target.key
                    }
                }
            }
        }
    }
}

/** 校验保存平台：内置恒可选，自定义须仍有启用音源，否则回退默认 */
internal fun resolveSearchSource(
    saved: MusicSearchSource?,
    availableKeys: Set<String>,
): MusicSearchSource =
    saved?.takeIf { it.key in availableKeys } ?: MusicSearchSource.NETEASE
