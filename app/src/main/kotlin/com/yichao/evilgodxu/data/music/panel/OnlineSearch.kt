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

// 搜索结果每页条数：首页 20 条，列表滑到底部后再加载下一页
private const val SEARCH_PAGE_SIZE = 20

// 代理音源一次拉取的条数上限：多数代理不支持分页，一次拿全量后本地按页切分
private const val PROXY_FETCH_COUNT = 60

private const val MAX_PROXY_COVER_FILL = 8

internal suspend fun performSearch(
    playbackState: MusicPlaybackState,
    context: Context,
) {
    val query = playbackState.searchQuery.trim()
    if (query.isBlank()) return
    // 取消上一次未完成的搜索与分页加载，避免过期响应覆盖新查询结果
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
    // 立即切到结果视图，使加载指示器在搜索期间可见
    playbackState.showSearchResults = true
    try {
        // 代理音源一次拉取全量（多数代理不支持分页），本地按页切分展示
        val proxyResults = ProxySourceEngine.search(
            playbackState.proxySourceStore,
            playbackState.searchSource,
            query,
            page = 1,
            pageSize = PROXY_FETCH_COUNT,
        )
        if (proxyResults != null) {
            // 代理音源可能返回重复条目（同一首歌多种音质/hash 相同），按 source+id 去重防止列表 key 冲突
            val deduped = proxyResults.distinctBy { it.source to it.id }
            playbackState.searchResults = deduped.take(SEARCH_PAGE_SIZE)
            playbackState.searchPending = deduped.drop(SEARCH_PAGE_SIZE)
            playbackState.searchPendingFull = proxyResults.size >= PROXY_FETCH_COUNT
            playbackState.hasMoreSearchResults =
                playbackState.searchPending.isNotEmpty() || playbackState.searchPendingFull
        } else {
            // 内置平台按页请求，首屏一页；自定义平台没有内置实现，代理不可用即无结果
            val results = builtInSourceOf(playbackState.searchSource)
                ?.let { runCatching { it.search(query, 1, SEARCH_PAGE_SIZE) }.getOrDefault(emptyList()) }
                .orEmpty()
            playbackState.searchResults = results.distinctBy { it.source to it.id }
            playbackState.hasMoreSearchResults = results.size >= SEARCH_PAGE_SIZE
        }
        playbackState.searchPage = 1
        if (playbackState.searchResults.isNotEmpty()) playbackState.addSearchHistory(query)
        playbackState.showSearchResults = true
        // 代理搜索结果的封面为逐条经 pic 动作换取，后台渐进补齐
        if (playbackState.searchResults.isNotEmpty()) fillProxySearchCovers(playbackState, context)
    } catch (e: kotlinx.coroutines.CancellationException) {
        // 搜索界面退出导致的协程取消，不是失败，向上传递取消
        throw e
    } catch (e: Exception) {
        CrashLogManager.logException("OnlineSearch", "搜索歌曲失败", e)
        playbackState.searchResults = emptyList()
    } finally {
        // 仅当前搜索协程复位搜索状态，避免被取消的旧协程提前清掉新搜索的加载态
        if (playbackState.searchJob == currentCoroutineContext()[Job]) {
            playbackState.isSearching = false
        }
    }
}

// 分页获取搜索结果：代理音源优先，失败或未配置时回退内置平台
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

// 上拉加载下一页：优先消费代理全量缓冲，缓冲耗尽或内置平台再请求下一页
internal suspend fun loadMoreSearchResults(
    playbackState: MusicPlaybackState,
    context: Context,
) {
    if (playbackState.isLoadingMore || playbackState.isSearching || !playbackState.hasMoreSearchResults) return
    if (playbackState.searchResults.isEmpty()) return
    // 代理音源全量缓冲：本地切分追加，无需重复请求（不支持分页的代理每次返回相同结果）
    if (playbackState.searchPending.isNotEmpty()) {
        // 缓冲内仍可能与已加载条目重复，追加前按 source+id 去重
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
        // 新搜索已取代本次加载时丢弃过期分页
        if (playbackState.searchLoadJob != currentCoroutineContext()[Job]) return
        val existingKeys = playbackState.searchResults.map { it.source to it.id }.toSet()
        val newItems = pageResults.filter { (it.source to it.id) !in existingKeys }
        if (newItems.isEmpty()) {
            // 本页无新增条目（全部与已加载重复）时视为已加载完全部结果，避免重复请求
            playbackState.hasMoreSearchResults = false
            return
        }
        playbackState.searchResults = playbackState.searchResults + newItems
        playbackState.searchPage = nextPage
        playbackState.hasMoreSearchResults = pageResults.size >= SEARCH_PAGE_SIZE
        // 代理搜索结果的封面为逐条经 pic 动作换取，后台渐进补齐
        if (newItems.isNotEmpty()) fillProxySearchCovers(playbackState, context)
    } catch (e: kotlinx.coroutines.CancellationException) {
        // 新搜索发起导致的分页协程取消，不是失败，向上传递取消
        throw e
    } catch (e: Exception) {
        CrashLogManager.logException("OnlineSearch", "加载更多搜索结果失败", e)
    } finally {
        playbackState.isLoadingMore = false
        playbackState.searchLoadJob = null
    }
}

// 串行补齐前 N 条，控制聚合接口调用频率
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
