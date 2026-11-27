package com.yichao.evilgodxu.data.music.metadata

import java.io.File

/** 回收 .tmp 残留；只认后缀且超宽限期 */
internal fun reclaimTempResidueIn(dir: File, suffix: String, olderThanMs: Long): Int {
    val now = System.currentTimeMillis()
    var reclaimed = 0
    dir.listFiles()?.forEach { file ->
        if (!file.isFile || !file.name.endsWith(suffix)) return@forEach
        if (now - file.lastModified() < olderThanMs) return@forEach
        if (runCatching { file.delete() }.getOrDefault(false)) reclaimed++
    }
    return reclaimed
}
