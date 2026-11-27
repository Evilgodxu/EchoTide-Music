package com.yichao.evilgodxu.data.music.playback

// 当前曲目音频格式，读不到为 null，不用默认值
data class AudioSignalPathFormat(
    val format: String?,
    val sampleRate: Int?,
    val outputRate: Int?,
    val bitDepth: Int?,
    val channels: Int?,
    val bitrate: Int?,
)

// 过滤来源：key 标识，name 为副标题
data class PlaylistSource(
    val key: String,
    val name: String,
)

// 单次播放记录：曲目 ID + 时间戳（毫秒）
data class PlayEvent(
    val trackId: Long,
    val timestamp: Long,
)

// 曲目变更类型：不用下标差，随机下读不出方向
enum class TrackSwitchKind {
    // 上一曲：内容自左侧移入
    Previous,

    // 下一曲：自右侧移入，自然接续同属
    Next,

    // 无方向变更：选曲播放，元素不位移
    Select,

    // 身份变了但画面同源，直接替换避免错位接缝
    SameContent,
}
