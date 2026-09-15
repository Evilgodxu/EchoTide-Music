package com.yichao.evilgodxu.data.cache

import android.content.Context
import android.os.Environment
import android.util.Log
import coil3.SingletonImageLoader
import com.yichao.evilgodxu.data.music.analysis.TrackVerdictCache
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
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

    /** 中转文件：下载与更新过程的临时产物，流程结束即删 */
    TEMP_FILE,

    /** 崩溃与异常日志 */
    LOG,

    /** 歌词展示缓存 */
    LYRIC,

    /** 歌曲缓存：播放在线曲目落盘的音频，属用户数据 */
    AUDIO,

    /** 更新安装包 */
    UPDATE_PACKAGE,

    /** 曲库分析缓存：对曲库的判定结果，可重算 */
    ANALYSIS,

    /** 用户偏好：DataStore 与 SharedPreferences 中的用户配置、歌单与播放记录，属用户数据 */
    PREFERENCE,
}

/**
 * 缓存归属范围：按产出归属方划分，是缓存页分卡的依据，也界定「清理缓存」的作用边界。
 *
 * [CLEARABLE] 系统缓存：应用自身可随时回收的运行副产物（cacheDir 内产出、异常日志、更新安装包），
 *              用户可经「清理缓存」整批删除；
 * [APP_DATA] 应用数据：应用自身运行产生的副产物，按各自保留策略回收；（当前无登记项，保留以作区分）
 * [USER_DATA] 用户数据：围绕用户曲库与偏好产生的产出，按各自保留策略回收，或只由用户显式删除。
 */
enum class CacheScope { CLEARABLE, APP_DATA, USER_DATA }

/** 单类缓存的产出占用 */
data class CacheUsage(
    val category: CacheCategory,
    val scope: CacheScope,
    val fileCount: Int,
    val sizeBytes: Long,
)

