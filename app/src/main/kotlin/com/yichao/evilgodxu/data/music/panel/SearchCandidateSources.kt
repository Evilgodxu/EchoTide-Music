package com.yichao.evilgodxu.data.music.panel

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.proxy.ProxySourceEngine

// 单来源单次查询候选：每来源对每条查询各取前 limit 条，单源失败不影响其它查询。
// 走平台搜索统一入口：代理音源优先，自定义平台没有内置搜索，其候选完全由代理音源提供
private suspend fun searchSourceCandidates(
    context: Context,
    source: MusicSearchSource,
    query: String,
    limit: Int = 10,
): List<NeteaseSongSearchResult> =
    runCatching { ProxySourceEngine.searchPlatform(context, source, query, page = 1, pageSize = limit).take(limit) }
        .getOrDefault(emptyList())

// 单来源候选：两条查询各取前 10 后合并去重
internal suspend fun searchSingleSourceCandidates(
    context: Context,
    source: MusicSearchSource,
    title: String,
    artist: String,
): List<NeteaseSongSearchResult> {
    val combined = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
    val occupied = if (combined.isBlank()) emptyList() else searchSourceCandidates(context, source, combined)
    val titleOnly = if (title.isBlank()) emptyList() else searchSourceCandidates(context, source, title)
    return mergeCandidates(occupied, titleOnly)
}

/**
 * 合并两次查询的候选，按「来源 + id」保留首次出现顺序去重。
 *
 * 同一平台会把同一首歌拆成多条记录返回 —— 酷狗对同一文件 hash 按不同演绎者各列一条，
 * 而条目身份就是 hash 派生的 id，于是候选列表里出现两条 id 相同的条目。候选列表以 id 作
 * LazyRow 的 key，重复 key 会直接抛 IllegalArgumentException 崩溃，故聚合时必须按身份去重。
 */
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
