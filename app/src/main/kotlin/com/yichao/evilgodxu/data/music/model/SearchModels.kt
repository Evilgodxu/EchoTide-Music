package com.yichao.evilgodxu.data.music.model

// 在线匹配候选，元数据补全时使用
internal data class NeteaseSongMatch(
    val id: Long,
    val title: String,
    val artist: String,
    val coverUrl: String?
)

// 在线曲目 ID 偏移，避免与本地 ID 碰撞
private const val ONLINE_TRACK_ID_OFFSET = 1_000_000L

internal val NeteaseSongSearchResult.playlistTrackId: Long
    get() = id + ONLINE_TRACK_ID_OFFSET

// 在线搜索来源，平台键是搜索/代理/持久化唯一身份
// 内置键 wy/qq/kg/kw，其余由代理音源承担
@JvmInline
value class MusicSearchSource(val key: String) {

    companion object {
        val NETEASE = MusicSearchSource("wy")
        val QQ = MusicSearchSource("qq")
        val KUGOU = MusicSearchSource("kg")
        val KUWO = MusicSearchSource("kw")

        // 内置平台，顺序即菜单展示顺序
        val BUILT_IN: List<MusicSearchSource> = listOf(NETEASE, QQ, KUGOU, KUWO)

        val builtInKeys: Set<String> = BUILT_IN.map { it.key }.toSet()
    }
}

data class NeteaseSongSearchResult(
    val id: Long,
    val title: String,
    val artist: String,
    val coverUrl: String?,
    // CDN 缩略图，列表行使用以加快加载
    val coverThumbUrl: String? = null,
    val duration: Long = 0L,
    val source: MusicSearchSource = MusicSearchSource.NETEASE,
    // 平台内标识，取播放地址/歌词时使用
    val sourceId: String? = null,
    // 封面 ID，播放时经 pic 动作换取真实地址
    val coverId: String? = null,
    // 专辑名等标签字段，缺的由详情补全或留空
    // 不参与展示与匹配，只在缓存落地时写入文件
    val album: String? = null,
    // 发行日期：完整写 yyyy-MM-dd，仅年份写 yyyy
    val releaseDate: String? = null,
    // 音轨号不含总数，各平台分母口径不一致
    val trackNumber: Int? = null,
    // 碟号
    val discNumber: Int? = null,
    // 版权方 / 厂牌，仅网易专辑详情提供
    val copyright: String? = null,
    // 平台响度，落地写成 ReplayGain
    // 只收单曲增益与峰值，缺失表示平台未给
    val replayGainTrackGainDb: Double? = null,
    val replayGainTrackPeak: Double? = null,
)

internal data class NeteaseLyricData(val lines: List<LyricLine>)

data class LyricWord(val startMs: Long, val durationMs: Long, val text: String)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    // 中文翻译，由 tlyric/trans 按时间合并
    val translation: String? = null
)
