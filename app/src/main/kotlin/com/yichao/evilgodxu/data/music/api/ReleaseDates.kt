package com.yichao.evilgodxu.data.music.api

import java.time.Instant
import java.time.ZoneId

// 平台时间戳统一转 yyyy-MM-dd
// 非法时间戳返回 null，避免写入 1970-01-01
internal fun dateTextFromEpochMillis(millis: Long): String? {
    if (millis <= 0L) return null
    return runCatching {
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }.getOrNull()
}

internal fun dateTextFromEpochSeconds(seconds: Long): String? {
    if (seconds <= 0L) return null
    return dateTextFromEpochMillis(seconds * 1000L)
}
