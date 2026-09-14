package com.yichao.evilgodxu.data.music.panel

import android.content.Context
import android.net.Uri
import com.yichao.evilgodxu.data.music.api.KugouMusicApi
import com.yichao.evilgodxu.data.music.api.KuwoMusicApi
import com.yichao.evilgodxu.data.music.api.MiguMusicApi
import com.yichao.evilgodxu.data.music.api.MusicQuality
import com.yichao.evilgodxu.data.music.api.NeteaseMusicApi
import com.yichao.evilgodxu.data.music.api.OnlineMusicSource
import com.yichao.evilgodxu.data.music.api.QQMusicApi
import com.yichao.evilgodxu.data.music.api.sourceOf
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.proxy.ProxySourceEngine
import com.yichao.evilgodxu.data.music.download.cacheToDownloads
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.R
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 搜索结果每页条数：首页 20 条，列表滑到底部后再加载下一页
private const val SEARCH_PAGE_SIZE = 20

// 代理音源一次拉取的条数上限：多数代理不支持分页，一次拿全量后本地按页切分
private const val PROXY_FETCH_COUNT = 60

// 单来源单次查询候选：每来源对每条查询各取前 10 条，单源失败不影响其它查询
private suspend fun searchSourceCandidates(source: OnlineMusicSource, query: String): List<NeteaseSongSearchResult> =
    runCatching { source.search(query, page = 1, pageSize = 10).take(10) }.getOrDefault(emptyList())

// 单来源候选：先以“歌名+歌手”查询、再以纯歌名查询，各取前 10 后合并去重（10+10）
private suspend fun searchSingleSourceCandidates(
    source: OnlineMusicSource,
    title: String,
    artist: String,
): List<NeteaseSongSearchResult> {
    val combined = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
    val occupied = if (combined.isBlank()) emptyList() else searchSourceCandidates(source, combined)
    val occupiedIds = occupied.map { it.id }.toSet()
    val titleOnly = if (title.isBlank()) emptyList() else
        searchSourceCandidates(source, title).filter { it.id !in occupiedIds }
    return occupied + titleOnly
}

// 过滤候选：以是否匹配当前歌曲标题为唯一判定依据，normalize 后相等或互相包含
private fun matchesTrackTitle(title: String, result: NeteaseSongSearchResult): Boolean {
    val nTarget = normalizeTitle(title)
    if (nTarget.isBlank()) return true
    val nResult = normalizeTitle(result.title)
    return nResult.isNotEmpty() && (nResult == nTarget || nResult.contains(nTarget) || nTarget.contains(nResult))
}

internal suspend fun searchLyricsCandidates(
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    source: MusicSearchSource,
) {
    playbackState.isLyricsSearching = true
    playbackState.lyricsCandidates = emptyList()
    playbackState.lyricsRefreshError = null
    try {
        playbackState.lyricsCandidates = searchSingleSourceCandidates(sourceOf(source), track.title, track.artist)
            .filter { matchesTrackTitle(track.title, it) }
            .take(30)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "搜索歌词候选失败: 歌曲=${track.title}", e)
        playbackState.lyricsCandidates = emptyList()
    } finally {
        playbackState.isLyricsSearching = false
    }
}

// 把歌词同步内嵌进音频文件：歌词缓存文件是网络来源歌词的唯一副本，内嵌后即使缓存文件丢失，
// 也能从音频文件本身恢复。失败只记录日志（写入器内部处理），不影响歌词缓存与显示
private suspend fun embedLyricsText(context: Context, track: MusicTrack, lyricsText: String) {
    if (lyricsText.isBlank()) return
    MusicMetadataWriter.writeLyricsToSource(context, track, lyricsText)
}

internal suspend fun applyLyricsCandidate(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    candidate: NeteaseSongSearchResult,
): Boolean {
    playbackState.isLyricsRefreshing = true
    playbackState.lyricsRefreshError = null
    return try {
        val updated = withContext(Dispatchers.IO) {
            val lines = when (candidate.source) {
                MusicSearchSource.QQ -> QQMusicApi.lyricLines(candidate).orEmpty()
                MusicSearchSource.KUGOU -> KugouMusicApi.lyricLines(candidate).orEmpty()
                MusicSearchSource.KUWO -> KuwoMusicApi.lyricLines(candidate).orEmpty()
                MusicSearchSource.MIGU -> MiguMusicApi.lyricLines(candidate).orEmpty()
                else -> NeteaseMusicApi.lyric(candidate.id).lines
            }
            if (lines.isEmpty()) return@withContext null
            val path = MusicMetadataCache.saveLyrics(context, track.title, track.artist, lines).orEmpty()
            if (path.isBlank()) return@withContext null
            embedLyricsText(context, track, MusicMetadataCache.encodeLyrics(lines))
            track.copy(
                lyricCachePath = path,
                lyricLines = lines,
                neteaseId = candidate.id,
                neteaseCoverUrl = candidate.coverUrl.orEmpty(),
                // 已获取到歌词，清除此前的匹配失败标记，避免重启后缓存恢复被挡住
                lyricFailed = false,
            )
        } ?: return false
        withContext(Dispatchers.Main) {
            playbackState.updateTrack(updated)
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "应用歌词候选失败: 歌曲=${track.title} 路径=${track.path}", e)
        false
    } finally {
        withContext(Dispatchers.Main) {
            playbackState.isLyricsRefreshing = false
        }
    }
}

