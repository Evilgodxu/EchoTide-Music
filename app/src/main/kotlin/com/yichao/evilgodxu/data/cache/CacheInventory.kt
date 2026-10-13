package com.yichao.evilgodxu.data.cache

import android.content.Context
import android.os.Environment
import android.util.Log
import coil3.SingletonImageLoader
import com.yichao.evilgodxu.data.music.clip.SpectrumImageSharing
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.LargeCoverStore
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File

/**
 * 缓存分类：产出统计与设置页展示共同的归类依据。
 * 用户数据按「每一份落盘存储」各占一类 —— 合成一类只看得见一个合计，看不出各项各自有多少条目；
 * 分类新增后必须同步补全 [CacheInventory] 中对应的路径解析，否则该类占用不会被统计到
 * （未登记的持久化文件由 [CacheCategory.OTHER_PERSISTED] 兜住，使漏计至少成为可见的一行）。
 */
enum class CacheCategory {
    /** 网络图片磁盘缓存 */
    IMAGE,

    /** 中转文件：下载与更新过程的临时产物，流程结束即删 */
    TEMP_FILE,

    /** 运行日志：崩溃、异常与诊断信息 */
    LOG,

    /** 歌词展示缓存 */
    LYRIC,

    /** 歌曲缓存：播放在线曲目落盘的音频，属用户数据 */
    AUDIO,

    /** 更新安装包 */
    UPDATE_PACKAGE,

    /** 自定义歌单 */
    PLAYLIST,

    /** 歌手页曲序 */
    ARTIST_ORDER,

    /** 代理音源 */
    PROXY_SOURCE,

    /** 最近播放记录 */
    RECENT_PLAYED,

    /** 搜索历史 */
    SEARCH_HISTORY,

    /** 播放列表缓存：上次播放队列的快照 */
    PLAYLIST_CACHE,

    /** 启动镜像：冷启动首帧所需的播放现场镜像 */
    BOOT_MIRROR,

    /** 启动语言 */
    BOOT_LANGUAGE,

    /** 待更新信息 */
    UPDATE_INFO,

    /** 设置与播放状态：主题、语言、背景取色与续播现场等 */
    SETTINGS,

    /** 心动高亮段：心动模式的分析结果 */
    HIGHLIGHT,

    /** 缓存回收记录：孤儿缓存的回收窗口台账 */
    RECLAIM_RECORD,

    /** 其它持久化数据：上面未登记的落盘文件，兜底计入 */
    OTHER_PERSISTED,
}

