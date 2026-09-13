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
