package com.yichao.evilgodxu.data.cache

import android.content.Context
import com.yichao.evilgodxu.data.music.highlight.HIGHLIGHT_STORE
import com.yichao.evilgodxu.data.music.metadata.CurrentCoverCache
import com.yichao.evilgodxu.data.music.metadata.LargeCoverStore
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.playback.BOOT_MIRROR_STORE
import com.yichao.evilgodxu.data.music.playback.PLAYBACK_QUEUE_STORE
import com.yichao.evilgodxu.data.music.playback.RECENT_PLAYED_STORE
import com.yichao.evilgodxu.data.music.playback.SEARCH_HISTORY_STORE
import com.yichao.evilgodxu.data.music.proxy.PROXY_SOURCE_STORE
import com.yichao.evilgodxu.data.persistence.PersistedData
import com.yichao.evilgodxu.data.persistence.StoragePaths
import com.yichao.evilgodxu.data.playlist.ALBUM_ORDER_STORE
import com.yichao.evilgodxu.data.playlist.ARTIST_ORDER_STORE
import com.yichao.evilgodxu.data.playlist.FAVORITE_ORDER_STORE
import com.yichao.evilgodxu.data.playlist.PLAYLISTS_STORE
import com.yichao.evilgodxu.data.settings.SETTINGS_STORE
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.update.UPDATE_STORE
import java.io.File

/** 缓存分类，对应存储页一行；末三项为兜底分类 */
enum class CacheCategory {
    /** 封面缓存：当前曲目与首页大图 */
    IMAGE,

    /** 中转文件，流程结束即删 */
    TEMP_FILE,

    /** 运行日志：崩溃、异常与诊断信息 */
    LOG,

    /** 歌词展示缓存 */
    LYRIC,

    /** 在线曲目落盘音频 */
    AUDIO,

    /** 更新安装包 */
    UPDATE_PACKAGE,

    /** 自定义歌单 */
    PLAYLIST,

    /** 歌手页曲序 */
    ARTIST_ORDER,

    /** 专辑页曲序 */
    ALBUM_ORDER,

    /** 收藏歌单曲序 */
    FAVORITE_ORDER,

    /** 代理音源 */
    PROXY_SOURCE,

    /** 最近播放记录 */
    RECENT_PLAYED,

    /** 搜索历史 */
    SEARCH_HISTORY,

    /** 播放列表缓存：上次播放队列的快照 */
    PLAYLIST_CACHE,

    /** 启动镜像：冷启动首帧播放现场 */
    BOOT_MIRROR,

    /** 待更新信息 */
    UPDATE_INFO,

    /** 设置与播放状态及背景取色 */
    SETTINGS,

    /** 心动高亮段：心动模式的分析结果 */
    HIGHLIGHT,

    /** 其它缓存：未主张产出，随清理一并回收 */
    OTHER_CACHE,

    /** 其它应用数据：专属目录内未主张产出 */
    OTHER_APP_DATA,

    /** 其它持久化：私有与公共目录内未主张产出 */
    OTHER_PERSISTED,
}

/** 归属范围：界分存储页分卡与清理边界 */
enum class CacheScope { CLEARABLE, APP_DATA, USER_DATA }

/** 单类缓存的产出占用 */
data class CacheUsage(
    val category: CacheCategory,
    val scope: CacheScope,
    val fileCount: Int,
    val sizeBytes: Long,
)

// 登记条目：归类、归属与落点；路径引用产出方入口
internal class CacheEntry(
    val category: CacheCategory,
    val scope: CacheScope,
    /** 具名持久化声明；目录类产出为 null */
    val persisted: PersistedData? = null,
    val resolve: (Context) -> List<File>,
)

// 落点根：根内未主张产出计入兜底分类
internal class CacheRoot(
    val category: CacheCategory,
    val scope: CacheScope,
    val resolve: (Context) -> File?,
)

/**
 * 产出登记表：存储页按此枚举统计
 * 落点须登记且不嵌套；名字引用 PersistedData
 */
internal object StorageRegistry {

