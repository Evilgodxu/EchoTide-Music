package com.yichao.evilgodxu.data.music.panel

import android.content.Context
import com.yichao.evilgodxu.data.music.api.builtInSourceOf
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.proxy.ProxySourceEngine
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 每页 20 条，滑到底再加载
private const val SEARCH_PAGE_SIZE = 20

// 代理一次拉 60 条，本地分页
private const val PROXY_FETCH_COUNT = 60

private const val MAX_PROXY_COVER_FILL = 8

internal suspend fun performSearch(
    playbackState: MusicPlaybackState,
    context: Context,
) {
    val query = playbackState.searchQuery.trim()
    if (query.isBlank()) return
    // 取消旧搜索，避免过期响应覆盖
    playbackState.searchJob?.cancel()
    playbackState.searchLoadJob?.cancel()
    playbackState.searchJob = currentCoroutineContext()[Job] ?: return
    playbackState.isSearching = true
    playbackState.searchResults = emptyList()
    playbackState.searchPending = emptyList()
    playbackState.searchPendingFull = false
    playbackState.errorMsg = null
    // 重置分页状态，从第一页开始
    playbackState.searchPage = 0
    playbackState.hasMoreSearchResults = true
    playbackState.isLoadingMore = false
    // 切到结果视图，加载指示器可见
    playbackState.showSearchResults = true
    try {
        // 代理一次拉全量，本地按页切分
        val proxyResults = ProxySourceEngine.search(
            playbackState.proxySourceStore,
            playbackState.searchSource,
            query,
            page = 1,
            pageSize = PROXY_FETCH_COUNT,
        )
        if (proxyResults != null) {
            // 按 source+id 去重，防止列表 key 冲突
            val deduped = proxyResults.distinctBy { it.source to it.id }
            playbackState.searchResults = deduped.take(SEARCH_PAGE_SIZE)
            playbackState.searchPending = deduped.drop(SEARCH_PAGE_SIZE)
            playbackState.searchPendingFull = proxyResults.size >= PROXY_FETCH_COUNT
            playbackState.hasMoreSearchResults =
                playbackState.searchPending.isNotEmpty() || playbackState.searchPendingFull
        } else {
            // 内置平台按页请求；自定义平台仅靠代理
            val results = builtInSourceOf(playbackState.searchSource)
                ?.let { runCatching { it.search(query, 1, SEARCH_PAGE_SIZE) }.getOrDefault(emptyList()) }
                .orEmpty()
            playbackState.searchResults = results.distinctBy { it.source to it.id }
            playbackState.hasMoreSearchResults = results.size >= SEARCH_PAGE_SIZE
        }
        playbackState.searchPage = 1
        if (playbackState.searchResults.isNotEmpty()) playbackState.addSearchHistory(query)
        playbackState.showSearchResults = true
        // 代理封面逐条换取，后台渐进补齐
        if (playbackState.searchResults.isNotEmpty()) fillProxySearchCovers(playbackState, context)
    } catch (e: kotlinx.coroutines.CancellationException) {
        // 退出搜索的取消，向上传递
        throw e
    } catch (e: Exception) {
        CrashLogManager.logException("OnlineSearch", "搜索歌曲失败", e)
        playbackState.searchResults = emptyList()
    } finally {
        // 仅当前协程复位，避免旧协程清掉新状态
        if (playbackState.searchJob == currentCoroutineContext()[Job]) {
            playbackState.isSearching = false
        }
    }
}

// 分页取结果：代理优先，失败回退内置
private suspend fun fetchSearchPage(
    playbackState: MusicPlaybackState,
    context: Context,
    query: String,
    page: Int,
): List<NeteaseSongSearchResult> {
    val proxyResults = ProxySourceEngine.search(
        playbackState.proxySourceStore,
        playbackState.searchSource,
        query,
        page = page,
        pageSize = SEARCH_PAGE_SIZE,
    )
    return if (proxyResults != null) {
        proxyResults
    } else {
        builtInSourceOf(playbackState.searchSource)
            ?.let { runCatching { it.search(query, page, SEARCH_PAGE_SIZE) }.getOrDefault(emptyList()) }
            .orEmpty()
    }.distinctBy { it.source to it.id }
}

