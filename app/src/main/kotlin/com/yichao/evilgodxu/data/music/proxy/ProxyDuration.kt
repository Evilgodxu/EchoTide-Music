package com.yichao.evilgodxu.data.music.proxy

/**
 * 代理音源的时长解析。
 *
 * 规范约定 `duration` 为毫秒数，但实际音源常直接透传平台的展示值（`"04:29"`）。
 * 纯数字按规范当作毫秒，带冒号的按 `[时:]分:秒` 解析，其余视为无值：
 * 时长缺失只少一项展示，而把 `"04:29"` 解析成 0 会让曲目落入「时长未知」，
 * 后续的试听判定与排序都拿不到依据。
 */
internal fun parseProxyDurationMs(raw: Any?): Long? = when (raw) {
    null -> null
    is Long -> raw.takeIf { it > 0L }
    is Int -> raw.toLong().takeIf { it > 0L }
    is Double -> raw.toLong().takeIf { it > 0L }
    is String -> parseDurationText(raw.trim())
    else -> null
}

private fun parseDurationText(text: String): Long? {
    if (text.isEmpty()) return null
    // 纯数字是规范里的毫秒值，不做秒假设
    if (!text.contains(':')) return text.toLongOrNull()?.takeIf { it > 0L }
    val parts = text.split(':').map { it.trim().toLongOrNull() ?: return null }
    if (parts.size !in 2..3 || parts.any { it < 0L }) return null
    val total = if (parts.size == 2) {
        parts[0] * 60L + parts[1]
    } else {
        (parts[0] * 60L + parts[1]) * 60L + parts[2]
    }
    return if (total > 0L) total * 1000L else null
}
