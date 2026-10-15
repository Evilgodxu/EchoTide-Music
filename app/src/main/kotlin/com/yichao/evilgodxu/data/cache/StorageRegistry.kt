package com.yichao.evilgodxu.data.cache

import android.content.Context
import android.os.Environment
import com.yichao.evilgodxu.data.music.highlight.HighlightStore
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.LargeCoverStore
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.proxy.ProxySourceStore
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.StoragePaths
import com.yichao.evilgodxu.data.playlist.ArtistOrderStore
import com.yichao.evilgodxu.data.playlist.PlaylistStore
import com.yichao.evilgodxu.data.settings.BOOT_LANGUAGE_STORE
import com.yichao.evilgodxu.data.settings.SETTINGS_STORE
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.update.UpdateManager
import java.io.File

/**
 * 缓存分类：产出统计与存储页展示共同的归类依据，一个分类对应存储页的一行。
 *
 * 具名持久化数据按「每一份落盘存储」各占一类 —— 合成一类只看得见一个合计，看不出各项各自有多少条目；
 * 其归类在 [StorageRegistry] 登记。末尾三项不是人工登记的具名数据，而是由落点根兜出来的未主张产出，
 * 漏登记只表现为它们非空（并落一条带具体路径的日志），不会静默不计。
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

    /** 其它缓存：缓存目录内未被任何条目主张的产出，随「清理缓存」一并回收 */
    OTHER_CACHE,

    /** 其它应用数据：应用专属外部目录内未被任何条目主张的产出 */
    OTHER_APP_DATA,

    /** 其它持久化数据：私有目录与公共下载目录内未被任何条目主张的产出 */
    OTHER_PERSISTED,
}

/**
 * 缓存归属范围：按产出归属方划分，是存储页分卡的依据，也界定「清理缓存」的作用边界。
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

// 登记条目：一份产出的归类、归属与落点解析。
// 落点不在此自行拼装路径 —— 一律引用产出方自己的路径入口（或模块自陈的 PersistedData），
// 避免同一路径出现第二份事实。清理不在此逐条驱动（见 CacheInventory.clearSystemCache）。
internal class CacheEntry(
    val category: CacheCategory,
    val scope: CacheScope,
    /** 条目对应的具名持久化数据声明；目录类产出没有「名字」，为 null */
    val persisted: PersistedData? = null,
    val resolve: (Context) -> List<File>,
)

// 落点根：兜底的边界。根内未被任何条目主张的产出整份计入 [category]，
// 于是新模块即便漏登记，其占用也只表现为兜底行非空，不会静默消失。
internal class CacheRoot(
    val category: CacheCategory,
    val scope: CacheScope,
    val resolve: (Context) -> File?,
)

/**
 * 缓存产出登记表：应用写盘的每一类产出在此登记归类、归属与落点解析，
 * 存储页按本表枚举统计（见 [CacheInventory.sample]）。
 *
 * 不变量：
 * 1. 任何落盘的产出都必须在 [entries] 登记，或落在某个 [roots] 之内（由兜底行接住）。
 * 2. [entries] 之间的落点不得嵌套 —— 嵌套会让同一份产出被两条各算一次，合计随之偏大。
 *
 * 具名持久化数据的名字不在此抄写：一律引用各存储模块自陈的 [PersistedData]，
 * 改名只需改模块那一处，读写与统计同步跟着变。
 */
internal object StorageRegistry {

