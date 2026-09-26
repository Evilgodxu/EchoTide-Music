package com.yichao.evilgodxu.data.music.playback

// 当前曲目的音频格式信息（音频信息条展示用）。
// 各项仅承载实际读取到的值，读取不到即为 null，不以推测默认值填充，
// 避免未知项被展示成真实信息而误导用户
data class AudioSignalPathFormat(
    val format: String?,
    val sampleRate: Int?,
    val outputRate: Int?,
    val bitDepth: Int?,
    val channels: Int?,
    val bitrate: Int?,
)

// 播放列表来源歌单：key 标识来源，name 为副标题显示名
data class PlaylistSource(
    val key: String,
    val name: String,
)

// 单次播放记录：曲目 ID + 播放时间戳（毫秒）
data class PlayEvent(
    val trackId: Long,
    val timestamp: Long,
)