// 台账条目：一类缓存的归属范围与落点解析，供占用统计使用。
// 保留策略与上限写在各条目上方的注释里；上限取值由使用方直接引用常量，不在此再存一份。
// 清理不在此逐条驱动 —— 「清理缓存」按系统作用域整清 cacheDir（见 clearSystemCache），
// 只有活跃缓存（Coil 磁盘缓存）须经其接口保持索引一致。
private class CacheEntry(
    val category: CacheCategory,
    val scope: CacheScope,
    val resolve: (Context) -> List<File>,
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

    /** 歌曲缓存目录名：与公共下载目录下音频条目的落点一致 */
    const val AUDIO_DIR_NAME = "Audio"

    /** 中转文件前缀：与 File.createTempFile 的 prefix 参数对应 */
    val TEMP_FILE_PREFIXES = listOf("download", "upgrade")

    private const val TAG = "CacheInventory"

    // 更新安装包的过期判定：经系统下载管理器写入，本进程结束后其下载仍可能继续，
    // 故只回收隔日残留，避免删掉正在下载或待安装的包
    private const val STALE_UPDATE_PACKAGE_MS = 24 * 60 * 60 * 1000L

    // DataStore 文件目录名：位于 filesDir 下，由 preferencesDataStore 按名生成
    private const val DATA_STORE_DIR = "datastore"

    // SharedPreferences 文件目录名：位于应用数据根目录下，由框架按名写入 XML
    private const val SHARED_PREFS_DIR = "shared_prefs"

    private val ENTRIES: List<CacheEntry> = listOf(
        // 图片缓存：Coil 磁盘缓存（上限 32MB，超出由 LRU 淘汰）与当前曲目封面落盘缓存（换歌即覆盖，只留一张）；
        // 清理随 clearSystemCache 整清 cacheDir 完成（Coil 部分须先经其接口以保持索引一致）
        CacheEntry(
            category = CacheCategory.IMAGE,
            scope = CacheScope.CLEARABLE,
            resolve = { context ->
                listOf(
                    File(context.cacheDir, IMAGE_CACHE_DIR_NAME),
                    CurrentCoverCache.location(context),
                )
            },
        ),
        // 中转文件：流程结束即删，进程异常中断的残留由冷启动回收
        CacheEntry(
            category = CacheCategory.TEMP_FILE,
            scope = CacheScope.CLEARABLE,
            resolve = { context -> tempFiles(context) },
        ),
        // 异常日志：仅保留今日，写入时顺带清理旧文件；清理作用域内由用户整批删除（逐文件删，目录保留）
        CacheEntry(
            category = CacheCategory.LOG,
            scope = CacheScope.CLEARABLE,
            resolve = { context -> logFiles(context) },
        ),
        // 歌词缓存：可由网络或音频文件重建，孤儿回收，连续 3 天无引用后删除
        CacheEntry(
            category = CacheCategory.LYRIC,
            scope = CacheScope.USER_DATA,
            resolve = { context -> metadataLocations(context, MusicMetadataCache.lyricRoot(context)) },
        ),
        // 歌曲缓存：播放在线曲目时落盘的音频，随曲目删除，应用不自动回收
        CacheEntry(
            category = CacheCategory.AUDIO,
            scope = CacheScope.USER_DATA,
            resolve = { context -> listOf(File(MusicMetadataCache.mediaRoot(context), AUDIO_DIR_NAME)) },
        ),
        // 更新安装包：校验后即删，隔日残留由冷启动回收；清理作用域内由用户整批删除
        CacheEntry(
            category = CacheCategory.UPDATE_PACKAGE,
            scope = CacheScope.CLEARABLE,
            resolve = { context -> updatePackages(context) },
        ),
        // 用户偏好：用户配置与播放记录无法重建，应用不自动回收
        CacheEntry(
            category = CacheCategory.PREFERENCE,
            scope = CacheScope.USER_DATA,
            resolve = { context -> preferenceStores(context) },
        ),
        // 曲库分析缓存：识别结果可重算，识别策略升级或用户主动刷新时整体清空
        CacheEntry(
            category = CacheCategory.ANALYSIS,
            scope = CacheScope.USER_DATA,
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
     * 清理应用自身缓存：与系统设置页「清除缓存」的作用域一致 —— 整清 cacheDir，
     * 从而自动覆盖任何第三方库落盘在 cacheDir 的缓存，无需逐个登记。
     * 系统缓存作用域不覆盖的应用专属外部产出（异常日志、更新安装包）在此手动删除。
     * 活跃的 Coil 磁盘缓存须先经其接口清空以保持索引一致。
     */
    suspend fun clearSystemCache(context: Context) {
        // 活跃缓存：Coil 磁盘缓存的 clear() 只删值文件、会遗留 .journal 元数据，须补删使目录真正为空
        runCatching {
            SingletonImageLoader.get(context).diskCache?.clear()
            File(context.cacheDir, IMAGE_CACHE_DIR_NAME)
                .listFiles()?.forEach { it.delete() }
        }.onFailure { CrashLogManager.logException(TAG, "清理图片缓存失败", it) }
        // 清空 cacheDir 其余内容（等同系统「清除缓存」作用域）：跳过 image_cache 目录本身，
        // 其值文件已清空，避免删掉 Coil 仍在使用的目录导致其后续写失效
        context.cacheDir.listFiles()?.forEach { child ->
            if (child.name != IMAGE_CACHE_DIR_NAME) child.deleteRecursively()
        }
        // 系统缓存作用域外、应用专属外部的产出（异常日志、更新安装包），系统 API 覆盖不到，手动删除
        logFiles(context).forEach { deleteQuietly(it) }
        updatePackages(context).forEach { deleteQuietly(it) }
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

    // 日志文件：仅认文件；清理时逐文件删除，目录保留以便 CrashLogManager 继续写入
    private fun logFiles(context: Context): List<File> = CrashLogManager.logDirectory(context)
        .listFiles { file -> file.isFile }
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

    // 偏好落点：两个目录都只由框架写入本应用的偏好文件，按目录整体计入而不逐个登记存储名，
    // 新增偏好存储无需补登记也不会漏计
    private fun preferenceStores(context: Context): List<File> = listOfNotNull(
        File(context.filesDir, DATA_STORE_DIR),
        context.filesDir.parentFile?.let { File(it, SHARED_PREFS_DIR) },
    )

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
