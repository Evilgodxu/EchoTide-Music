package com.yichao.evilgodxu.data.music.proxy

import android.content.Context
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.MusicSearchSource

internal data class OnlinePlatformOption(
    val source: MusicSearchSource,
    val name: String,
)

// 在线平台注册表：枚举候选并提供平台展示名
// 内置平台固定可见，自定义平台来自已启用音源
internal object OnlinePlatformRegistry {

    // 可选平台：内置在前，自定义按导入顺序追加
    fun options(context: Context, sources: List<ProxySourceSpec>): List<OnlinePlatformOption> {
        val builtIn = MusicSearchSource.BUILT_IN.map { source ->
            OnlinePlatformOption(source, builtInNameRes(source)?.let(context::getString) ?: source.key)
        }
        val custom = customSearchPlatforms(sources).map { (key, name) ->
            OnlinePlatformOption(MusicSearchSource(key), name)
        }
        return builtIn + custom
    }

    // 平台展示名，供候选标签等按平台取名
    // 音源移除后查不到名称，回退平台键
    fun displayName(
        context: Context,
        sources: List<ProxySourceSpec>,
        source: MusicSearchSource,
    ): String {
        builtInNameRes(source)?.let { return context.getString(it) }
        return customSearchPlatforms(sources)[source.key] ?: source.key
    }

    // 自定义平台无对应资源，返回 null
    private fun builtInNameRes(source: MusicSearchSource): Int? = when (source) {
        MusicSearchSource.NETEASE -> R.string.music_panel_search_source
        MusicSearchSource.QQ -> R.string.music_panel_search_source_qq
        MusicSearchSource.KUGOU -> R.string.music_panel_search_source_kugou
        MusicSearchSource.KUWO -> R.string.music_panel_search_source_kuwo
        else -> null
    }
}
