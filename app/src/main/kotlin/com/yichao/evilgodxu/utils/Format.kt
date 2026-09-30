package com.yichao.evilgodxu.utils

// 字节数转紧凑可读文本：按 1024 进制递进，小数位固定两位，避免同一列表内宽度随数值跳动
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return "%.2f %s".format(value, units[index])
}

// 字节数按 10 进制兆字节（MB）换算：存储厂商与文件管理器口径一致
internal fun formatMegabytes(bytes: Long): String = "%.2f MB".format(bytes / 1_000_000.0)

// 字节数按 2 进制兆字节（MiB）换算：与 [formatBytes] 同为 1024 进制，供对照展示
internal fun formatMebibytes(bytes: Long): String = "%.2f MiB".format(bytes / (1024.0 * 1024.0))

// 毫秒转 m:ss 文本
internal fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}