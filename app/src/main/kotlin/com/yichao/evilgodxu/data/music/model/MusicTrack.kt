package com.yichao.evilgodxu.data.music.model

// 本地音乐轨道
data class MusicTrack(
    val id: Long,
    val path: String,
    val audioUri: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val albumId: Long,
    val albumName: String = "",
    val neteaseId: Long = 0L,
    // 在线封面地址：仅作在线播放时下载封面原图的凭据，不参与显示（显示统一读系统略缩图）
    val neteaseCoverUrl: String = "",
    val lyricCachePath: String = "",
    internal val lyricLines: List<LyricLine> = emptyList(),
    val isFavorite: Boolean = false,
    // 歌词时间轴偏移（毫秒），用于微调持久化
    val lyricOffsetMs: Long = 0L,
    // 歌词自动匹配已尝试且失败：不再重复拉取
    val lyricFailed: Boolean = false,
    // 是否由在线播放产生（含已缓存为本地文件）；仅当前播放时保留，切歌后自动清理
    val isOnlinePlay: Boolean = false,
    // 本地音频文件修改时间（毫秒）：扫描时取自 MediaStore，供「文件修改时间」排序；在线/外部曲目为 0
    val fileModifiedMs: Long = 0L,
) {
    // 是否为可读取本地音频源：本地文件路径或 MediaStore 本地文件 URI
    val isLocalAudioSource: Boolean
        get() = path.isNotBlank() || audioUri.startsWith("content:") || audioUri.startsWith("file:")

    // 是否可升级为无损：仅本地音频源可被匹配并替换为无损版本
    fun isUpgradableToLossless(): Boolean = isLocalAudioSource
}

// 播放模式
enum class PlayMode { RepeatOne, RepeatAll, Shuffle }
