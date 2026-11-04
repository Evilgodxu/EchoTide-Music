package com.yichao.evilgodxu.data.music.panel

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.proxy.ProxySourceEngine
import com.yichao.evilgodxu.log.CrashLogManager

// 无损升级候选：代理音源优先，失败或未配置时回退内置搜索；
// 按标题/歌手搜索在线原曲，供用户确认选中后下载无损版本替换本地文件
internal suspend fun searchLosslessUpgradeCandidates(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    source: MusicSearchSource,
) {
    playbackState.isLosslessUpgradeSearching = true
    playbackState.losslessUpgradeCandidates = emptyList()
    playbackState.losslessUpgradeError = null
    try {
        val keyword = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
        val proxyResults = runCatching {
            ProxySourceEngine.search(context, source, keyword, page = 1, pageSize = 30)
        }.getOrNull()
        val candidates = if (proxyResults.isNullOrEmpty()) {
            searchSingleSourceCandidates(context, source, track.title, track.artist)
        } else proxyResults
        playbackState.losslessUpgradeCandidates = candidates
            .filter { matchesTrackTitle(track.title, it) }
            .take(30)
    } catch (e: Exception) {
        CrashLogManager.logException("LosslessUpgradeSearch", "搜索无损升级候选失败: 歌曲=${track.title}", e)
        playbackState.losslessUpgradeCandidates = emptyList()
    } finally {
        playbackState.isLosslessUpgradeSearching = false
    }
}
