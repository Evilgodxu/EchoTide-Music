package com.yichao.evilgodxu.data.music.panel

import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.proxy.ProxySourceEngine
import com.yichao.evilgodxu.data.music.proxy.ProxySourceStore

// 单来源查询候选，失败返回空；自定义平台也走代理音源
private suspend fun searchSourceCandidates(
    sourceStore: ProxySourceStore,
    source: MusicSearchSource,
    query: String,
    limit: Int = 10,
): List<NeteaseSongSearchResult> =
    runCatching { ProxySourceEngine.searchPlatform(sourceStore, source, query, page = 1, pageSize = limit).take(limit) }
        .getOrDefault(emptyList())

// 标题与歌手各取前 10，合并去重
internal suspend fun searchSingleSourceCandidates(
    sourceStore: ProxySourceStore,
    source: MusicSearchSource,
    title: String,
    artist: String,
): List<NeteaseSongSearchResult> {
    val combined = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
    val occupied = if (combined.isBlank()) emptyList() else searchSourceCandidates(sourceStore, source, combined)
    val titleOnly = if (title.isBlank()) emptyList() else searchSourceCandidates(sourceStore, source, title)
    return mergeCandidates(occupied, titleOnly)
}

/** 按来源+id 去重，重复 key 会崩 LazyRow */
internal fun mergeCandidates(
    primary: List<NeteaseSongSearchResult>,
    secondary: List<NeteaseSongSearchResult>,
): List<NeteaseSongSearchResult> =
    (primary + secondary).distinctBy { it.source to it.id }

// 标题相等或互相包含即视为匹配
internal fun matchesTrackTitle(title: String, result: NeteaseSongSearchResult): Boolean {
    val nTarget = normalizeTitle(title)
    if (nTarget.isBlank()) return true
    val nResult = normalizeTitle(result.title)
    return nResult.isNotEmpty() && (nResult == nTarget || nResult.contains(nTarget) || nTarget.contains(nResult))
}

internal fun normalizeTitle(value: String): String {
    return value.lowercase()
        .replace(Regex("""[\s　（）()\[\]【】「」『』《》〈〉、，。！？"'""'']+"""), "")
        .trim()
}
