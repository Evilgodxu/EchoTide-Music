package com.yichao.evilgodxu.data.music.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.model.RecentCover
import kotlinx.coroutines.Job

/** 首页搜索与在线匹配的界面状态，含协程句柄 */
internal class SearchPanelState {

    var isSearchMode by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var searchSource by mutableStateOf(MusicSearchSource.NETEASE)
    var searchResults by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var isSearching by mutableStateOf(false)
    // 搜索协程句柄，新搜索取消旧任务
    var searchJob: Job? = null
    // 分页：已加载页数、加载中、是否还有更多
    var searchPage by mutableIntStateOf(0)
    var isLoadingMore by mutableStateOf(false)
    var hasMoreSearchResults by mutableStateOf(true)
    // 代理音源全量缓冲，本地按页切分
    var searchPending by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    // 首次请求是否拉满，缓冲耗尽后继续请求
    var searchPendingFull by mutableStateOf(false)
    // 加载更多句柄，新搜索取消
    var searchLoadJob: Job? = null
    var showSearchResults by mutableStateOf(false)
    var pendingSearchResults by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var closeSearchResultsOnReady by mutableStateOf(false)
    // 音质选择对话框状态，非空即显示
    var qualityPickTrack by mutableStateOf<NeteaseSongSearchResult?>(null)
    // 音质尝试中，阻止重复点击
    var qualityBusy by mutableStateOf(false)
    // 最近一次音质失败提示，保留对话框
    var qualityError by mutableStateOf<String?>(null)
    // 试播曲目 ID，READY 后清空
    var pendingQualityPlayTrackId by mutableStateOf<Long?>(null)
    // 无损升级进行中，阻止重复触发
    var losslessUpgradeBusy by mutableStateOf(false)
    // 最近一次无损升级失败提示
    var losslessUpgradeError by mutableStateOf<String?>(null)
    // 无损升级候选（在线原曲）
    var losslessUpgradeCandidates by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var isLosslessUpgradeSearching by mutableStateOf(false)
    var losslessUpgradeSource by mutableStateOf(MusicSearchSource.NETEASE)
    var coverCandidates by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var isCoverSearching by mutableStateOf(false)
    var localCoverCandidates by mutableStateOf<List<RecentCover>>(emptyList())
    var lyricsCandidates by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var isLyricsSearching by mutableStateOf(false)
    var isLyricsRefreshing by mutableStateOf(false)
    var lyricsRefreshError by mutableStateOf<String?>(null)
    // 歌词/封面刷新来源，切换即重搜
    var lyricsRefreshSource by mutableStateOf(MusicSearchSource.NETEASE)
    var coverRefreshSource by mutableStateOf(MusicSearchSource.NETEASE)
    // 元数据补全候选与来源，须与封面刷新分开
    var metadataFillCandidates by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var isMetadataFillSearching by mutableStateOf(false)
    var metadataFillSource by mutableStateOf(MusicSearchSource.NETEASE)

    // 搜索上下文复位，各收尾入口共用
    fun clearSearchResults() {
        searchQuery = ""
        searchResults = emptyList()
        searchPending = emptyList()
        searchPendingFull = false
        showSearchResults = false
    }

    // 播放就绪：按需收起结果并关闭音质框
    fun onPlaybackReady() {
        if (closeSearchResultsOnReady) {
            closeSearchResultsOnReady = false
            isSearchMode = false
            clearSearchResults()
            pendingSearchResults = emptyList()
        }
        if (pendingQualityPlayTrackId != null) {
            pendingQualityPlayTrackId = null
            qualityBusy = false
            qualityPickTrack = null
            qualityError = null
        }
    }

    /** 试播失败：移除试播曲目并保留对话框，返回曲目 ID */
    fun onPlaybackFailed(qualityFailureMessage: String?): Long? {
        val pendingId = pendingQualityPlayTrackId
        if (pendingId == null || qualityPickTrack == null) return null
        pendingQualityPlayTrackId = null
        qualityBusy = false
        qualityError = qualityFailureMessage
        return pendingId
    }

    // 播放中断：清掉待生效结果，避免误收
    fun onPlaybackAborted() {
        closeSearchResultsOnReady = false
        pendingSearchResults = emptyList()
    }
}
