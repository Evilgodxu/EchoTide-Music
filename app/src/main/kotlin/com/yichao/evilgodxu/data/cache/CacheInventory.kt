package com.yichao.evilgodxu.data.cache

import android.content.Context
import android.os.Environment
import android.util.Log
import coil3.SingletonImageLoader
import com.yichao.evilgodxu.data.music.analysis.TrackVerdictCache
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File

/**
 * 缓存分类：产出统计与设置页展示共同的归类依据。
 * 分类新增后必须同步补全 [CacheInventory] 中对应的路径解析，否则该类占用不会被统计到。
 */
enum class CacheCategory {
    /** 网络图片磁盘缓存 */
    IMAGE,

    /** 下载与更新过程中转的临时文件 */
    TEMP_FILE,

    /** 崩溃与异常日志 */
    LOG,

    /** 封面展示缓存 */
    COVER,

    /** 歌词展示缓存 */
    LYRIC,

    /** 在线歌曲缓存，属用户数据 */
    AUDIO,

    /** 更新安装包 */
    UPDATE_PACKAGE,

    /** 曲目判定结果缓存 */
    ANALYSIS,
}

/**
 * 缓存归属范围：决定回收边界，是「哪些能清、哪些不能清」的唯一判据。
 *
 * [SYSTEM_CACHE] 位于 cacheDir，属系统「清除缓存」作用域，应用可随时整体回收；
 * [PRIVATE] 位于应用专属目录，仅在各自保留策略下回收；
 * [USER_VISIBLE] 位于公共下载目录，是用户可见数据，不得由「清理缓存」批量删除。
 */
enum class CacheScope { SYSTEM_CACHE, PRIVATE, USER_VISIBLE }

/** 单类缓存的产出占用 */
data class CacheUsage(
    val category: CacheCategory,
    val scope: CacheScope,
    val fileCount: Int,
    val sizeBytes: Long,
)

// 台账条目：一类缓存的归属范围、落点解析与清理方式。
// 保留策略与上限写在各条目上方的注释里；上限取值由使用方直接引用常量，不在此再存一份
private class CacheEntry(
    val category: CacheCategory,
    val scope: CacheScope,
    val resolve: (Context) -> List<File>,
    // 有专属清理接口的缓存在此提供（如 LRU 缓存须经其接口清除），为空则按落点删文件
    val clear: (suspend (Context) -> Unit)? = null,
)

/**
 * 缓存产出台账：集中登记应用写盘的每一类缓存，并据此提供占用统计与回收。
 *
 * 不变量：任何落盘的缓存都必须在此登记分类、归属范围与回收方式，未登记的产出视为失控。
 * 落点不在此处自行拼装路径 —— 一律引用各缓存管理者的路径入口，避免同一路径出现第二份事实。
 */
internal object CacheInventory {

    /** 图片磁盘缓存目录名：与 Coil 磁盘缓存配置共用同一常量，改名会使既有缓存变为无人认领的孤儿 */
    const val IMAGE_CACHE_DIR_NAME = "image_cache"

    /** 图片磁盘缓存上限：约可容纳百张 2048 长边封面，超出由 LRU 淘汰，不随可用空间无界增长 */
    const val IMAGE_DISK_CACHE_MAX_BYTES = 32L * 1024 * 1024

    /** 在线歌曲缓存目录名：与公共下载目录下音频条目的落点一致 */
    const val AUDIO_DIR_NAME = "Audio"

    /** 下载与更新中转文件前缀：与 File.createTempFile 的 prefix 参数对应 */
    val TEMP_FILE_PREFIXES = listOf("download", "upgrade")

    private const val TAG = "CacheInventory"

    // 更新安装包的过期判定：经系统下载管理器写入，本进程结束后其下载仍可能继续，
    // 故只回收隔日残留，避免删掉正在下载或待安装的包
    private const val STALE_UPDATE_PACKAGE_MS = 24 * 60 * 60 * 1000L

