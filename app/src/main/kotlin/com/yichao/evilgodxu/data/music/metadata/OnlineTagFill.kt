package com.yichao.evilgodxu.data.music.metadata

import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult

// 详情请求等待上限 5s，超时用搜索结果字段
internal const val METADATA_FILL_DETAIL_WAIT_MS = 5_000L

/** 候选可提供的标签字段；语言/流派无常靠映射，留空 */
internal fun candidateTagFields(candidate: NeteaseSongSearchResult): Map<TagField, String> = buildMap {
    candidate.title.takeIf { it.isNotBlank() }?.let { put(TagField.TITLE, it) }
    candidate.artist.takeIf { it.isNotBlank() }?.let { put(TagField.ARTIST, it) }
    candidate.album?.takeIf { it.isNotBlank() }?.let { put(TagField.ALBUM, it) }
    candidate.releaseDate?.takeIf { it.isNotBlank() }?.let { put(TagField.DATE, it) }
    candidate.trackNumber?.let { put(TagField.TRACK_NUMBER, it.toString()) }
    candidate.discNumber?.let { put(TagField.DISC_NUMBER, it.toString()) }
    candidate.copyright?.takeIf { it.isNotBlank() }?.let { put(TagField.COPYRIGHT, it) }
}

/** 补全计划：overwrite 时覆盖，否则只填空；跳过值相同项 */
internal fun fillPlan(
    current: Map<TagField, String>,
    candidate: NeteaseSongSearchResult,
    overwrite: Boolean,
): List<Pair<TagField, String>> {
    val available = candidateTagFields(candidate)
    return TagField.entries.mapNotNull { field ->
        val value = available[field] ?: return@mapNotNull null
        val existing = current[field].orEmpty()
        if (existing.trim() == value.trim()) return@mapNotNull null
        if (!overwrite && existing.isNotBlank()) return@mapNotNull null
        field to value
    }
}
