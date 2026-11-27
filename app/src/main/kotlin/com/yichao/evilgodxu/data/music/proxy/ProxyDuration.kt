package com.yichao.evilgodxu.data.music.proxy

// 音源时长解析：规范为毫秒，也兼容 [时:]分:秒
// 其余视为无值，避免解析成 0 落入时长未知
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
