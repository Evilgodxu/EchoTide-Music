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

/**
 * 首页背景取色结果的持有、复用与落盘。
 *
 * 三段色与所属曲目 URI 一起保存：冷启动首帧、系统略缩图就绪前由它直接铺底，
 * 背景帧与底色的色调也同取这一份。内存侧同步回填 [CoverColorCache]，
 * 显示端与流动帧代现算命中后即视为「已取色」，不再从封面边缘重算。
 */
internal class BackgroundColorMemory(
    private val scope: () -> CoroutineScope,
    private val context: () -> Context?,
    private val currentTrackUri: () -> String?,
    private val onCurrentTrackColorsChanged: () -> Unit,
) {

    // 键名沿用历史取值：早期版本只有一个代表色存在 edge 键下，改键名会让已装机用户的取色结果读不到
    // 而多闪一次默认背景；历史数据只有 edge 一项，读回时按单色展开成三段同色
    private val uriKey = stringPreferencesKey("music_saved_gradient_uri")
    private val edgeKey = intPreferencesKey("music_saved_gradient_edge")
    private val topKey = intPreferencesKey("music_saved_gradient_top")
    private val bottomKey = intPreferencesKey("music_saved_gradient_bottom")

    private var savedColors by mutableStateOf<CoverBackgroundColors?>(null)
    private var savedUri by mutableStateOf<String?>(null)

    val colors: CoverBackgroundColors? get() = savedColors
    val uri: String? get() = savedUri

    // 仅当曲目与取色结果同源时返回，避免运行时切歌后旧曲目的恢复色闪帧
    fun restoredColorFor(track: MusicTrack?): CoverBackgroundColors? =
        if (track != null && track.audioUri == savedUri) savedColors else null

    /**
     * 采纳一份已持久化或镜像预置的取色结果。
     *
     * 内存缓存同步回填：同一曲目的其余显示端与流动帧代现算直接复用，不再从封面重算。
     */
    fun adopt(uri: String?, colors: CoverBackgroundColors?) {
        savedUri = uri
        savedColors = colors
        if (uri != null && colors != null) CoverColorCache.put(uri, colors)
    }

    /**
     * 冷启动恢复：与启动镜像预置的取色结果同源时不覆盖。
     *
     * 两者写入点相同，镜像可能领先一次（取色落盘与状态落盘之间存在进程被杀窗口），
     * 覆盖会让首帧背景色回退。
     */
    fun restoreFrom(preferences: Preferences) {
        val restoredUri = preferences[uriKey]
        if (restoredUri == savedUri) return
        // 历史数据只有代表色（edge）一项：按单色展开成三段同色，观感等同旧版整幅铺色
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

    // 无损升级替换音频文件时 URI 变化但封面/背景不变：把已持久化的取色结果改指到新 URI，
    // 避免升级后背景回落默认色（新文件系统略缩图未就绪前也保持既有背景）
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

    // 首页背景真实取色成功后持久化，供下次播放直接复用
    fun saveForCurrentTrack(colors: CoverBackgroundColors) {
        val uri = currentTrackUri() ?: return
        saveFor(uri, colors)
    }

    // 取色结果按所属曲目落盘：显示端取色与切歌后台取色共用，避免退出时才保存而丢失
    internal fun saveFor(uri: String, colors: CoverBackgroundColors) {
        savedColors = colors
        savedUri = uri
        // 内存缓存同步回填：同一曲目的其余显示端与流动帧代现算直接复用，不再从封面重算
        CoverColorCache.put(uri, colors)
        // 启动镜像同步更新取色结果：冷启动首帧的背景色同样只能来自镜像
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

    // 作废已持久化的背景取色结果：封面变更后旧色调不再成立，等重取后再落盘
    fun clear() {
        if (savedUri == null && savedColors == null) return
        savedColors = null
        savedUri = null
        // 启动镜像同步清掉：镜像仍持旧色调，冷启动首帧会由它预置出旧背景
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
