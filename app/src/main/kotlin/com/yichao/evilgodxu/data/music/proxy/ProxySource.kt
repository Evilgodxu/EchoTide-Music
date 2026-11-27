package com.yichao.evilgodxu.data.music.proxy

import com.yichao.evilgodxu.data.music.api.MusicQuality
import org.json.JSONArray
import org.json.JSONObject

// 代理音源数据模型
data class ProxySourceSpec(
    val name: String,
    val version: String,
    val author: String,
    val homepage: String,
    val description: String,
    val platforms: Map<String, ProxyPlatformSpec>,
    // 原始 JSON，持久化与转发展示使用
    val rawJson: String,
    // 停用的音源不参与任何平台解析
    val enabled: Boolean = true,
)

// 动作可缺省（自定义 search 除外），缺省回退内置
data class ProxyPlatformSpec(
    // 平台名仅自定义平台持有，内置平台取字符串资源
    val name: String? = null,
    val search: ProxyActionSpec? = null,
    val url: ProxyActionSpec? = null,
    val lyric: ProxyActionSpec? = null,
    val pic: ProxyActionSpec? = null,
) {
    val hasAnyAction: Boolean
        get() = search != null || url != null || lyric != null || pic != null
}

// 动作：请求模板、音质映射与结果映射
data class ProxyActionSpec(
    val method: String,
    val url: String,
    val params: Map<String, String>,
    // POST 场景追加到 URL 的查询参数
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val qualities: Map<MusicQuality, String>,
    val result: ProxyResultSpec,
) {
    val isPost: Boolean
        get() = method.equals("POST", ignoreCase = true)
}

// 结果映射：未配置字段为 null，由调用方回退
data class ProxyResultSpec(
    val list: String? = null,
    val id: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val coverUrl: String? = null,
    val coverId: String? = null,
    val sourceId: String? = null,
    val duration: String? = null,
    val url: String? = null,
    val lyric: String? = null,
    val tlyric: String? = null,
    // 标签字段仅 search 使用，缺省即缓存落地时为空
    val album: String? = null,
    val date: String? = null,
    val trackNo: String? = null,
    val discNo: String? = null,
    // 平台响度，缓存落地写成 ReplayGain
    val rgTrackGain: String? = null,
    val rgTrackPeak: String? = null,
)

// 最小 JSONPath：支持 $、.字段 与 [下标]
internal object ProxyJsonPath {
    fun resolve(root: Any?, rawPath: String?): Any? {
        if (rawPath.isNullOrBlank()) return null
        var current: Any? = root
        val trimmed = rawPath.trim()
        val path = if (trimmed.startsWith("$")) trimmed.substring(1).let { if (it.startsWith(".")) it.substring(1) else it } else trimmed
        val tokens = parseTokens(path) ?: return null
        for (token in tokens) {
            current = when (current) {
                is JSONObject -> if (token.startsWith("[")) null else current.opt(token)
                is JSONArray -> {
                    val index = (if (token.length > 2) token.substring(1, token.length - 1).toIntOrNull() else null)
                        ?: return null
                    if (index in 0 until current.length()) current.get(index) else null
                }
                else -> null
            }
            if (current == null || current == JSONObject.NULL) return null
        }
        return current?.takeIf { it != JSONObject.NULL }
    }

    private fun parseTokens(path: String): List<String>? {
        val tokens = mutableListOf<String>()
        val field = StringBuilder()
        var index = 0
        while (index < path.length) {
            when (val char = path[index]) {
                '[' -> {
                    if (field.isNotEmpty()) {
                        tokens += field.toString()
                        field.setLength(0)
                    }
                    val close = path.indexOf(']', index)
                    if (close < 0) return null
                    val raw = path.substring(index + 1, close)
                    val itemIndex = raw.toIntOrNull() ?: return null
                    if (itemIndex < 0) return null
                    tokens += "[$itemIndex]"
                    index = close + 1
                }
                '.' -> {
                    if (field.isNotEmpty()) {
                        tokens += field.toString()
                        field.setLength(0)
                    }
                    index++
                }
                else -> {
                    field.append(char)
                    index++
                }
            }
        }
        if (field.isNotEmpty()) tokens += field.toString()
        return tokens
    }
}
