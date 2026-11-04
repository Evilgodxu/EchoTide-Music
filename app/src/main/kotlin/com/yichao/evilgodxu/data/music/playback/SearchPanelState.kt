package com.yichao.evilgodxu.data.music.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.model.RecentCover
import kotlinx.coroutines.Job

/**
 * 首页在线搜索与在线资源匹配的界面状态。
 *
 * 覆盖搜索框、结果分页、音质试播、无损升级、封面与歌词匹配这几条互不相同的流程；
 * 它们只共享「一次在线匹配」的生命周期，不参与播放队列与播放器状态。
 * 协程句柄同样收在这里：新搜索发起时取消旧任务，取消关系由同一处维护。
 */
internal class SearchPanelState {

    var isSearchMode by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var searchSource by mutableStateOf(MusicSearchSource.NETEASE)
    var searchResults by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var isSearching by mutableStateOf(false)
    // 当前搜索协程句柄：新搜索发起时取消上一次，避免过期响应覆盖新查询结果
    var searchJob: Job? = null
    // 搜索结果分页：已加载页数、是否正在加载更多、是否还有更多
    var searchPage by mutableIntStateOf(0)
    var isLoadingMore by mutableStateOf(false)
    var hasMoreSearchResults by mutableStateOf(true)
    // 代理音源一次拉取的全量结果缓冲：本地按页切分展示，避免不支持分页的代理重复请求
    var searchPending by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    // 代理音源首次请求是否拉满（可能支持分页，缓冲耗尽后继续请求下一页）
    var searchPendingFull by mutableStateOf(false)
    // 加载更多分页的协程句柄：新搜索发起时取消，避免过期分页混入新结果
    var searchLoadJob: Job? = null
    var showSearchResults by mutableStateOf(false)
    var pendingSearchResults by mutableStateOf<List<NeteaseSongSearchResult>>(emptyList())
    var closeSearchResultsOnReady by mutableStateOf(false)
    // 首页音质选择对话框：非空时显示，目标为待播在线歌曲
    var qualityPickTrack by mutableStateOf<NeteaseSongSearchResult?>(null)
    // 音质尝试中：解析地址与等待播放结果期间置 true，阻止重复点击/误关对话框
    var qualityBusy by mutableStateOf(false)
    // 最近一次音质尝试失败提示（失败时保留对话框展示，供用户换其它音质）
    var qualityError by mutableStateOf<String?>(null)
    // 音质试播曲目 ID：播放就绪(READY)后清空；播放失败时据此移除试播曲目并保留对话框
    var pendingQualityPlayTrackId by mutableStateOf<Long?>(null)
    // 无损升级进行中：阻止重复触发与误关对话框
    var losslessUpgradeBusy by mutableStateOf(false)
    // 最近一次无损升级失败提示：升级失败时保留对话框展示，供用户重试
    var losslessUpgradeError by mutableStateOf<String?>(null)
    // 无损升级候选：按来源搜索的在线原曲，用户确认选中后下载无损替换本地文件
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
    // 歌词/封面刷新当前来源：按来源独立搜索，切换来源时轮换并重新搜索
    var lyricsRefreshSource by mutableStateOf(MusicSearchSource.NETEASE)
    var coverRefreshSource by mutableStateOf(MusicSearchSource.NETEASE)

    // 播放就绪：搜索结果如需在起播后收起则收起；音质试播就绪即视为播放成功，关闭对话框
    fun onPlaybackReady() {
        if (closeSearchResultsOnReady) {
            closeSearchResultsOnReady = false
            isSearchMode = false
            showSearchResults = false
            searchQuery = ""
            searchResults = emptyList()
            searchPending = emptyList()
            searchPendingFull = false
            pendingSearchResults = emptyList()
        }
        if (pendingQualityPlayTrackId != null) {
            pendingQualityPlayTrackId = null
            qualityBusy = false
            qualityPickTrack = null
            qualityError = null
        }
    }

    /**
     * 播放失败：音质试播失败时移除刚加入的试播曲目并保留对话框，供用户换其它音质。
     *
     * 返回需要从播放列表移除的试播曲目 ID；非试播失败时为 null。
     */
    fun onPlaybackFailed(qualityFailureMessage: String?): Long? {
        val pendingId = pendingQualityPlayTrackId
        if (pendingId == null || qualityPickTrack == null) return null
        pendingQualityPlayTrackId = null
        qualityBusy = false
        qualityError = qualityFailureMessage
        return pendingId
    }

    // 播放中断：待起播的搜索结果不再自动收起，清掉待生效结果，避免下次就绪时误收
    fun onPlaybackAborted() {
        closeSearchResultsOnReady = false
        pendingSearchResults = emptyList()
    }
}
