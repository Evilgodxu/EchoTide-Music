package com.yichao.evilgodxu.data.music.api

// 在线播放音质档位：各平台解析播放地址时按此档位请求对应层级
enum class MusicQuality {
    LOSSLESS,
    HIGH,
    STANDARD,
}

// 音质自适应候选顺序：选定档位优先；该档在音源中不存在时先向上匹配更高档位，再降级到更低档位。
// 解析与下载（歌单同步）或解析与试播（在线播放）均按此顺序逐档尝试，全部不可用才判定失败
fun MusicQuality.adaptiveCandidates(): List<MusicQuality> = when (this) {
    MusicQuality.LOSSLESS -> listOf(MusicQuality.LOSSLESS, MusicQuality.HIGH, MusicQuality.STANDARD)
    MusicQuality.HIGH -> listOf(MusicQuality.HIGH, MusicQuality.LOSSLESS, MusicQuality.STANDARD)
    MusicQuality.STANDARD -> listOf(MusicQuality.STANDARD, MusicQuality.HIGH, MusicQuality.LOSSLESS)
}