// 导入本地 LRC 歌词文件：解析后写入歌词缓存并应用到当前曲目
internal suspend fun applyLocalLyrics(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    uri: Uri,
): Boolean {
    return try {
        val updated = withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return@withContext null
            val lines = MusicMetadataCache.parseLyricsText(text)
            if (lines.isEmpty()) return@withContext null
            val path = MusicMetadataCache.saveLyrics(context, track.title, track.artist, lines).orEmpty()
            if (path.isBlank()) return@withContext null
            embedLyricsText(context, track, MusicMetadataCache.encodeLyrics(lines))
            track.copy(lyricCachePath = path, lyricLines = lines, lyricFailed = false)
        } ?: return false
        withContext(Dispatchers.Main) {
            playbackState.updateTrack(updated)
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "导入本地歌词失败: 歌曲=${track.title}", e)
        false
    }
}

// 编辑歌词行原文：整行按增强 LRC 文本（时间戳/逐字/翻译）重新解析后替换原行，写回缓存并刷新
internal suspend fun applyLyricsLineEdit(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    index: Int,
    rawText: String,
): Boolean {
    return try {
        val updated = withContext(Dispatchers.IO) {
            val edited = MusicMetadataCache.parseLyricsText(rawText)
            if (edited.isEmpty()) return@withContext null
            val lines = track.lyricLines.toMutableList()
            if (index !in lines.indices) return@withContext null
            // 编辑结果可能拆分为多行，整体替换原位置并维持时间序
            lines.removeAt(index)
            lines.addAll(index, edited)
            val sorted = lines.sortedBy { it.timeMs }
            val path = MusicMetadataCache.saveLyrics(context, track.title, track.artist, sorted).orEmpty()
            if (path.isBlank()) return@withContext null
            track.copy(lyricCachePath = path, lyricLines = sorted, lyricFailed = false)
        } ?: return false
        withContext(Dispatchers.Main) {
            playbackState.updateTrack(updated)
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "编辑歌词原文失败: 歌曲=${track.title}", e)
        false
    }
}

internal suspend fun searchCoverCandidates(
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    source: MusicSearchSource,
) {
    playbackState.isCoverSearching = true
    playbackState.coverCandidates = emptyList()
    try {
        playbackState.coverCandidates = searchSingleSourceCandidates(sourceOf(source), track.title, track.artist)
            .filter { matchesTrackTitle(track.title, it) && !it.coverUrl.isNullOrBlank() }
            .take(30)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "搜索封面候选失败: 歌曲=${track.title}", e)
        playbackState.coverCandidates = emptyList()
    } finally {
        playbackState.isCoverSearching = false
    }
}

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
            searchSingleSourceCandidates(sourceOf(source), track.title, track.artist)
        } else proxyResults
        playbackState.losslessUpgradeCandidates = candidates
            .filter { matchesTrackTitle(track.title, it) }
            .take(30)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "搜索无损升级候选失败: 歌曲=${track.title}", e)
        playbackState.losslessUpgradeCandidates = emptyList()
    } finally {
        playbackState.isLosslessUpgradeSearching = false
    }
}