    /** 登记条目：顺序即存储页展示顺序 */
    val entries: List<CacheEntry> = listOf(
        // 封面缓存：当前曲目一张，首页保留三张
        CacheEntry(CacheCategory.IMAGE, CacheScope.CLEARABLE) { context ->
            listOf(
                CurrentCoverCache.location(context),
                LargeCoverStore.location(context),
            )
        },
        // 中转文件，残留由冷启动回收
        CacheEntry(CacheCategory.TEMP_FILE, CacheScope.CLEARABLE) { context -> tempFiles(context) },
        // 日志仅保留今日，清理时逐文件删
        CacheEntry(CacheCategory.LOG, CacheScope.CLEARABLE) { context -> logFiles(context) },
        // 歌词可重建，仅统计不自动回收
        CacheEntry(CacheCategory.LYRIC, CacheScope.USER_DATA) { context ->
            metadataLocations(context, MusicMetadataCache.lyricRoot(context))
        },
        // 在线音频随曲目删除，不自动回收
        CacheEntry(CacheCategory.AUDIO, CacheScope.USER_DATA) { context ->
            listOf(File(MusicMetadataCache.mediaRoot(context), StoragePaths.AUDIO_DIR_NAME))
        },
        // 安装包校验后即删，隔日残留冷启动回收
        CacheEntry(CacheCategory.UPDATE_PACKAGE, CacheScope.CLEARABLE) { context -> updatePackages(context) },
        // 以下为具名持久化数据
        persisted(CacheCategory.PLAYLIST, CacheScope.USER_DATA, PLAYLISTS_STORE),
        persisted(CacheCategory.ARTIST_ORDER, CacheScope.USER_DATA, ARTIST_ORDER_STORE),
        persisted(CacheCategory.ALBUM_ORDER, CacheScope.USER_DATA, ALBUM_ORDER_STORE),
        persisted(CacheCategory.FAVORITE_ORDER, CacheScope.USER_DATA, FAVORITE_ORDER_STORE),
        persisted(CacheCategory.PROXY_SOURCE, CacheScope.USER_DATA, PROXY_SOURCE_STORE),
        persisted(CacheCategory.RECENT_PLAYED, CacheScope.USER_DATA, RECENT_PLAYED_STORE),
        persisted(CacheCategory.SEARCH_HISTORY, CacheScope.USER_DATA, SEARCH_HISTORY_STORE),
        persisted(CacheCategory.PLAYLIST_CACHE, CacheScope.USER_DATA, PLAYBACK_QUEUE_STORE),
        persisted(CacheCategory.BOOT_MIRROR, CacheScope.USER_DATA, BOOT_MIRROR_STORE),
        persisted(CacheCategory.UPDATE_INFO, CacheScope.USER_DATA, UPDATE_STORE),
        // 设置与播放状态同一文件
        persisted(CacheCategory.SETTINGS, CacheScope.USER_DATA, SETTINGS_STORE),
        // 心动分析结果，重建需重扫全库
        persisted(CacheCategory.HIGHLIGHT, CacheScope.USER_DATA, HIGHLIGHT_STORE),
    )

    /**
     * 落点根：每个可写根目录一条，未主张产出落兜底行
     * 根之间不得包含，重合目录去重
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

    // cacheDir 中转文件，按前缀匹配
    internal fun tempFiles(context: Context): List<File> = context.cacheDir
        .listFiles { file -> file.isFile && StoragePaths.TEMP_FILE_PREFIXES.any { file.name.startsWith(it) } }
        ?.toList()
        .orEmpty()

    // 只认文件；目录保留供继续写入
    internal fun logFiles(context: Context): List<File> = CrashLogManager.logDirectory(context)
        .listFiles { file -> file.isFile }
        ?.toList()
        .orEmpty()

    // 专属下载目录下按 apk 扩展名匹配
    internal fun updatePackages(context: Context): List<File> = File(
        context.getExternalFilesDir(null),
        StoragePaths.UPDATE_DIR_NAME,
    )
        .listFiles { file -> file.isFile && file.extension.equals("apk", ignoreCase = true) }
        ?.toList()
        .orEmpty()

    // 公共下载与专属目录两处，重合只留一处
    private fun metadataLocations(context: Context, primary: File): List<File> {
        val fallback = context.getExternalFilesDir(null)?.let { File(it, primary.name) }
        return listOfNotNull(primary, fallback).distinctBy { it.absolutePath }
    }
}