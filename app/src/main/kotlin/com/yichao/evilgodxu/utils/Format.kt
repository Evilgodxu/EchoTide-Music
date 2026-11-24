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

// 毫秒转时间文本：不足一小时为 m:ss，达到一小时后补出小时段（h:mm:ss），
// 避免长时长以「更大的分钟数」呈现而读不出实际秒数层级
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

/**
 * 采样率按 kHz 呈现并去掉尾零：按 Hz 全写会挤满一行且触发无意义换行，尾零又不携带信息；
 * 一位小数足以表达全部常见档位（44.1kHz、88.2kHz）。
 * 小数点按运行地域呈现，故裁剪尾零时须同时认「.」与「,」。
 */
internal fun formatSampleRateKHz(hertz: Int): String =
    "%.1f".format(hertz / 1000.0).trimEnd('0').trimEnd('.', ',')