    /** 登记条目：顺序即存储页展示顺序 */
    val entries: List<CacheEntry> = listOf(
        // 图片缓存：Coil 磁盘缓存（上限见 StoragePaths，超出由 LRU 淘汰）、当前曲目封面落盘缓存
        // （换歌即覆盖，只留一张）与首页大封面落盘缓存（保留上一/当前/下一三张）；
        // 清理随 clearSystemCache 整清 cacheDir 完成（Coil 部分须先经其接口以保持索引一致）
        CacheEntry(CacheCategory.IMAGE, CacheScope.CLEARABLE) { context ->
            listOf(
                File(context.cacheDir, StoragePaths.IMAGE_CACHE_DIR_NAME),
                CurrentCoverCache.location(context),
                LargeCoverStore.location(context),
            )
        },
        // 中转文件：流程结束即删，进程异常中断的残留由冷启动回收
        CacheEntry(CacheCategory.TEMP_FILE, CacheScope.CLEARABLE) { context -> tempFiles(context) },
        // 运行日志：仅保留今日，写入时顺带清理旧文件；清理作用域内由用户整批删除（逐文件删，目录保留）
        CacheEntry(CacheCategory.LOG, CacheScope.CLEARABLE) { context -> logFiles(context) },
        // 歌词缓存：可由网络或音频文件重建，孤儿回收，连续 3 天无引用后删除
        CacheEntry(CacheCategory.LYRIC, CacheScope.USER_DATA) { context ->
            metadataLocations(context, MusicMetadataCache.lyricRoot(context))
        },
        // 歌曲缓存：播放在线曲目时落盘的音频，随曲目删除，应用不自动回收
        CacheEntry(CacheCategory.AUDIO, CacheScope.USER_DATA) { context ->
            listOf(File(MusicMetadataCache.mediaRoot(context), StoragePaths.AUDIO_DIR_NAME))
        },
        // 更新安装包：校验后即删，隔日残留由冷启动回收；清理作用域内由用户整批删除
        CacheEntry(CacheCategory.UPDATE_PACKAGE, CacheScope.CLEARABLE) { context -> updatePackages(context) },
        // 以下各项为具名持久化数据：逐份存储各占一类，逐项与合计都能对上
        persisted(CacheCategory.PLAYLIST, CacheScope.USER_DATA, PlaylistStore.PERSISTED),
        persisted(CacheCategory.ARTIST_ORDER, CacheScope.USER_DATA, ArtistOrderStore.PERSISTED),
        persisted(CacheCategory.PROXY_SOURCE, CacheScope.USER_DATA, ProxySourceStore.PERSISTED),
        persisted(CacheCategory.RECENT_PLAYED, CacheScope.USER_DATA, MusicPlaybackState.RECENT_PLAYED_STORE),
        persisted(CacheCategory.SEARCH_HISTORY, CacheScope.USER_DATA, MusicPlaybackState.SEARCH_HISTORY_STORE),
        persisted(CacheCategory.PLAYLIST_CACHE, CacheScope.USER_DATA, MusicPlaybackState.PLAYLIST_CACHE_STORE),
        persisted(CacheCategory.BOOT_MIRROR, CacheScope.USER_DATA, MusicPlaybackState.BOOT_MIRROR_STORE),
        persisted(CacheCategory.BOOT_LANGUAGE, CacheScope.USER_DATA, BOOT_LANGUAGE_STORE),
        persisted(CacheCategory.UPDATE_INFO, CacheScope.USER_DATA, UpdateManager.PERSISTED),
        // 设置与播放状态单文件承载设置项、保存的背景取色与续播现场
        persisted(CacheCategory.SETTINGS, CacheScope.USER_DATA, SETTINGS_STORE),
        // 心动高亮段：心动模式的分析结果，重建要重扫全库，属用户数据
        persisted(CacheCategory.HIGHLIGHT, CacheScope.USER_DATA, HighlightStore.PERSISTED),
    )

    /**
     * 落点根：应用可写盘的每个根目录各一条。根内未主张产出落兜底行，
     * 使「漏登记」变成可见的一行而非无声的空间去向不明。
     *
     * 根之间不得相互包含（否则同一份内容会被两个根各算一次兜底）；
     * 唯一可能重合的是公共下载目录与应用专属外部目录 —— 前者不可用时 mediaRoot 会回退到后者，
     * 由 CacheInventory 按绝对路径去重，只留先声明的那条。
     */
    val roots: List<CacheRoot> = listOf(
        CacheRoot(CacheCategory.OTHER_CACHE, CacheScope.CLEARABLE) { context -> context.cacheDir },
        CacheRoot(CacheCategory.OTHER_PERSISTED, CacheScope.USER_DATA) { context -> context.filesDir },
        CacheRoot(CacheCategory.OTHER_PERSISTED, CacheScope.USER_DATA) { context ->
            PersistedData.sharedPrefsDir(context)
        },
        CacheRoot(CacheCategory.OTHER_APP_DATA, CacheScope.APP_DATA) { context -> context.getExternalFilesDir(null) },
        CacheRoot(CacheCategory.OTHER_PERSISTED, CacheScope.USER_DATA) { context ->
            MusicMetadataCache.mediaRoot(context)
        },
    )

    private fun persisted(category: CacheCategory, scope: CacheScope, data: PersistedData) =
        CacheEntry(category, scope, persisted = data) { context -> listOf(data.locate(context)) }

    // cacheDir 下的中转文件：按前缀匹配，仅认文件
    internal fun tempFiles(context: Context): List<File> = context.cacheDir
        .listFiles { file -> file.isFile && StoragePaths.TEMP_FILE_PREFIXES.any { file.name.startsWith(it) } }
        ?.toList()
        .orEmpty()

    // 日志文件：仅认文件；清理时逐文件删除，目录保留以便 CrashLogManager 继续写入
    internal fun logFiles(context: Context): List<File> = CrashLogManager.logDirectory(context)
        .listFiles { file -> file.isFile }
        ?.toList()
        .orEmpty()

    // 应用专属下载目录下的更新安装包：该目录仅由本应用写入，按扩展名匹配即可
    internal fun updatePackages(context: Context): List<File> = context
        .getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?.listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
        ?.toList()
        .orEmpty()

    // 元数据缓存落点：公共下载目录与应用专属目录兜底两处，路径重合时只保留一处
    private fun metadataLocations(context: Context, primary: File): List<File> {
        val fallback = context.getExternalFilesDir(null)?.let { File(it, primary.name) }
        return listOfNotNull(primary, fallback).distinctBy { it.absolutePath }
    }
}