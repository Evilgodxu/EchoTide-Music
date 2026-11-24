package com.yichao.evilgodxu.data.music.panel

import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.log.CrashLogManager

/**
 * 元数据补全的候选搜索：按当前曲目的标题/歌手在该平台搜索，只保留标题匹配的条目。
 *
 * 与封面候选的差别只有一处：不按「有没有封面」过滤 —— 补全要的是专辑、年份这些字段，
 * 缺封面的条目同样可用。
 */
internal suspend fun searchMetadataCandidates(
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    source: MusicSearchSource,
) {
    playbackState.isMetadataFillSearching = true
    playbackState.metadataFillCandidates = emptyList()
    try {
        playbackState.metadataFillCandidates = searchSingleSourceCandidates(
            playbackState.proxySourceStore, source, track.title, track.artist,
        )
            .filter { matchesTrackTitle(track.title, it) }
            .take(CANDIDATE_LIMIT)
    } catch (e: Exception) {
        CrashLogManager.logException("MetadataFill", "搜索补全候选失败: 歌曲=${track.title}", e)
        playbackState.metadataFillCandidates = emptyList()
    } finally {
        playbackState.isMetadataFillSearching = false
    }
}

// 候选上限：对话框是一行横向滚动，超出用户也不会翻完
private const val CANDIDATE_LIMIT = 30
