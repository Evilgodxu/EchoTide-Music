package com.yichao.evilgodxu.data.persistence

import com.yichao.evilgodxu.data.music.clip.SpectrumImageSharing

/**
 * 写盘落点中的共享路径事实：同一个目录名或前缀会被产出方与统计方同时引用，
 * 集中一处是为了不出现第二份事实 —— 改名只改这里，读写与统计一起跟着变。
 */
object StoragePaths {

    /** 图片磁盘缓存目录名：与 Coil 磁盘缓存配置共用，改名会使既有缓存变成无人认领的孤儿 */
    const val IMAGE_CACHE_DIR_NAME = "image_cache"

    /** 图片磁盘缓存上限：约可容纳百张 2048 长边封面，超出由 LRU 淘汰，不随可用空间无界增长 */
    const val IMAGE_DISK_CACHE_MAX_BYTES = 32L * 1024 * 1024

    /** 歌曲缓存目录名：公共下载目录下的音频条目落点 */
    const val AUDIO_DIR_NAME = "Audio"

    /** 中转文件前缀：与 File.createTempFile 的 prefix 参数及频谱图分享中转文件名的前缀对应 */
    val TEMP_FILE_PREFIXES = listOf(
        "download",
        "upgrade",
        "metadata",
        SpectrumImageSharing.SHARE_FILE_PREFIX,
    )
}