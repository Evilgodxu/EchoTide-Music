package com.yichao.evilgodxu.data.music.proxy

import android.content.Context
import android.net.Uri
import com.yichao.evilgodxu.data.music.api.MusicHttpClient
import com.yichao.evilgodxu.data.music.api.MusicQuality
import com.yichao.evilgodxu.data.music.api.NeteaseMusicApi
import com.yichao.evilgodxu.data.music.api.adaptiveCandidates
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.download.downloadTrackToLibrary
import com.yichao.evilgodxu.data.music.panel.normalizeTitle
import com.yichao.evilgodxu.data.music.panel.resolvePlayUrlByQuality
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

// 分享链接解析出的歌单引用：平台 + 歌单 ID
internal data class RemotePlaylistLink(
    val source: MusicSearchSource,
    val playlistId: String,
)

// 歌单同步失败原因
internal enum class SyncFailure { FETCH_FAILED, NO_DOWNLOAD, LIBRARY_MATCH_FAILED }

// 同步统计：已存在跳过 / 新下载 / 下载失败
internal data class SyncStats(
    val existingCount: Int,
    val downloadedCount: Int,
    val failedCount: Int,
)

// 歌单同步结果：Success 携带歌单名称、入库曲目 ID（含本地已存在与新下载）与统计
internal sealed interface PlaylistSyncResult {
    data class Success(
        val playlistName: String,
        val trackIds: List<Long>,
        val stats: SyncStats,
    ) : PlaylistSyncResult

    data class Failure(val reason: SyncFailure) : PlaylistSyncResult
}

// 歌单同步：解析分享链接 → 拉取歌单（代理音源优先，未配置或失败回退内置解析）→
// 本地同名跳过 → 按选定音质自适应匹配可用音质下载入库
internal object PlaylistSyncer {

    // 解析分享链接为平台 + 歌单 ID；直接解析失败时尝试跟随重定向
    suspend fun parseLink(context: Context, raw: String): RemotePlaylistLink? {
        parseLinkDirect(raw)?.let { return it }
        val resolved = resolveRedirect(context, raw) ?: return null
        return parseLinkDirect(resolved)
    }

    private fun parseLinkDirect(raw: String): RemotePlaylistLink? {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        return when {
            host.contains("163") -> RemotePlaylistLink(MusicSearchSource.NETEASE, idParam(uri) ?: return null)
            host.contains("qq.com") -> RemotePlaylistLink(
                MusicSearchSource.QQ,
                (idParam(uri) ?: pathId(uri.path, "playlist")) ?: return null,
            )
            host.contains("kuwo.cn") -> RemotePlaylistLink(
                MusicSearchSource.KUWO,
                (pathId(uri.path, "playlist_detail") ?: idParam(uri)) ?: return null,
            )
            else -> null
        }
    }

    // 读取 id 查询参数；hash 片段中的参数（music.163.com/#/playlist?id=）一并兼容
    private fun idParam(uri: Uri): String? {
        uri.getQueryParameter("id")
            ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
            ?.let { return it }
        val fragment = uri.fragment ?: return null
        return runCatching {
            Uri.parse("https://x/?$fragment").getQueryParameter("id")
                ?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
        }.getOrNull()
    }

    // 提取路径中的数字 ID，如 /playlist/123、/playlist_detail/123
    private fun pathId(path: String?, segment: String): String? {
        if (path.isNullOrBlank()) return null
        return Regex("$segment/(\\d+)").find(path)?.groupValues?.get(1)
    }

