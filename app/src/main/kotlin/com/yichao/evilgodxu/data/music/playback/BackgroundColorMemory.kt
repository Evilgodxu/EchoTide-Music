package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.yichao.evilgodxu.data.music.metadata.CoverBackgroundColors
import com.yichao.evilgodxu.data.music.metadata.CoverColorCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.settings.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 首页背景取色：三段色随曲目落盘，首帧铺底并回填缓存 */
internal class BackgroundColorMemory(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
    private val currentTrackUri: () -> String?,
    private val onCurrentTrackColorsChanged: () -> Unit,
) {

    // 键名沿用历史值，避免已装机用户取色丢失
    private val uriKey = stringPreferencesKey("music_saved_gradient_uri")
    private val edgeKey = intPreferencesKey("music_saved_gradient_edge")
    private val topKey = intPreferencesKey("music_saved_gradient_top")
    private val bottomKey = intPreferencesKey("music_saved_gradient_bottom")

    private var savedColors by mutableStateOf<CoverBackgroundColors?>(null)
    private var savedUri by mutableStateOf<String?>(null)

    val colors: CoverBackgroundColors? get() = savedColors
    val uri: String? get() = savedUri

    // 仅同源曲目返回，避免切歌后旧色闪帧
    fun restoredColorFor(track: MusicTrack?): CoverBackgroundColors? =
        if (track != null && track.audioUri == savedUri) savedColors else null

    /** 采纳已持久化或镜像预置的取色，同步回填缓存 */
    fun adopt(uri: String?, colors: CoverBackgroundColors?) {
        savedUri = uri
        savedColors = colors
        if (uri != null && colors != null) CoverColorCache.put(uri, colors)
    }

    /** 恢复取色，与镜像同源时不覆盖 */
    fun restoreFrom(preferences: Preferences) {
        val restoredUri = preferences[uriKey]
        if (restoredUri == savedUri) return
        // 历史只有代表色，展开为三段同色
        val edge = preferences[edgeKey]
        val colors = if (restoredUri != null && edge != null) {
            CoverBackgroundColors(
                top = Color(preferences[topKey] ?: edge),
                middle = Color(edge),
                bottom = Color(preferences[bottomKey] ?: edge),
            )
        } else {
            null
        }
        adopt(restoredUri, colors)
    }

    // 无损升级换 URI 时改指取色，避免背景回落默认色
    fun remapUri(fromUri: String, toUri: String) {
        if (savedUri != fromUri) return
        savedUri = toUri
        val colors = savedColors ?: return
        val context = context() ?: return
        scope().launch {
            withContext(Dispatchers.IO) {
                context.settingsDataStore.edit { preferences ->
                    preferences[uriKey] = toUri
                    preferences[edgeKey] = colors.representative.toArgb()
                    preferences[topKey] = colors.top.toArgb()
                    preferences[bottomKey] = colors.bottom.toArgb()
                }
            }
        }
    }

    // 首页真实取色成功后落盘，供下次复用
    fun saveForCurrentTrack(colors: CoverBackgroundColors) {
        val uri = currentTrackUri() ?: return
        saveFor(uri, colors)
    }

    // 取色按曲目落盘，显示端与切歌预取共用
    internal fun saveFor(uri: String, colors: CoverBackgroundColors) {
        savedColors = colors
        savedUri = uri
        // 回填缓存，其余显示端直接复用
        CoverColorCache.put(uri, colors)
        // 同步更新镜像，冷启动首帧靠它
        if (currentTrackUri() == uri) onCurrentTrackColorsChanged()
        val context = context() ?: return
        scope().launch {
            withContext(Dispatchers.IO) {
                context.settingsDataStore.edit { preferences ->
                    preferences[uriKey] = uri
                    preferences[edgeKey] = colors.representative.toArgb()
                    preferences[topKey] = colors.top.toArgb()
                    preferences[bottomKey] = colors.bottom.toArgb()
                }
            }
        }
    }

    // 封面变更后作废旧色调，等重取再落盘
    fun clear() {
        if (savedUri == null && savedColors == null) return
        savedColors = null
        savedUri = null
        // 镜像同步清掉，否则首帧仍铺旧色
        onCurrentTrackColorsChanged()
        val context = context() ?: return
        scope().launch {
            withContext(Dispatchers.IO) {
                context.settingsDataStore.edit { preferences ->
                    preferences.remove(uriKey)
                    preferences.remove(edgeKey)
                    preferences.remove(topKey)
                    preferences.remove(bottomKey)
                }
            }
        }
    }
}
