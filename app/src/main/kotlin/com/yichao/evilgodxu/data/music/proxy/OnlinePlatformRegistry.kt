package com.yichao.evilgodxu.data.music.proxy

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.R

// 平台切换菜单的一项：平台标识 + 展示名
internal data class OnlinePlatformOption(
    val source: MusicSearchSource,
    val name: String,
)

// 在线平台注册表：枚举平台切换菜单的候选，并提供按平台键取展示名的能力。
// 内置平台固定可见、名称取应用内字符串资源；自定义平台来自已启用的代理音源，名称由音源声明
internal object OnlinePlatformRegistry {

    // 可选平台：内置平台在前，自定义平台按音源导入顺序追加
    fun options(context: Context): List<OnlinePlatformOption> {
        val builtIn = MusicSearchSource.BUILT_IN.map { source ->
            OnlinePlatformOption(source, builtInNameRes(source)?.let(context::getString) ?: source.key)
        }
        val custom = ProxySourceStore.customSearchPlatforms(context).map { (key, name) ->
            OnlinePlatformOption(MusicSearchSource(key), name)
        }
        return builtIn + custom
    }

    // 平台展示名：候选标签等按平台取名的场景使用。
    // 音源被移除后其历史结果已查不到名称，此时回退平台键，取值可能不是人类可读文本
    fun displayName(context: Context, source: MusicSearchSource): String {
        builtInNameRes(source)?.let { return context.getString(it) }
        return ProxySourceStore.customSearchPlatforms(context)[source.key] ?: source.key
    }

    // 内置平台名称资源；自定义平台无对应资源，返回 null
    private fun builtInNameRes(source: MusicSearchSource): Int? = when (source) {
        MusicSearchSource.NETEASE -> R.string.music_panel_search_source
        MusicSearchSource.QQ -> R.string.music_panel_search_source_qq
        MusicSearchSource.KUGOU -> R.string.music_panel_search_source_kugou
        MusicSearchSource.KUWO -> R.string.music_panel_search_source_kuwo
        MusicSearchSource.MIGU -> R.string.music_panel_search_source_migu
        else -> null
    }
}
