package com.yichao.evilgodxu.data.cache

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.LargeCoverStore
import java.io.File

/** 缓存台账：统计各类产出占用并回收；漏登记落入兜底行 */
internal object CacheInventory {

    // 安装包只回收隔日残留，避免删掉下载中
    private const val STALE_UPDATE_PACKAGE_MS = 24 * 60 * 60 * 1000L

    // 只回收静置 60s 外的 .tmp，避开写入
    private const val TEMP_RESIDUE_GRACE_MS = 60_000L

    /** 统计各类占用；遍历为阻塞 IO，须在 IO 线程调用 */
    fun sample(context: Context): List<CacheUsage> {
        val declared = StorageRegistry.entries.map { entry -> entry to entry.resolve(context) }
        val claimedPaths = declared.flatMap { (_, files) -> files.map { it.absolutePath } }.toSet()
        val unclaimed = unclaimedByCategory(context, claimedPaths)
        return buildList {
            declared.forEach { (entry, files) ->
                val (fileCount, sizeBytes) = measureAll(files)
                add(CacheUsage(entry.category, entry.scope, fileCount, sizeBytes))
            }
            // 兜底项排在末尾
            unclaimed.forEach { (category, bucket) ->
                val (fileCount, sizeBytes) = measureAll(bucket.files)
                add(CacheUsage(category, bucket.scope, fileCount, sizeBytes))
            }
        }
    }

    /** 冷启动回收残留，返回释放字节数 */
    fun reclaimOnColdStart(context: Context): Long {
        var freedBytes = 0L
        StorageRegistry.tempFiles(context).forEach { file -> freedBytes += deleteQuietly(file) }
        val now = System.currentTimeMillis()
        StorageRegistry.updatePackages(context)
            .filter { now - it.lastModified() > STALE_UPDATE_PACKAGE_MS }
            .forEach { file -> freedBytes += deleteQuietly(file) }
        return freedBytes
    }

    /**
     * 退出前回收封面中转；不动歌词目录
     * 删除为阻塞 IO，须在 IO 线程调用
     */
    fun reclaimOnExit(context: Context) {
        CurrentCoverCache.reclaimTempResidue(context, TEMP_RESIDUE_GRACE_MS)
        LargeCoverStore.reclaimTempResidue(context, TEMP_RESIDUE_GRACE_MS)
    }

    /** 整清 cacheDir，并手动删日志与安装包 */
    suspend fun clearSystemCache(context: Context) {
        // 整清 cacheDir；回写中转在 filesDir
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        // 日志与安装包系统 API 覆盖不到，手动删
        StorageRegistry.logFiles(context).forEach { deleteQuietly(it) }
        StorageRegistry.updatePackages(context).forEach { deleteQuietly(it) }
    }

    // 落点根按绝对路径去重，避免同一目录重复统计
    private fun effectiveRoots(context: Context): List<Pair<CacheRoot, File>> {
        val seen = mutableSetOf<String>()
        return StorageRegistry.roots.mapNotNull { root ->
            val dir = root.resolve(context) ?: return@mapNotNull null
            if (seen.add(dir.absolutePath)) root to dir else null
        }
    }

    // 未被主张的产出计入兜底；按路径判定，不按名字
    private fun unclaimedByCategory(context: Context, claimedPaths: Set<String>): Map<CacheCategory, Unclaimed> {
        val byCategory = LinkedHashMap<CacheCategory, Unclaimed>()
        effectiveRoots(context).forEach { (root, dir) ->
            val bucket = byCategory.getOrPut(root.category) { Unclaimed(root.scope) }
            collectUnclaimedIn(dir, claimedPaths) { bucket.files += it }
        }
        return byCategory
    }

    private fun collectUnclaimedIn(dir: File, claimedPaths: Set<String>, onUnclaimed: (File) -> Unit) {
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        children.forEach { child ->
            val path = child.absolutePath
            when {
                // 已被主张，整棵剪掉
                path in claimedPaths -> Unit
                // 部分主张则下钻，剩多少记多少
                claimedPaths.any { it.startsWith(path + File.separator) } ->
                    collectUnclaimedIn(child, claimedPaths, onUnclaimed)
                // 无人主张，整份计入兜底
                else -> onUnclaimed(child)
            }
        }
    }

    private fun measureAll(locations: List<File>): Pair<Int, Long> {
        var fileCount = 0
        var sizeBytes = 0L
        locations.forEach { location ->
            val (count, size) = measure(location)
            fileCount += count
            sizeBytes += size
        }
        return fileCount to sizeBytes
    }

    // 不可读按 0 计，不中断调用方
    private fun measure(location: File): Pair<Int, Long> {
        if (location.isFile) return 1 to location.length()
        val children = runCatching { location.listFiles() }.getOrNull() ?: return 0 to 0L
        var fileCount = 0
        var sizeBytes = 0L
        children.forEach { child ->
            val (count, size) = measure(child)
            fileCount += count
            sizeBytes += size
        }
        return fileCount to sizeBytes
    }

    // 回收失败不抛异常，按 0 计
    private fun deleteQuietly(file: File): Long {
        val size = file.length()
        return if (runCatching { file.delete() }.getOrDefault(false)) size else 0L
    }

    private class Unclaimed(val scope: CacheScope, val files: MutableList<File> = mutableListOf())
}