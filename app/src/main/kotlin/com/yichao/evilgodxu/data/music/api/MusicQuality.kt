package com.yichao.evilgodxu.data.music.api

// 在线播放音质档位：各平台解析播放地址时按此档位请求对应层级。
// HI_RES 为无损档内的上位层，仅参与解析匹配，不作为用户可选档位（见 userSelectable）；
// 母带、全景声等平台升频音质不设档位，系统任何情况下都不请求。
enum class MusicQuality(val userSelectable: Boolean) {
    HI_RES(false),
    LOSSLESS(true),
    HIGH(true),
    STANDARD(true),
}

// 无损档内的解析层级，由高到低：优先 Hi-Res，其次普通无损。
// 供无损升级等只接受无损的场景逐级尝试，不降级到更低档位
internal val LOSSLESS_TIERS: List<MusicQuality> = listOf(MusicQuality.HI_RES, MusicQuality.LOSSLESS)

// 音质自适应候选顺序：无损档以 Hi-Res 优先，其余档位先取自身，缺档时按音质由低到高向上匹配，再逐级降级；
// 候选链顶端恒为 Hi-Res，母带等平台升频音质不参与匹配。
// 解析与下载（歌单同步）或解析与试播（在线播放）均按此顺序逐档尝试，全部不可用才判定失败
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