/**
 * 缓存归属范围：按产出归属方划分，是缓存页分卡的依据，也界定「清理缓存」的作用边界。
 *
 * [CLEARABLE] 系统缓存：应用自身可随时回收的运行副产物（cacheDir 内产出、运行日志、更新安装包），
 *              用户可经「清理缓存」整批删除；
 * [APP_DATA] 应用数据：应用自身运行产生的副产物，按各自保留策略回收，不由「清理缓存」整批删除；
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

    /** 中转文件前缀：与 File.createTempFile 的 prefix 参数及频谱图分享中转文件名的前缀对应 */
    val TEMP_FILE_PREFIXES = listOf(
        "download",
        "upgrade",
        "metadata",
        SpectrumImageSharing.SHARE_FILE_PREFIX,
    )

    private const val TAG = "CacheInventory"

    // 更新安装包的过期判定：经系统下载管理器写入，本进程结束后其下载仍可能继续，
    // 故只回收隔日残留，避免删掉正在下载或待安装的包
    private const val STALE_UPDATE_PACKAGE_MS = 24 * 60 * 60 * 1000L

    // DataStore 文件目录名：位于 filesDir 下，由 preferencesDataStore 按名生成
    private const val DATA_STORE_DIR = "datastore"

    // SharedPreferences 文件目录名：位于应用数据根目录下，由框架按名写入 XML
    private const val SHARED_PREFS_DIR = "shared_prefs"

    // 持久化用户数据的存储名：由框架按名生成文件名（prefs 为 <名>.xml、DataStore 为 <名>.preferences_pb），
    // 这些名字是各存储的唯一标识，与各存储自身的常量一一对应
    private const val PREFS_PLAYLISTS = "music_playlists_preferences"
    private const val PREFS_ARTIST_ORDER = "music_artist_order_preferences"
    private const val PREFS_PROXY_SOURCES = "proxy_sources"
    private const val PREFS_RECENT_PLAYED = "music_recent_played_preferences"
    private const val PREFS_SEARCH_HISTORY = "music_search_history_preferences"
    private const val PREFS_PLAYLIST_CACHE = "music_playlist_cache_preferences"
    private const val PREFS_BOOT_MIRROR = "music_boot_mirror_preferences"
    private const val PREFS_BOOT_LANGUAGE = "app_language_boot"
    private const val PREFS_UPDATE_INFO = "update_prefs"
    private const val DATA_STORE_SETTINGS = "settings"

    // filesDir 直下的用户数据文件：不经框架存储，由各自的读写方按名维护
    private const val FILE_HIGHLIGHT = "highlight_segments.json"
    private const val FILE_RECLAIM_RECORD = "orphan_reclaim.json"

    // 已登记的持久化文件名：供「其它持久化数据」兜底扣除，新增登记项时须一并补入
    private val REGISTERED_PERSISTED_NAMES = setOf(
        "$PREFS_PLAYLISTS.xml",
        "$PREFS_ARTIST_ORDER.xml",
        "$PREFS_PROXY_SOURCES.xml",
        "$PREFS_RECENT_PLAYED.xml",
        "$PREFS_SEARCH_HISTORY.xml",
        "$PREFS_PLAYLIST_CACHE.xml",
        "$PREFS_BOOT_MIRROR.xml",
        "$PREFS_BOOT_LANGUAGE.xml",
        "$PREFS_UPDATE_INFO.xml",
        "$DATA_STORE_SETTINGS.preferences_pb",
        FILE_HIGHLIGHT,
        FILE_RECLAIM_RECORD,
    )

    private val ENTRIES: List<CacheEntry> = listOf(
        // 图片缓存：Coil 磁盘缓存（上限 32MB，超出由 LRU 淘汰）、当前曲目封面落盘缓存（换歌即覆盖，只留一张）
        // 与首页大封面落盘缓存（保留上一/当前/下一三张）；
        // 清理随 clearSystemCache 整清 cacheDir 完成（Coil 部分须先经其接口以保持索引一致）
        CacheEntry(
            category = CacheCategory.IMAGE,
            scope = CacheScope.CLEARABLE,
            resolve = { context ->
                listOf(
                    File(context.cacheDir, IMAGE_CACHE_DIR_NAME),
                    CurrentCoverCache.location(context),
                    LargeCoverStore.location(context),
                )
            },
        ),
        // 中转文件：流程结束即删，进程异常中断的残留由冷启动回收
        CacheEntry(
            category = CacheCategory.TEMP_FILE,
            scope = CacheScope.CLEARABLE,
            resolve = { context -> tempFiles(context) },
        ),
        // 运行日志：仅保留今日，写入时顺带清理旧文件；清理作用域内由用户整批删除（逐文件删，目录保留）
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
        // 以下各项为持久化的用户数据：逐份存储各占一类，逐项与合计都能对上。
        // 存储名与各存储自身的常量一一对应（见各项读写处），改名须两处同步
        CacheEntry(CacheCategory.PLAYLIST, CacheScope.USER_DATA) { context -> sharedPreferencesFile(context, PREFS_PLAYLISTS) },
        CacheEntry(CacheCategory.ARTIST_ORDER, CacheScope.USER_DATA) { context -> sharedPreferencesFile(context, PREFS_ARTIST_ORDER) },
        CacheEntry(CacheCategory.PROXY_SOURCE, CacheScope.USER_DATA) { context -> sharedPreferencesFile(context, PREFS_PROXY_SOURCES) },
        CacheEntry(CacheCategory.RECENT_PLAYED, CacheScope.USER_DATA) { context -> sharedPreferencesFile(context, PREFS_RECENT_PLAYED) },
        CacheEntry(CacheCategory.SEARCH_HISTORY, CacheScope.USER_DATA) { context -> sharedPreferencesFile(context, PREFS_SEARCH_HISTORY) },
        CacheEntry(CacheCategory.PLAYLIST_CACHE, CacheScope.USER_DATA) { context -> sharedPreferencesFile(context, PREFS_PLAYLIST_CACHE) },
        CacheEntry(CacheCategory.BOOT_MIRROR, CacheScope.USER_DATA) { context -> sharedPreferencesFile(context, PREFS_BOOT_MIRROR) },
        CacheEntry(CacheCategory.BOOT_LANGUAGE, CacheScope.USER_DATA) { context -> sharedPreferencesFile(context, PREFS_BOOT_LANGUAGE) },
        CacheEntry(CacheCategory.UPDATE_INFO, CacheScope.USER_DATA) { context -> sharedPreferencesFile(context, PREFS_UPDATE_INFO) },
        // 设置与播放状态单文件承载设置项、保存的背景取色与续播现场
        CacheEntry(CacheCategory.SETTINGS, CacheScope.USER_DATA) { context -> dataStoreFile(context, DATA_STORE_SETTINGS) },
        // 心动高亮段：心动模式的分析结果，重建要重扫全库，属用户数据
        CacheEntry(CacheCategory.HIGHLIGHT, CacheScope.USER_DATA) { context -> listOf(File(context.filesDir, FILE_HIGHLIGHT)) },
        // 其它持久化数据：上面未登记的落盘文件，兜底计入
        CacheEntry(CacheCategory.OTHER_PERSISTED, CacheScope.USER_DATA) { context -> unregisteredPersistedFiles(context) },
        // 缓存回收记录：孤儿缓存的回收窗口台账，由台账自身按保留策略重建，不是用户数据
        CacheEntry(CacheCategory.RECLAIM_RECORD, CacheScope.APP_DATA) { context -> listOf(File(context.filesDir, FILE_RECLAIM_RECORD)) },
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
     * 系统缓存作用域不覆盖的应用专属外部产出（运行日志、更新安装包）在此手动删除。
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
        // 系统缓存作用域外、应用专属外部的产出（运行日志、更新安装包），系统 API 覆盖不到，手动删除
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

    // SharedPreferences 落点：框架按存储名生成 `<名>.xml`，落在应用数据根目录下的 shared_prefs。
    // 未写入过该存储时文件不存在，按 0 计
    private fun sharedPreferencesFile(context: Context, name: String): List<File> =
        listOfNotNull(
            context.filesDir.parentFile?.let { File(File(it, SHARED_PREFS_DIR), "$name.xml") },
        )

    // DataStore 落点：框架按名生成 `<名>.preferences_pb`，落在 filesDir/datastore
    private fun dataStoreFile(context: Context, name: String): List<File> =
        listOf(File(File(context.filesDir, DATA_STORE_DIR), "$name.preferences_pb"))

    // 其它持久化数据：登记项之外仍落在持久化目录里的文件 —— 新增存储忘了登记、或框架自身写入的
    // 偏好文件都归此列。逐项登记后本行通常为空；它非空即说明有新产出未被归类，不该当成噪声略过
    private fun unregisteredPersistedFiles(context: Context): List<File> {
        val sharedPrefs = context.filesDir.parentFile?.let { File(it, SHARED_PREFS_DIR) }
        return buildList {
            addAll(unregisteredFilesIn(sharedPrefs))
            addAll(unregisteredFilesIn(File(context.filesDir, DATA_STORE_DIR)))
            // filesDir 直下：datastore 目录已由其内的文件登记，其余未登记项整份计入（含未登记的子目录）
            addAll(
                context.filesDir.listFiles { child ->
                    child.name != DATA_STORE_DIR && child.name !in REGISTERED_PERSISTED_NAMES
                }?.toList().orEmpty(),
            )
        }
    }

    // 目录下未登记的文件；目录缺席或不可读时按没有计，统计失败不应中断调用方
    private fun unregisteredFilesIn(directory: File?): List<File> =
        directory?.listFiles { file ->
            file.isFile && file.name !in REGISTERED_PERSISTED_NAMES
        }?.toList().orEmpty()

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
