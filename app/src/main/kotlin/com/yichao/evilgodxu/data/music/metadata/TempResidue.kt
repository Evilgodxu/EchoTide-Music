package com.yichao.evilgodxu.data.music.metadata

import java.io.File

/**
 * 封面落盘目录的中转残留回收规则。
 *
 * 两个封面落盘缓存（见 [CurrentCoverCache]、[LargeCoverStore]）都按「先写 `.tmp` 再改名」落盘：
 * 改名之前的半成品不属于任何落点，写入被进程结束打断就会一直留在盘上。这类文件没有任何入口会读，
 * 也不属于任何曲目的引用，是纯粹的孤儿。
 *
 * 目录与后缀由调用方给出（各自的落点入口与写入约定），此处只管两个缓存共同的那条规矩：
 * **只认后缀匹配的文件，且只回收超过宽限期的** —— 正在写入的那份不能被回收，否则写入方的改名会静默失败。
 *
 * @return 回收的文件数
 */
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
