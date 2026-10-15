package com.yichao.evilgodxu.data.persistence

import com.yichao.evilgodxu.data.music.clip.SpectrumImageSharing

/**
 * 写盘落点中的共享路径事实：同一个目录名或前缀会被产出方与统计方同时引用，
 * 集中一处是为了不出现第二份事实 —— 改名只改这里，读写与统计一起跟着变。
 */
object StoragePaths {

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