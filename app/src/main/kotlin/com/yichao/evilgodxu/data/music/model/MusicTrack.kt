package com.yichao.evilgodxu.data.music.model

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
    // 在线封面地址，仅下载凭据，显示仍取音频文件
    val neteaseCoverUrl: String = "",
    val lyricCachePath: String = "",
    internal val lyricLines: List<LyricLine> = emptyList(),
    val isFavorite: Boolean = false,
    // 歌词时间轴偏移（毫秒），用于微调持久化
    val lyricOffsetMs: Long = 0L,
    // 歌词自动匹配已尝试且失败：不再重复拉取
    val lyricFailed: Boolean = false,
    // 在线播放产生（含已缓存），切歌后清理
    val isOnlinePlay: Boolean = false,
    // 本地文件修改时间，供排序；在线/外部为 0
    val fileModifiedMs: Long = 0L,
) {
    val isLocalAudioSource: Boolean
        get() = path.isNotBlank() || audioUri.startsWith("content:") || audioUri.startsWith("file:")

    // 仅本地音频源可被匹配并替换为无损版本
    fun isUpgradableToLossless(): Boolean = isLocalAudioSource
}

// 播放模式
// Highlight 决定每首放哪一段，其余决定播放顺序
// 新值只能追加末尾：按 ordinal 落盘，插入会错位
enum class PlayMode { RepeatOne, RepeatAll, Shuffle, Highlight }