    // 跟随短链重定向，返回最终 URL
    private suspend fun resolveRedirect(context: Context, url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", MusicHttpClient.MUSIC_USER_AGENT)
                .build()
            MusicHttpClient.client.newCall(request).execute().use { resp ->
                resp.request.url.toString()
            }
        }.getOrNull()
    }

    // 拉取歌单：代理音源优先，未配置或失败时回退内置解析（仅网易云内置支持）
    suspend fun fetchRemote(context: Context, link: RemotePlaylistLink): ProxyPlaylistResult? {
        ProxySourceEngine.fetchPlaylist(context, link.source, link.playlistId)?.let { return it }
        val builtin = when (link.source) {
            MusicSearchSource.NETEASE -> NeteaseMusicApi.fetchPlaylist(link.playlistId)
            else -> null
        } ?: return null
        return ProxyPlaylistResult(builtin.name, builtin.songs)
    }

    // 同步：拉取歌单 → 本地同名跳过 → 逐首按选定音质自适应解析直链并下载 → 刷新曲库 → 返回入库曲目 ID
    suspend fun syncToLibrary(
        context: Context,
        state: MusicPlaybackState,
        link: RemotePlaylistLink,
        quality: MusicQuality,
        playlistRefresher: PlaylistRefresher,
        onProgress: (done: Int, total: Int, title: String) -> Unit,
    ): PlaylistSyncResult {
        val fetched = fetchRemote(context, link)
            ?: return PlaylistSyncResult.Failure(SyncFailure.FETCH_FAILED)
        val total = fetched.songs.size
        if (total == 0) return PlaylistSyncResult.Failure(SyncFailure.NO_DOWNLOAD)
        // 先刷新本地库，保证同名查重基于最新曲库
        playlistRefresher.refresh(context, state, restoreCurrent = true)
        // 本地曲目按归一化标题建索引：仅本地音频参与查重
        val localByTitle = state.libraryTracks
            .filter { it.isLocalAudioSource }
            .groupBy { normalizeTitle(it.title) }
        var existing = 0
        var failed = 0
        val trackIds = mutableListOf<Long>()
        val downloadedFiles = mutableListOf<String>()
        fetched.songs.forEachIndexed { index, song ->
            onProgress(index + 1, total, song.title)
            // 本地已有同名歌曲：跳过下载，直接复用其 ID
            val existingTrack = localByTitle[normalizeTitle(song.title)]?.firstOrNull()
            if (existingTrack != null) {
                existing++
                trackIds += existingTrack.id
                return@forEachIndexed
            }
            // 封面按代理音源 pic 动作换取，失败不影响下载
            val coverBytes = runCatching { ProxySourceEngine.coverBytes(context, song) }.getOrNull()
            val fileName = downloadBestQuality(context, song, quality, coverBytes)
            if (fileName != null) {
                downloadedFiles += fileName
            } else {
                failed++
                CrashLogManager.logException(
                    "PlaylistSyncer",
                    "歌单同步跳过曲目：各音质档位均不可用, 歌曲=${song.title} - ${song.artist}, 选定音质=$quality",
                )
            }
        }
        if (existing == 0 && downloadedFiles.isEmpty()) {
            return PlaylistSyncResult.Failure(SyncFailure.NO_DOWNLOAD)
        }
        // 刷新曲库使下载文件成为本地曲目，再按文件名匹配入库 ID
        playlistRefresher.refresh(context, state, restoreCurrent = true)
        val downloadedIds = mutableListOf<Long>()
        val unmatchedFiles = mutableListOf<String>()
        downloadedFiles.forEach { fileName ->
            val id = state.libraryTracks.firstOrNull { it.path.endsWith(fileName) }?.id
            if (id != null) downloadedIds += id else unmatchedFiles += fileName
        }
        trackIds += downloadedIds
        // 文件已落盘但曲库未收录：计入失败并留痕，避免歌单少歌却无任何提示
        if (unmatchedFiles.isNotEmpty()) {
            failed += unmatchedFiles.size
            CrashLogManager.logException(
                "PlaylistSyncer",
                "歌单同步下载完成但曲库未收录: ${unmatchedFiles.joinToString()}",
            )
        }
        if (existing == 0 && downloadedIds.isEmpty()) {
            return PlaylistSyncResult.Failure(SyncFailure.LIBRARY_MATCH_FAILED)
        }
        return PlaylistSyncResult.Success(
            playlistName = fetched.name,
            trackIds = trackIds.distinct(),
            stats = SyncStats(existing, downloadedIds.size, failed),
        )
    }

    // 逐档解析直链并下载，返回首个成功落盘的文件名。
    // 解析失败、地址非法、下载落盘失败（试听片段、签名过期、接口拒绝）都视为该档不可用并继续下一档，
    // 避免「解析到地址但实际不可下载」直接判该曲失败而不再尝试其它档位
    private suspend fun downloadBestQuality(
        context: Context,
        song: NeteaseSongSearchResult,
        requested: MusicQuality,
        coverBytes: ByteArray?,
    ): String? {
        requested.adaptiveCandidates().forEach { candidate ->
            val url = resolvePlayUrlByQuality(context, song, candidate) ?: return@forEach
            if (!url.isHttpUrl()) return@forEach
            downloadTrackToLibrary(context, song, url, coverBytes)?.let { return it }
        }
        return null
    }

    private fun String.isHttpUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
}