internal suspend fun applyCoverCandidate(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    candidate: NeteaseSongSearchResult,
): Boolean {
    return try {
        val writeSuccess = withContext(Dispatchers.IO) {
            val bytes = NeteaseMusicApi.loadCoverBytes(candidate.coverUrl.orEmpty()) ?: return@withContext false
            // 手动刷新封面：按音频容器格式原生写入元数据；系统据此重建封面略缩图，
            // 显示端只读系统略缩图，不再落盘应用自建封面缓存
            MusicMetadataWriter.writeCover(context, track, bytes)
        }
        if (!writeSuccess) return false
        withContext(Dispatchers.Main) {
            // 封面已写入音频文件：清掉在线封面地址，转由系统略缩图提供显示
            playbackState.updateTrack(track.copy(neteaseId = candidate.id, neteaseCoverUrl = ""))
            playbackState.bumpCoverRevision()
            playbackState.coverCandidates = emptyList()
        }
        true
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "应用封面候选失败: 歌曲=${track.title} 路径=${track.path}", e)
        false
    }
}

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
            context,
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
            // 内置平台按页请求，首屏一页
            val results = runCatching { sourceOf(playbackState.searchSource).search(query, 1, SEARCH_PAGE_SIZE) }
                .getOrDefault(emptyList())
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
        CrashLogManager.logException("MusicPanelSearchLogic", "搜索歌曲失败", e)
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
        context,
        playbackState.searchSource,
        query,
        page = page,
        pageSize = SEARCH_PAGE_SIZE,
    )
    return if (proxyResults != null) {
        proxyResults
    } else {
        runCatching { sourceOf(playbackState.searchSource).search(query, page, SEARCH_PAGE_SIZE) }
            .getOrDefault(emptyList())
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
        CrashLogManager.logException("MusicPanelSearchLogic", "加载更多搜索结果失败", e)
    } finally {
        playbackState.isLoadingMore = false
        playbackState.searchLoadJob = null
    }
}

