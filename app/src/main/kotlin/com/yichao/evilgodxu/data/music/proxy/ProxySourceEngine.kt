package com.yichao.evilgodxu.data.music.proxy

import android.content.Context
import com.yichao.evilgodxu.data.music.api.builtInSourceOf
import com.yichao.evilgodxu.data.music.api.mergeTranslations
import com.yichao.evilgodxu.data.music.api.MusicHttpClient
import com.yichao.evilgodxu.data.music.api.MusicQuality
import com.yichao.evilgodxu.data.music.api.parseLrcText
import com.yichao.evilgodxu.data.music.api.stableIdFromString
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.log.CrashLogManager
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

// 代理音源执行引擎：按规范构建请求、解析响应并映射到在线搜索/播放统一模型。
// 所有入口在对应的平台/动作未配置或请求失败时返回 null，由调用方回退内置音源。
internal object ProxySourceEngine {

    private const val SEARCH_COUNT = 20
    // 歌单分页每页条数与最大页数：防止接口异常时无限翻页
    private const val PLAYLIST_PAGE_SIZE = 50
    private const val MAX_PLAYLIST_PAGES = 100

    suspend fun search(
        context: Context,
        source: MusicSearchSource,
        keyword: String,
        page: Int = 1,
        pageSize: Int = SEARCH_COUNT,
    ): List<NeteaseSongSearchResult>? = withContext(Dispatchers.IO) {
        val action = ProxySourceStore.platformSpec(context, source.key)?.search
            ?: return@withContext null
        val body = executeAction(
            action,
            mapOf("keyword" to keyword, "page" to page.toString(), "count" to pageSize.toString()),
        ) ?: return@withContext null
        val listPath = action.result.list
        val array = if (listPath == null) {
            body as? JSONArray
        } else {
            ProxyJsonPath.resolve(body, listPath) as? JSONArray
        } ?: return@withContext null
        List(array.length()) { index -> mapSearchResult(array.opt(index), action, source) }
            .filterNotNull()
            .distinctBy { it.id }
            .also { if (it.isEmpty()) return@withContext null }
    }

    // 按平台搜索的统一入口：代理音源优先，未配置或解析失败时回退内置实现。
    // 自定义平台没有内置实现，代理不可用时只能返回空列表
    suspend fun searchPlatform(
        context: Context,
        source: MusicSearchSource,
        keyword: String,
        page: Int = 1,
        pageSize: Int = SEARCH_COUNT,
    ): List<NeteaseSongSearchResult> {
        search(context, source, keyword, page, pageSize)?.let { return it }
        return builtInSourceOf(source)?.let { builtIn ->
            runCatching { builtIn.search(keyword, page, pageSize) }.getOrDefault(emptyList())
        }.orEmpty()
    }

    // 按歌单 ID 拉取歌单歌曲：跨页循环直至拉满或接口无新条目，按 id 去重
    suspend fun fetchPlaylist(
        context: Context,
        source: MusicSearchSource,
        playlistId: String,
    ): ProxyPlaylistResult? {
        val action = ProxySourceStore.platformSpec(context, source.key)?.playlist
            ?: return null
        val songs = mutableListOf<NeteaseSongSearchResult>()
        var name = ""
        var page = 1
        while (page <= MAX_PLAYLIST_PAGES) {
            val body = executeAction(
                action,
                mapOf(
                    "playlistId" to playlistId,
                    "page" to page.toString(),
                    "count" to PLAYLIST_PAGE_SIZE.toString(),
                ),
            ) ?: break
            if (name.isBlank()) name = resolveString(body, action.result.playlistName).orEmpty()
            val listPath = action.result.list
            val array = if (listPath == null) {
                body as? JSONArray
            } else {
                ProxyJsonPath.resolve(body, listPath) as? JSONArray
            } ?: break
            val mapped = List(array.length()) { index -> mapSearchResult(array.opt(index), action, source) }
                .filterNotNull()
            if (mapped.isEmpty()) break
            // 接口可能重复返回同一页：无新条目即视为已拉完
            val merged = (songs + mapped).distinctBy { it.id }
            if (merged.size == songs.size) break
            songs.clear()
            songs.addAll(merged)
            val total = resolveLong(body, action.result.total) ?: 0L
            if (total > 0 && songs.size >= total) break
            // 本页不足一页即为末页
            if (mapped.size < PLAYLIST_PAGE_SIZE) break
            page++
        }
        return songs.takeIf { it.isNotEmpty() }?.let { ProxyPlaylistResult(name, it) }
    }

    // 按音质档位解析播放直链：直链同时用于播放与缓存下载
    suspend fun resolveUrl(
        context: Context,
        target: NeteaseSongSearchResult,
        quality: MusicQuality,
    ): String? = withContext(Dispatchers.IO) {
        val action = ProxySourceStore.platformSpec(context, target.source.key)?.url
            ?: return@withContext null
        val qualityValue = action.qualities[quality]
        // qualities 未声明时任意音质都执行动作（{quality} 渲染为空串），适配固定直链音源
        if (qualityValue == null && action.qualities.isNotEmpty()) return@withContext null
        val body = executeAction(
            action,
            placeholders(target) + ("quality" to (qualityValue ?: "")),
        ) ?: return@withContext null
        // result.url 未配置时视为响应体本身即直链
        resolveString(body, action.result.url ?: "$") ?: return@withContext null
    }

