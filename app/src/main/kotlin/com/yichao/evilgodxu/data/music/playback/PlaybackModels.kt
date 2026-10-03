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

// 曲目变更的类型：界面据此决定换图、换色与换文案的过渡。
// 不取播放列表的下标差——随机播放下标差是随机数，读不出前后；
// 变更的类型本身（上一曲/下一曲/选曲）则始终能读出方向
enum class TrackSwitchKind {
    // 上一曲：新内容自左侧移入
    Previous,

    // 下一曲：新内容自右侧移入。自然接续（自动下一首、插队队列）同属此列
    Next,

    // 无方向可读的曲目变更：选曲播放（含外部音频起播、搜索结果起播）。
    // 移入的一侧取不出来，元素不做位移
    Select,

    // 曲目身份变了而画面同源：在线曲缓存完成后迁移为本地曲目、库扫描后重建列表实例等。
    // 内容一致，直接替换看不出变化；横移则会把两张同源封面错位成一道可辨的接缝
    SameContent,
}