// 代理搜索结果的封面为逐条经 pic 动作换取：串行补齐前 N 条，控制聚合接口调用频率
private fun fillProxySearchCovers(playbackState: MusicPlaybackState, context: Context) {
    val pending = playbackState.searchResults
        .filter { it.coverUrl.isNullOrBlank() && !it.coverId.isNullOrBlank() }
        .take(MAX_PROXY_COVER_FILL)
    if (pending.isEmpty()) return
    playbackState.playbackScope.launch {
        pending.forEach { result ->
            val url = ProxySourceEngine.coverUrl(context, result) ?: return@forEach
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

// 代理搜索结果封面逐条换取的条数上限
private const val MAX_PROXY_COVER_FILL = 8

internal suspend fun downloadAndPlay(
    context: Context,
    playbackState: MusicPlaybackState,
    result: NeteaseSongSearchResult,
    url: String,
    metadataEnricher: MetadataEnricher,
    playlistRefresher: PlaylistRefresher,
) {
    val trackId = result.id + 1000000L
    val track = MusicTrack(
        id = trackId,
        path = "",
        audioUri = url,
        title = result.title,
        artist = result.artist,
        duration = result.duration,
        albumId = 0L,
        // 仅网易云结果写入 neteaseId，供播放失败重试时匹配原曲
        neteaseId = if (result.source == MusicSearchSource.NETEASE) result.id else 0L,
        neteaseCoverUrl = result.coverUrl.orEmpty(),
        isOnlinePlay = true,
    )

    withContext(Dispatchers.Main) {
        val existingIndex = playbackState.playlist.indexOfFirst { it.id == trackId }
        val targetIndex = if (existingIndex >= 0) {
            existingIndex
        } else {
            playbackState.playlist = playbackState.playlist + track
            playbackState.playlist.size - 1
        }
        playbackState.currentIndex = targetIndex
        playbackState.currentTrack = playbackState.playlist[targetIndex]
        playbackState.persistPlaylist()
        playTrackAt(context, playbackState, targetIndex)
    }

    // 在线结果后台加载歌词：各平台各有歌词接口。
    // 以 async 返回增强 LRC 文本，供缓存流程把歌词与标题/艺术家/封面一次内嵌进音频文件；
    // 异常已在块内捕获并返回 null，不会向作用域抛出
    val lyricsJob = playbackState.playbackScope.async(Dispatchers.IO) {
        try {
            val lines = ProxySourceEngine.lyricLines(context, result.source, result)
                ?: when (result.source) {
                    MusicSearchSource.NETEASE -> NeteaseMusicApi.lyric(result.id).lines
                    MusicSearchSource.QQ -> QQMusicApi.lyricLines(result).orEmpty()
                    MusicSearchSource.KUGOU -> KugouMusicApi.lyricLines(result).orEmpty()
                    MusicSearchSource.KUWO -> KuwoMusicApi.lyricLines(result).orEmpty()
                    MusicSearchSource.MIGU -> MiguMusicApi.lyricLines(result).orEmpty()
                }
            if (lines.isEmpty()) return@async null
            val lyricPath = MusicMetadataCache.saveLyrics(context, result.title, result.artist, lines).orEmpty()
            withContext(Dispatchers.Main) {
                // updateTrack 同步回写并持久化引用：仅改内存态会丢失持久化引用，
                // 进程被杀后重启清理会把刚落盘的歌词缓存当作孤儿误删
                val track = playbackState.playlist.firstOrNull { it.id == trackId } ?: return@withContext
                // 歌词已就位，清掉此前的失败标记，否则缓存文件被删后不再重建
                playbackState.updateTrack(
                    track.copy(lyricCachePath = lyricPath, lyricLines = lines, lyricFailed = false)
                )
            }
            // 与歌词缓存文件同一份增强 LRC 文本，内嵌后可被内嵌歌词读取器原样解析回来
            MusicMetadataCache.encodeLyrics(lines)
        } catch (e: Exception) {
            CrashLogManager.logException("MusicPanelSearchLogic", "获取在线歌词失败", e)
            null
        }
    }

    // 在线播放时同步下载封面原图：仅以字节形式交回缓存流程，由其在缓存完成后内嵌进音频文件，
    // 系统随即为该文件生成封面略缩图（显示端只读系统略缩图，不落盘自建封面缓存）。
    // 在线播放由用户主动选择曲目触发，属用户决策下的联网补齐；自动补全路径不联网
    val coverJob = playbackState.playbackScope.async(Dispatchers.IO) {
        try {
            // 代理音源优先按 coverId 换取封面，失败时回退搜索结果的封面直链
            ProxySourceEngine.coverBytes(context, result)
                ?: run {
                    val coverUrl = result.coverUrl?.takeIf { it.isNotBlank() } ?: return@run null
                    NeteaseMusicApi.loadCoverBytes(coverUrl)
                }
        } catch (e: Exception) {
            CrashLogManager.logException("MusicPanelSearchLogic", "下载在线封面失败: 歌曲=${result.title}", e)
            null
        }
    }

    playbackState.playbackScope.launch(Dispatchers.IO) {
        cacheToDownloads(
            context, result, url, trackId, playbackState,
            metadataEnricher, playlistRefresher, coverJob, lyricsJob,
        )
    }
}

internal fun normalizeTitle(value: String): String {
    return value.lowercase()
        .replace(Regex("""[\s　（）()\[\]【】「」『』《》〈〉、，。！？"'""'']+"""), "")
        .trim()
}

internal suspend fun enrichOnlineMetadata(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    result: NeteaseSongSearchResult,
) {
    if (track.neteaseId != 0L && track.lyricLines.isNotEmpty()) return
    try {
        val lyric = NeteaseMusicApi.lyric(result.id)
        val lyricPath = if (lyric.lines.isNotEmpty()) {
            MusicMetadataCache.saveLyrics(context, track.title, track.artist, lyric.lines).orEmpty()
        } else ""
        embedLyricsText(context, track, MusicMetadataCache.encodeLyrics(lyric.lines))
        withContext(Dispatchers.Main) {
            // updateTrack 同步回写并持久化引用，避免进程被杀后重启清理误删刚下载的歌词缓存
            val track = playbackState.playlist.firstOrNull { it.id == track.id } ?: return@withContext
            playbackState.updateTrack(
                track.copy(
                    neteaseId = result.id,
                    neteaseCoverUrl = result.coverUrl.orEmpty(),
                    lyricCachePath = lyricPath,
                    lyricLines = lyric.lines,
                    // 取到歌词即清掉失败标记；本次没取到则保留原标记，不清也不新置
                    lyricFailed = lyric.lines.isEmpty() && track.lyricFailed
                )
            )
        }
    } catch (e: Exception) {
        CrashLogManager.logException("MusicPanelSearchLogic", "获取在线元数据失败", e)
    }
}

// 本地曲库命中同曲时直接播放；命中返回 true
internal suspend fun tryPlayLocalMatch(
    target: NeteaseSongSearchResult,
    playbackState: MusicPlaybackState,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
): Boolean {
    val normalizedTitle = normalizeTitle(target.title)
    val normalizedArtist = normalizeTitle(target.artist)
    val localMatch = playbackState.playlist.firstOrNull { t ->
        t.path.isNotBlank() &&
        normalizeTitle(t.title) == normalizedTitle &&
        (normalizedArtist.isBlank() || normalizeTitle(t.artist) == normalizedArtist)
    } ?: return false
    val idx = playbackState.playlist.indexOfFirst { it.id == localMatch.id }
    if (idx < 0) return false
    if (target.source == MusicSearchSource.NETEASE) {
        scope.launch {
            enrichOnlineMetadata(context, playbackState, localMatch, target)
        }
    }
    playbackState.errorMsg = null
    playbackState.currentIndex = idx
    playbackState.currentTrack = playbackState.playlist[idx]
    playbackState.isSearchMode = false
    playbackState.showSearchResults = false
    playbackState.searchQuery = ""
    playbackState.searchResults = emptyList()
    playbackState.searchPending = emptyList()
    playbackState.searchPendingFull = false
    playTrackAt(context, playbackState, idx)
    return true
}

internal suspend fun playSearchResult(
    target: NeteaseSongSearchResult,
    playbackState: MusicPlaybackState,
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    metadataEnricher: MetadataEnricher,
    playlistRefresher: PlaylistRefresher,
) {
    if (tryPlayLocalMatch(target, playbackState, context, scope)) return

    playbackState.pendingSearchResults = emptyList()

    // 代理音源优先解析播放地址，失败时回退内置解析；缓存下载沿用同一直链
    val playTarget: NeteaseSongSearchResult
    val url: String?
    when (target.source) {
        MusicSearchSource.QQ -> {
            playTarget = target
            url = ProxySourceEngine.resolveUrl(context, target, MusicQuality.HIGH)
                ?: withContext(Dispatchers.IO) { QQMusicApi.songUrl(target.sourceId.orEmpty()) }
        }
        MusicSearchSource.KUGOU -> {
            playTarget = target
            url = ProxySourceEngine.resolveUrl(context, target, MusicQuality.HIGH)
                ?: withContext(Dispatchers.IO) { KugouMusicApi.songUrl(target.sourceId.orEmpty()) }
        }
        MusicSearchSource.KUWO -> {
            playTarget = target
            url = ProxySourceEngine.resolveUrl(context, target, MusicQuality.HIGH)
                ?: withContext(Dispatchers.IO) { KuwoMusicApi.songUrl(target.sourceId.orEmpty()) }
        }
        MusicSearchSource.MIGU -> {
            playTarget = target
            url = ProxySourceEngine.resolveUrl(context, target, MusicQuality.HIGH)
                ?: withContext(Dispatchers.IO) { MiguMusicApi.songUrl(target.sourceId.orEmpty()) }
        }
        MusicSearchSource.NETEASE -> {
            val fullResult = if (target.coverUrl.isNullOrBlank() || target.duration <= 0L) {
                withContext(Dispatchers.IO) {
                    NeteaseMusicApi.songDetail(target.id) ?: target
                }
            } else target
            playTarget = fullResult
            url = ProxySourceEngine.resolveUrl(context, fullResult, MusicQuality.HIGH)
                ?: withContext(Dispatchers.IO) {
                    NeteaseMusicApi.getSongUrlWithFallback(fullResult.id)
                }
        }
    }

    if (url != null) {
        playbackState.errorMsg = null
        playbackState.closeSearchResultsOnReady = true
        downloadAndPlay(context, playbackState, playTarget, url, metadataEnricher, playlistRefresher)
    } else {
        playbackState.errorMsg = context.getString(R.string.music_panel_play_error)
        playbackState.pendingSearchResults = emptyList()
    }
}

// 按指定音质解析在线播放地址；代理音源优先，返回 null 表示该音质不可用
internal suspend fun resolvePlayUrlByQuality(
    context: Context,
    target: NeteaseSongSearchResult,
    quality: MusicQuality,
): String? = withContext(Dispatchers.IO) {
    ProxySourceEngine.resolveUrl(context, target, quality) ?: when (target.source) {
        MusicSearchSource.NETEASE -> NeteaseMusicApi.songUrl(target.id, quality)
        MusicSearchSource.QQ -> QQMusicApi.songUrl(target.sourceId.orEmpty(), quality)
        MusicSearchSource.KUGOU -> KugouMusicApi.songUrl(target.sourceId.orEmpty())
        MusicSearchSource.KUWO -> KuwoMusicApi.songUrl(target.sourceId.orEmpty(), quality)
        MusicSearchSource.MIGU -> MiguMusicApi.songUrl(target.sourceId.orEmpty(), quality)
    }
}

// 按用户选定音质播放在线搜索结果；URL 解析成功即加入播放列表开始播放，
// 并标记待确认曲目交由播放器回调判定成败（失败时移除曲目、保留音质对话框）
internal suspend fun playSearchResultWithQuality(
    target: NeteaseSongSearchResult,
    quality: MusicQuality,
    playbackState: MusicPlaybackState,
    context: Context,
    metadataEnricher: MetadataEnricher,
    playlistRefresher: PlaylistRefresher,
): Boolean {
    val url = resolvePlayUrlByQuality(context, target, quality) ?: return false
    playbackState.pendingQualityPlayTrackId = target.id + 1000000L
    playbackState.closeSearchResultsOnReady = true
    downloadAndPlay(context, playbackState, target, url, metadataEnricher, playlistRefresher)
    return true
}
