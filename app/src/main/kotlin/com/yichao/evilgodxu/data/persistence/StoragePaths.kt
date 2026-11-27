package com.yichao.evilgodxu.data.persistence

import com.yichao.evilgodxu.data.music.clip.SpectrumImageSharing

/** 共享目录名与前缀，只在此处定义 */
object StoragePaths {

    /** 歌曲缓存目录名 */
    const val AUDIO_DIR_NAME = "Audio"

    /** 更新包目录：避开系统 Download，仅下载时落盘 */
    const val UPDATE_DIR_NAME = "update"

    /** 中转文件前缀，须与各产出方一致 */
    val TEMP_FILE_PREFIXES = listOf(
        "download",
        "upgrade",
        "metadata",
        SpectrumImageSharing.SHARE_FILE_PREFIX,
    )
}