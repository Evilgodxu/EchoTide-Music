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

/**
 * 在线搜索平台的落盘与读回。
 *
 * 平台是单值选择，重启后应回到上次选中的那一个，而不是每次都从默认平台开始。
 * 写入在途时新选择只覆盖待写值，由在途写入以最新值收尾——连续切换不必各写一次盘。
 */
internal class SearchSourceStore(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
) {

    private var pendingSource: MusicSearchSource? = null
    private var writeJob: Job? = null

    /** 保存的平台；从未切换过或保存值不可解析时为 null。 */
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

/**
 * 恢复保存的搜索平台：内置平台恒定可选，自定义平台须仍有启用的音源提供它的搜索动作。
 *
 * 已不可选的平台回退默认平台——搜索条上留着一个查不到内容的平台名，比回到默认更让人困惑。
 */
internal fun resolveSearchSource(
    saved: MusicSearchSource?,
    availableKeys: Set<String>,
): MusicSearchSource =
    saved?.takeIf { it.key in availableKeys } ?: MusicSearchSource.NETEASE
