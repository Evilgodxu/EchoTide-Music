package com.yichao.evilgodxu.data.music.recommend

// 歌词清洗：剔除制作信息行与空行
// 前缀判据与副歌定位共用，避免误判制作行
internal object LyricFeatures {

    // 制作信息行无语义，保留会干扰副歌匹配
    private val METADATA_PREFIX = Regex(
        "^(作词|作詞|作曲|编曲|編曲|制作人|製作人|词|詞|曲|歌手|专辑|專輯|由|出品|和声|和聲|混音|母带|母帶|录音|錄音|OP|SP|监制|監製|策划|企劃)"
    )

    fun cleanLyrics(rawLines: Collection<String>): List<String> = rawLines
        .map { it.trim() }
        .filter { it.isNotEmpty() && METADATA_PREFIX.find(it) == null }

    fun cleanLyrics(text: String): List<String> = cleanLyrics(text.lines())
}