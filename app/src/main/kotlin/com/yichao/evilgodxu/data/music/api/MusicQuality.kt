package com.yichao.evilgodxu.data.music.api

// 在线播放音质档位，解析直链时按档位请求
// HI_RES 仅参与匹配不可选；母带等升频不设档位
enum class MusicQuality(val userSelectable: Boolean) {
    HI_RES(false),
    LOSSLESS(true),
    HIGH(true),
    STANDARD(true),
}

// 无损档解析层级，由高到低
internal val LOSSLESS_TIERS: List<MusicQuality> = listOf(MusicQuality.HI_RES, MusicQuality.LOSSLESS)

// 自适应候选：无损档 Hi-Res 优先，其余先自身再降级
// 候选链顶端恒为 Hi-Res，升频音质不参与
fun MusicQuality.adaptiveCandidates(): List<MusicQuality> = when (this) {
    MusicQuality.HI_RES,
    MusicQuality.LOSSLESS -> LOSSLESS_TIERS + listOf(MusicQuality.HIGH, MusicQuality.STANDARD)
    MusicQuality.HIGH -> listOf(
        MusicQuality.HIGH, MusicQuality.LOSSLESS, MusicQuality.HI_RES, MusicQuality.STANDARD,
    )
    MusicQuality.STANDARD -> listOf(
        MusicQuality.STANDARD, MusicQuality.HIGH, MusicQuality.LOSSLESS, MusicQuality.HI_RES,
    )
}