    private val ENTRIES: List<CacheEntry> = listOf(
        // 图片缓存：上限 32MB，超出由 LRU 淘汰；清理须经 Coil 接口，直删文件会与其索引失配
        CacheEntry(
            category = CacheCategory.IMAGE,
            scope = CacheScope.SYSTEM_CACHE,
            resolve = { context -> listOf(File(context.cacheDir, IMAGE_CACHE_DIR_NAME)) },
            clear = { context -> SingletonImageLoader.get(context).diskCache?.clear() },
        ),
        // 下载中转文件：流程结束即删，进程异常中断的残留由冷启动回收
        CacheEntry(
            category = CacheCategory.TEMP_FILE,
            scope = CacheScope.SYSTEM_CACHE,
            resolve = { context -> tempFiles(context) },
        ),
        // 异常日志：仅保留今日，写入时顺带清理旧文件
        CacheEntry(
            category = CacheCategory.LOG,
            scope = CacheScope.PRIVATE,
            resolve = { context -> listOf(CrashLogManager.logDirectory(context)) },
        ),
        // 封面缓存：孤儿回收，连续 3 天无引用后删除
        CacheEntry(
            category = CacheCategory.COVER,
            scope = CacheScope.USER_VISIBLE,
            resolve = { context -> metadataLocations(context, MusicMetadataCache.coverRoot(context)) },
        ),
        // 歌词缓存：孤儿回收，连续 3 天无引用后删除
        CacheEntry(
            category = CacheCategory.LYRIC,
            scope = CacheScope.USER_VISIBLE,
            resolve = { context -> metadataLocations(context, MusicMetadataCache.lyricRoot(context)) },
        ),
        // 在线歌曲缓存：属用户数据，随曲目删除，应用不自动回收
        CacheEntry(
            category = CacheCategory.AUDIO,
            scope = CacheScope.USER_VISIBLE,
            resolve = { context -> listOf(File(MusicMetadataCache.mediaRoot(context), AUDIO_DIR_NAME)) },
        ),
        // 更新安装包：校验后即删，隔日残留由冷启动回收
        CacheEntry(
            category = CacheCategory.UPDATE_PACKAGE,
            scope = CacheScope.PRIVATE,
            resolve = { context -> updatePackages(context) },
        ),
        // 判定结果缓存：识别策略升级或用户主动刷新时整体清空
        CacheEntry(
            category = CacheCategory.ANALYSIS,
            scope = CacheScope.PRIVATE,
            resolve = { context -> verdictCaches(context) },
        ),
    )

    /** 扫描全部登记项，产出各类缓存的当前占用。目录遍历为阻塞 IO，必须在 IO 线程调用 */
    fun sample(context: Context): List<CacheUsage> = ENTRIES.map { entry ->
        var fileCount = 0
        var sizeBytes = 0L
        entry.resolve(context).forEach { location ->
            val (count, size) = measure(location)
            fileCount += count
            sizeBytes += size
        }
        CacheUsage(entry.category, entry.scope, fileCount, sizeBytes)
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
        tempFiles(context).forEach { file -> freedBytes += deleteQuietly(file) }
        val now = System.currentTimeMillis()
        updatePackages(context)
            .filter { now - it.lastModified() > STALE_UPDATE_PACKAGE_MS }
            .forEach { file -> freedBytes += deleteQuietly(file) }
        if (freedBytes > 0) Log.i(TAG, "冷启动回收临时产出 释放=$freedBytes B")
        return freedBytes
    }

    /**
     * 清理系统缓存作用域：只作用于 cacheDir，与系统设置页「清除缓存」的作用范围一致。
     * 公共下载目录下的封面/歌词/音频属用户可见数据，各自有回收策略，不在此列。
     */
    suspend fun clearSystemCache(context: Context) {
        ENTRIES.filter { it.scope == CacheScope.SYSTEM_CACHE }.forEach { entry ->
            val clear = entry.clear
            if (clear != null) {
                runCatching { clear(context) }.onFailure {
                    CrashLogManager.logException(TAG, "清理缓存失败: ${entry.category}", it)
                }
            } else {
                entry.resolve(context).forEach { deleteQuietly(it) }
            }
        }
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

    // cacheDir 下的中转文件：按前缀匹配，仅认文件
    private fun tempFiles(context: Context): List<File> = context.cacheDir
        .listFiles { file -> file.isFile && TEMP_FILE_PREFIXES.any { file.name.startsWith(it) } }
        ?.toList()
        .orEmpty()

    // 应用专属下载目录下的更新安装包：该目录仅由本应用写入，按扩展名匹配即可
    private fun updatePackages(context: Context): List<File> = context
        .getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?.listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
        ?.toList()
        .orEmpty()

    // 判定缓存落点：含写入中断可能留下的 .tmp 中间文件
    private fun verdictCaches(context: Context): List<File> = TrackVerdictCache.KNOWN_FILE_NAMES
        .flatMap { name -> listOf(File(context.filesDir, name), File(context.filesDir, "$name.tmp")) }

    // 元数据缓存落点：公共下载目录与应用专属目录兜底两处，路径重合时只保留一处
    private fun metadataLocations(context: Context, primary: File): List<File> {
        val fallback = context.getExternalFilesDir(null)?.let { File(it, primary.name) }
        return listOfNotNull(primary, fallback).distinctBy { it.absolutePath }
    }

    // 删除并返回释放字节数；失败按 0 计，回收失败不抛异常
    private fun deleteQuietly(file: File): Long {
        val size = file.length()
        return if (runCatching { file.delete() }.getOrDefault(false)) size else 0L
    }
}
