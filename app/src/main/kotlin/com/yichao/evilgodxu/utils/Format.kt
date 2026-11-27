package com.yichao.evilgodxu.utils

// 按 1024 进制；两位小数，宽度不跳动
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

// 按 10 进制 MB 换算
internal fun formatMegabytes(bytes: Long): String = "%.2f MB".format(bytes / 1_000_000.0)

// 按 MiB（1024 进制）换算，供对照
internal fun formatMebibytes(bytes: Long): String = "%.2f MiB".format(bytes / (1024.0 * 1024.0))

// 毫秒转时间；满一小时用 h:mm:ss
internal fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    val minutes = totalSeconds % 3600 / 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:$seconds"
    } else {
        "$minutes:$seconds"
    }
}

/** 采样率转 kHz 并去尾零；小数点须兼容 . 与 , */
internal fun formatSampleRateKHz(hertz: Int): String =
    "%.1f".format(hertz / 1000.0).trimEnd('0').trimEnd('.', ',')