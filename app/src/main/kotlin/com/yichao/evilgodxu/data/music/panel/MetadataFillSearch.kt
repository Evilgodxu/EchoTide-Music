package com.yichao.evilgodxu.data.music.panel

import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.log.CrashLogManager

/** 元数据候选：按标题/歌手搜索，不按有无封面过滤 */
internal suspend fun searchMetadataCandidates(
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    source: MusicSearchSource,
) {
    playbackState.isMetadataFillSearching = true
    playbackState.metadataFillCandidates = emptyList()
    try {
        playbackState.metadataFillCandidates = metadataFillCandidates(playbackState, track, source)
    } finally {
        playbackState.isMetadataFillSearching = false
    }
}

/** 取回候选但不写播放状态；搜索口径只此一处 */
internal suspend fun metadataFillCandidates(
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    source: MusicSearchSource,
): List<NeteaseSongSearchResult> = try {
    searchSingleSourceCandidates(
        playbackState.proxySourceStore, source, track.title, track.artist,
    )
        .filter { matchesTrackTitle(track.title, it) }
        .take(CANDIDATE_LIMIT)
} catch (e: Exception) {
    CrashLogManager.logException("MetadataFill", "搜索补全候选失败: 歌曲=${track.title}", e)
    emptyList()
}

// 候选上限：横向滚动，超出不会翻完
private const val CANDIDATE_LIMIT = 30