// 加载下一页：先消费代理缓冲，再请求
internal suspend fun loadMoreSearchResults(
    playbackState: MusicPlaybackState,
    context: Context,
) {
    if (playbackState.isLoadingMore || playbackState.isSearching || !playbackState.hasMoreSearchResults) return
    if (playbackState.searchResults.isEmpty()) return
    // 消费本地缓冲，避免重复请求
    if (playbackState.searchPending.isNotEmpty()) {
        // 追加前按 source+id 去重
        val existingKeys = playbackState.searchResults.map { it.source to it.id }.toMutableSet()
        val batch = playbackState.searchPending.take(SEARCH_PAGE_SIZE).filter { item ->
            existingKeys.add(item.source to item.id)
        }
        playbackState.searchResults = playbackState.searchResults + batch
        playbackState.searchPending = playbackState.searchPending.drop(SEARCH_PAGE_SIZE)
        playbackState.searchPage++
        playbackState.hasMoreSearchResults =
            playbackState.searchPending.isNotEmpty() || playbackState.searchPendingFull
        if (batch.isNotEmpty()) fillProxySearchCovers(playbackState, context)
        return
    }
    val query = playbackState.searchQuery.trim()
    if (query.isBlank()) return
    playbackState.searchLoadJob = currentCoroutineContext()[Job] ?: return
    playbackState.isLoadingMore = true
    try {
        val nextPage = playbackState.searchPage + 1
        val pageResults = fetchSearchPage(playbackState, context, query, page = nextPage)
        // 新搜索已取代则丢弃本页
        if (playbackState.searchLoadJob != currentCoroutineContext()[Job]) return
        val existingKeys = playbackState.searchResults.map { it.source to it.id }.toSet()
        val newItems = pageResults.filter { (it.source to it.id) !in existingKeys }
        if (newItems.isEmpty()) {
            // 无新增条目即视为加载完毕
            playbackState.hasMoreSearchResults = false
            return
        }
        playbackState.searchResults = playbackState.searchResults + newItems
        playbackState.searchPage = nextPage
        playbackState.hasMoreSearchResults = pageResults.size >= SEARCH_PAGE_SIZE
        // 代理封面逐条换取，后台渐进补齐
        if (newItems.isNotEmpty()) fillProxySearchCovers(playbackState, context)
    } catch (e: kotlinx.coroutines.CancellationException) {
        // 新搜索导致的取消，向上传递
        throw e
    } catch (e: Exception) {
        CrashLogManager.logException("OnlineSearch", "加载更多搜索结果失败", e)
    } finally {
        playbackState.isLoadingMore = false
        playbackState.searchLoadJob = null
    }
}

// 串行补齐前 N 条封面，控制调用频率
private fun fillProxySearchCovers(playbackState: MusicPlaybackState, context: Context) {
    val pending = playbackState.searchResults
        .filter { it.coverUrl.isNullOrBlank() && !it.coverId.isNullOrBlank() }
        .take(MAX_PROXY_COVER_FILL)
    if (pending.isEmpty()) return
    playbackState.playbackScope.launch {
        pending.forEach { result ->
            val url = ProxySourceEngine.coverUrl(playbackState.proxySourceStore, result) ?: return@forEach
            withContext(Dispatchers.Main) {
                val index = playbackState.searchResults.indexOfFirst { it.id == result.id }
                if (index >= 0) {
                    val list = playbackState.searchResults.toMutableList()
                    list[index] = result.copy(coverUrl = url)
                    playbackState.searchResults = list
                }
            }
        }
    }
}
