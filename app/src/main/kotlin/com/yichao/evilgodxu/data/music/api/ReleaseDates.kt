package com.yichao.evilgodxu.data.music.api

import java.time.Instant
import java.time.ZoneId

/**
 * 平台时间戳转标签日期。
 *
 * 各平台给的都是发行时间戳（网易为毫秒，QQ 搜索结果 pubtime 为秒，酷狗直接给日期串），
 * 这里统一收敛成 `yyyy-MM-dd`：ID3 的 TDRC、Vorbis 的 DATE、MP4 的 ©day 都按同一形式写入，
 * 读取端不必按容器猜格式。非法时间戳（缺省、0、溢出）返回 null —— 宁可字段留空，
 * 也不要把 1970-01-01 当成发行日期写进文件。
 */
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
