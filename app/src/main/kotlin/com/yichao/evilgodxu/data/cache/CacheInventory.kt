package com.yichao.evilgodxu.data.cache

import android.content.Context
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.LargeCoverStore
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import java.io.File

/**
 * 缓存产出台账：按 [StorageRegistry] 遍历各类产出的当前占用，并提供回收。
 *
 * 完备性不依赖登记的完整性：登记条目之外还会逐个落点根走一遍未被任何条目主张的产出，
 * 整份计入该根的兜底分类。于是新模块漏登记只表现为兜底行非空，不会出现占用去向不明却查不到的产出。
 */
internal object CacheInventory {

    // 更新安装包的过期判定：经系统下载管理器写入，本进程结束后其下载仍可能继续，
    // 故只回收隔日残留，避免删掉正在下载或待安装的包
    private const val STALE_UPDATE_PACKAGE_MS = 24 * 60 * 60 * 1000L

    // 中转残留的回收宽限期：只回收静置期外的 .tmp，避开此刻正在进行的写入（写一张封面只需毫秒级）
    private const val TEMP_RESIDUE_GRACE_MS = 60_000L

    /** 扫描全部登记条目与落点根，产出各类缓存的当前占用。目录遍历为阻塞 IO，必须在 IO 线程调用 */
    fun sample(context: Context): List<CacheUsage> {
        val declared = StorageRegistry.entries.map { entry -> entry to entry.resolve(context) }
        val claimedPaths = declared.flatMap { (_, files) -> files.map { it.absolutePath } }.toSet()
        val unclaimed = unclaimedByCategory(context, claimedPaths)
        return buildList {
            declared.forEach { (entry, files) ->
                val (fileCount, sizeBytes) = measureAll(files)
                add(CacheUsage(entry.category, entry.scope, fileCount, sizeBytes))
            }
            // 兜底项排在末尾：同一张卡内具名条目在前，兜底行在后
            unclaimed.forEach { (category, bucket) ->
                val (fileCount, sizeBytes) = measureAll(bucket.files)
                add(CacheUsage(category, bucket.scope, fileCount, sizeBytes))
            }
        }
    }

    /**
     * 冷启动回收：清理上一次进程遗留的中转文件与过期安装包。
     *
     * 中转文件不做时间判定即可回收 —— 启动时刻不可能有本次下载在进行，同前缀文件必然是
     * 异常中断的残留，无需等常规兜底窗口；安装包因下载可能在本进程之外继续而只回收隔日残留。
     *
     * @return 释放的字节数
     */
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
     * 退出前回收孤儿产出：只处理「引用可判定、内容可重建」的产出，用户数据一律不碰。
     * 遍历与删除均为阻塞 IO，必须在 IO 线程调用。
     *
     * - **歌词缓存**：无任何曲目引用的文件。引用集由调用方给出（见 MusicPlaybackState.lyricCacheReferences）；
     *   传入 null 表示此刻判不出全量引用，整体跳过 —— 引用集不全时判「无引用」等于判「全删」。
     * - **封面落盘目录的中转残留**：写入被打断留下的 `.tmp`，不属于任何曲目，也没有入口会读。
     *
     * 不在此列的三类各有归属：在线音频缓存与歌单等用户数据只由用户显式删除；运行日志要留给用户分享；
     * 待安装的更新包可能正等安装 —— 它们的残留由 [reclaimOnColdStart] 按各自判据处理。
     */
    fun reclaimOnExit(context: Context, lyricReferences: Set<String>?) {
        lyricReferences?.let { MusicMetadataCache.cleanupOrphanedMetadata(context, it) }
        CurrentCoverCache.reclaimTempResidue(context, TEMP_RESIDUE_GRACE_MS)
        LargeCoverStore.reclaimTempResidue(context, TEMP_RESIDUE_GRACE_MS)
    }

    /**
     * 清理应用自身缓存：与系统设置页「清除缓存」的作用域一致 —— 整清 cacheDir，
     * 从而自动覆盖任何第三方库落盘在 cacheDir 的缓存，无需逐个登记。
     * 系统缓存作用域不覆盖的应用专属外部产出（运行日志、更新安装包）在此手动删除。
     */
    suspend fun clearSystemCache(context: Context) {
        // 整清 cacheDir：登记在此的产出（封面落盘缓存、中转文件等）无需逐条驱动，一并回收；
        // 旧版本 Coil 磁盘缓存遗留的目录也落在其中，随这一次清扫一并消失
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        // 系统缓存作用域外、应用专属外部的产出（运行日志、更新安装包），系统 API 覆盖不到，手动删除
        StorageRegistry.logFiles(context).forEach { deleteQuietly(it) }
        StorageRegistry.updatePackages(context).forEach { deleteQuietly(it) }
    }

    // 落点根：解析成实际目录并按绝对路径去重 —— 公共下载目录不可用时 mediaRoot 会回退到
    // 应用专属外部目录，二者是同一处，重复统计会让同一份产出出现在两张卡上
    private fun effectiveRoots(context: Context): List<Pair<CacheRoot, File>> {
        val seen = mutableSetOf<String>()
        return StorageRegistry.roots.mapNotNull { root ->
            val dir = root.resolve(context) ?: return@mapNotNull null
            if (seen.add(dir.absolutePath)) root to dir else null
        }
    }

    // 未主张产出：自每个落点根向下走，命中已主张落点即剪枝，其余整份计入该根的兜底分类。
    // 判定只用「路径包含」而不做名字比对 —— 按名豁免会在跨目录时把同名文件误判为已登记
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
                // 已被某条目主张：整棵剪掉，这份占用由该条目负责
                path in claimedPaths -> Unit
                // 只被部分主张（条目落在更深一层，如安装包与日志文件）：继续下钻，剩多少记多少
                claimedPaths.any { it.startsWith(path + File.separator) } ->
                    collectUnclaimedIn(child, claimedPaths, onUnclaimed)
                // 无人主张：整份计入兜底，不再下钻
                else -> onUnclaimed(child)
            }
        }
    }

    // 统计一组落点：逐个累加文件数与字节数
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

    // 统计单个落点：目录递归累加、文件直接计入；不可读时按 0 计，统计失败不应中断调用方
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

    // 删除并返回释放字节数；失败按 0 计，回收失败不抛异常
    private fun deleteQuietly(file: File): Long {
        val size = file.length()
        return if (runCatching { file.delete() }.getOrDefault(false)) size else 0L
    }

    // 单个兜底分类下收集到的未主张落点
    private class Unclaimed(val scope: CacheScope, val files: MutableList<File> = mutableListOf())
}