    suspend fun lyricLines(
        context: Context,
        source: MusicSearchSource,
        target: NeteaseSongSearchResult,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val action = ProxySourceStore.platformSpec(context, source.key)?.lyric
            ?: return@withContext null
        val body = executeAction(action, placeholders(target)) ?: return@withContext null
        val lrc = resolveString(body, action.result.lyric) ?: return@withContext null
        val tlyric = resolveString(body, action.result.tlyric).orEmpty()
        mergeTranslations(parseLrcText(lrc), parseLrcText(tlyric))
    }

    // 换取封面直链：优先搜索结果中的 coverUrl，否则经 pic 动作按 coverId 解析
    suspend fun coverUrl(context: Context, target: NeteaseSongSearchResult): String? =
        withContext(Dispatchers.IO) {
            target.coverUrl?.takeIf { it.isNotBlank() }?.let { return@withContext it }
            val coverId = target.coverId?.takeIf { it.isNotBlank() } ?: return@withContext null
            val action = ProxySourceStore.platformSpec(context, target.source.key)?.pic
                ?: return@withContext null
            val body = executeAction(action, placeholders(target)) ?: return@withContext null
            // 响应体即图片直链时 result.url 同样可省略
            resolveString(body, action.result.url ?: "$") ?: return@withContext null
        }

    // 下载封面字节：供在线播放时落盘缓存封面
    suspend fun coverBytes(context: Context, target: NeteaseSongSearchResult): ByteArray? =
        withContext(Dispatchers.IO) {
            val url = coverUrl(context, target) ?: return@withContext null
            try {
                MusicHttpClient.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body.bytes() else null
                }
            } catch (e: Exception) {
                CrashLogManager.logException("ProxySource", "下载代理封面失败", e)
                null
            }
        }

    private fun mapSearchResult(
        item: Any?,
        action: ProxyActionSpec,
        source: MusicSearchSource,
    ): NeteaseSongSearchResult? {
        if (item !is JSONObject) return null
        val result = action.result
        val title = resolveString(item, result.title) ?: return null
        val idLong = resolveLong(item, result.id)
        val sourceId = resolveString(item, result.sourceId) ?: idLong?.toString()
        val id = idLong ?: sourceId?.let { stableIdFromString(it) } ?: return null
        return NeteaseSongSearchResult(
            id = id,
            title = title,
            artist = resolveJoinString(item, result.artist).orEmpty(),
            coverUrl = resolveString(item, result.coverUrl),
            duration = resolveLong(item, result.duration) ?: 0L,
            source = source,
            sourceId = sourceId,
            coverId = resolveString(item, result.coverId),
        )
    }

    private fun placeholders(target: NeteaseSongSearchResult): Map<String, String> {
        val id = target.id.toString()
        return mapOf(
            "id" to id,
            "sourceId" to (target.sourceId ?: id),
            "coverId" to target.coverId.orEmpty(),
            "title" to target.title,
            "artist" to target.artist,
            "album" to "",
            "duration" to target.duration.toString(),
        )
    }

    private suspend fun executeAction(
        action: ProxyActionSpec,
        values: Map<String, String>,
    ): Any? = withContext(Dispatchers.IO) {
        try {
            val url = buildUrl(action, values)
            val builder = Request.Builder().url(url)
            action.headers.forEach { (key, value) ->
                val rendered = render(value, values)
                if (rendered.isNotEmpty()) builder.header(key, rendered)
            }
            val request = if (action.isPost) {
                val form = action.params.entries.joinToString("&") { (key, value) ->
                    "${encode(key)}=${encode(render(value, values))}"
                }
                builder.post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
            } else {
                builder.get().build()
            }
            MusicHttpClient.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    null
                } else {
                    val raw = resp.body.string()
                    when {
                        raw.isBlank() -> null
                        // 响应体即直链（纯文本 URL）：原样返回，避免 JSON 解析在 =、# 处截断查询参数
                        raw.trimStart().startsWith("http://", ignoreCase = true) ||
                            raw.trimStart().startsWith("https://", ignoreCase = true) -> raw.trim()
                        else -> JSONTokener(raw).nextValue()
                    }
                }
            }
        } catch (e: Exception) {
            CrashLogManager.logException("ProxySource", "代理音源请求失败: ${action.url}", e)
            null
        }
    }

    private fun buildUrl(action: ProxyActionSpec, values: Map<String, String>): String {
        val base = render(action.url, values)
        val queryValues = if (action.isPost) action.query else action.params
        if (queryValues.isEmpty()) return base
        val query = queryValues.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(render(value, values))}"
        }
        return base + if (base.contains("?")) "&" else "?" + query
    }

    private fun render(template: String, values: Map<String, String>): String {
        var result = template
        values.forEach { (key, value) -> result = result.replace("{$key}", value) }
        return result
    }

    private fun resolveString(root: Any, path: String?): String? {
        val value = ProxyJsonPath.resolve(root, path) ?: return null
        return when (value) {
            is String -> value
            is JSONObject, is JSONArray -> null
            else -> value.toString()
        }?.takeIf { it.isNotBlank() }
    }

    // 歌手字段：字符串数组按 " / " 拼接，单个字符串原样返回
    private fun resolveJoinString(root: Any, path: String?): String? {
        val value = ProxyJsonPath.resolve(root, path) ?: return null
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { index ->
                val item = value.opt(index)
                if (item is String) item.takeIf { it.isNotBlank() } else null
            }.joinToString(" / ")
            is String -> value.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun resolveLong(root: Any, path: String?): Long? {
        val value = ProxyJsonPath.resolve(root, path) ?: return null
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
