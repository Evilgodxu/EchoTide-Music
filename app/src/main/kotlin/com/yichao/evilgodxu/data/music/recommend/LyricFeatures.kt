package com.yichao.evilgodxu.data.music.recommend

/**
 * 歌词文本清洗：剔除制作信息行与空行。
 *
 * 副歌定位（[com.yichao.evilgodxu.data.music.highlight.HighlightLocator]）复用同一套前缀判据，
 * 避免整段重复的制作信息行被误判为副歌。
 */
internal object LyricFeatures {

    // 歌词中的制作信息行不含歌词语义，保留会稀释有效行并干扰副歌匹配
    private val METADATA_PREFIX = Regex(
        "^(作词|作詞|作曲|编曲|編曲|制作人|製作人|词|詞|曲|歌手|专辑|專輯|由|出品|和声|和聲|混音|母带|母帶|录音|錄音|OP|SP|监制|監製|策划|企劃)"
    )

    /** 数据清洗：去制作信息行与空行，返回有效歌词行 */
    fun cleanLyrics(rawLines: Collection<String>): List<String> = rawLines
        .map { it.trim() }
        .filter { it.isNotEmpty() && METADATA_PREFIX.find(it) == null }

    fun cleanLyrics(text: String): List<String> = cleanLyrics(text.lines())
}