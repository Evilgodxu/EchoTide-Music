package com.yichao.evilgodxu.data.music.metadata

import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult

/**
 * 在线候选能提供的标签字段。
 *
 * 只收录「平台确实给出、且字段语义与标签一致」的项：语言与流派在各平台只有数字码且没有
 * 可靠的映射表，宁可留空也不写一条猜出来的值；专辑艺术家同样不是候选条目能确定的。
 */
internal fun candidateTagFields(candidate: NeteaseSongSearchResult): Map<TagField, String> = buildMap {
    candidate.title.takeIf { it.isNotBlank() }?.let { put(TagField.TITLE, it) }
    candidate.artist.takeIf { it.isNotBlank() }?.let { put(TagField.ARTIST, it) }
    candidate.album?.takeIf { it.isNotBlank() }?.let { put(TagField.ALBUM, it) }
    candidate.releaseDate?.takeIf { it.isNotBlank() }?.let { put(TagField.DATE, it) }
    candidate.trackNumber?.let { put(TagField.TRACK_NUMBER, it.toString()) }
    candidate.discNumber?.let { put(TagField.DISC_NUMBER, it.toString()) }
    candidate.copyright?.takeIf { it.isNotBlank() }?.let { put(TagField.COPYRIGHT, it) }
}

/**
 * 补全计划：把候选字段折成「要写给哪些字段、写什么」。
 *
 * 两种口径的差别只在已有值：[overwrite] 为 true 时平台值覆盖本地值（「全覆盖」），
 * 为 false 时只填空字段（「仅补全」）。两种口径都跳过「值与当前完全一致」的字段 ——
 * 写进去不改变任何内容，却会让整段音频被重写一遍。
 *
 * 结果按 [TagField] 的声明顺序返回，界面据此逐条走既有的自动落盘路径。
 */
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
