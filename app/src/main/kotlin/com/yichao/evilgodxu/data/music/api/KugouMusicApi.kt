package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.log.CrashLogManager
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 酷狗音乐在线源：官方 song_search_v2 搜索 + trackercdn 取播放地址 + 歌词接口。
 * 歌曲标识为文件 hash 字符串，转成稳定数字 id 存入搜索结果。
 */
internal object KugouMusicApi : OnlineMusicSource {

    // 默认榜单：酷狗音乐 TOP500 热门榜
    private const val CHART_RANK_ID = "8888"

    override suspend fun search(keyword: String, page: Int, pageSize: Int): List<NeteaseSongSearchResult> = withContext(Dispatchers.IO) {
        try {
            val url = "https://songsearch.kugou.com/song_search_v2?keyword=${URLEncoder.encode(keyword, "UTF-8")}" +
                    "&page=$page&pagesize=$pageSize&platform=WebFilter&format=json"
            val root = JSONObject(get(url))
            val lists = root.optJSONObject("data")?.optJSONArray("lists") ?: JSONArray()
            List(lists.length()) { index -> songResult(lists.getJSONObject(index)) }
        } catch (e: Exception) {
            CrashLogManager.logException("KugouMusicApi", "搜索歌曲失败", e)
            emptyList()
        }
    }

    /**
     * 内置榜单解析：TOP500 热门榜。
     *
     * 该 CDN 的证书不含本站域名（mobilecdnbj.kugou.com），走 https 会因证书校验直接断连，
     * 只能按明文 http 请求 —— 应用已全局放行明文，接口本身也只提供 http 站点。
     */
    override suspend fun chart(limit: Int): List<NeteaseSongSearchResult> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        try {
            val url = "http://mobilecdnbj.kugou.com/api/v3/rank/song?rankid=$CHART_RANK_ID" +
                    "&page=1&pagesize=$limit&version=9108"
            val info = JSONObject(get(url)).optJSONObject("data")?.optJSONArray("info") ?: JSONArray()
            List(minOf(info.length(), limit)) { index -> rankSong(info.getJSONObject(index)) }
                .filter { it.title.isNotBlank() }
        } catch (e: Exception) {
            CrashLogManager.logException("KugouMusicApi", "解析榜单失败", e)
            emptyList()
        }
    }

    /** 榜单条目映射：歌手在 `authors`、封面在 `album_sizable_cover`，均与搜索结果的字段名不同 */
    private fun rankSong(item: JSONObject): NeteaseSongSearchResult {
        val hash = item.optString("hash").ifBlank { item.optString("320hash") }
        val authors = item.optJSONArray("authors") ?: JSONArray()
        val artist = List(authors.length()) { authors.getJSONObject(it).optString("author_name") }
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        // 封面地址带 {size} 占位符，替换为实际尺寸段
        val cover = item.optString("album_sizable_cover")
            .takeIf { it.isNotBlank() }
            ?.replace("{size}", "300")
            ?.let { if (it.startsWith("http://")) "https://${it.removePrefix("http://")}" else it }
        return NeteaseSongSearchResult(
            id = stableIdFromString(hash),
            title = item.optString("songname").ifBlank { titleFromFilename(item.optString("filename")) },
            artist = artist,
            coverUrl = cover,
            coverThumbUrl = cover,
            // duration 为秒
            duration = item.optLong("duration", 0L) * 1000L,
            source = MusicSearchSource.KUGOU,
            sourceId = hash,
        )
    }

    // 榜单与搜索的歌曲字段同源，统一映射为搜索结果模型
    private fun songResult(item: JSONObject): NeteaseSongSearchResult {
        val hash = item.optString("hash").ifBlank { item.optString("FileHash") }
        val filename = item.optString("filename").ifBlank { item.optString("FileName") }
        val rawTitle = item.optString("songname").ifBlank { item.optString("SongName") }
        val artist = item.optString("singername").ifBlank { item.optString("SingerName") }
        var cover = item.optJSONObject("trans_param")?.optString("union_cover")
            ?.takeIf { it.isNotBlank() }
            ?: item.optString("cover_url").takeIf { it.isNotBlank() }
            ?: item.optString("Image").takeIf { it.isNotBlank() }
        if (cover != null && cover.contains("{size}")) cover = cover.replace("{size}", "300")
        // 封面 CDN 返回 http 明文，统一转 https
        if (cover != null && cover.startsWith("http://")) {
            cover = "https://${cover.removePrefix("http://")}"
        }
        // duration 为秒，timelen 为毫秒，二者取其一
        val durationSec = item.optString("duration").toLongOrNull()
            ?: item.optLong("Duration", 0L)
        val timelen = item.optLong("timelen", 0L)
        return NeteaseSongSearchResult(
            id = stableIdFromString(hash),
            title = rawTitle.ifBlank { titleFromFilename(filename) },
            artist = artist,
            coverUrl = cover,
            coverThumbUrl = cover,
            duration = if (durationSec > 0) durationSec * 1000L else timelen,
            source = MusicSearchSource.KUGOU,
            sourceId = hash
        )
    }

    /** 获取播放地址：trackercdn 的 key 为 hash + "kgcloudv2" 的 MD5 */
    suspend fun songUrl(hash: String): String? = withContext(Dispatchers.IO) {
        if (hash.isBlank()) return@withContext null
        try {
            val key = md5(hash + "kgcloudv2")
            val url = "https://trackercdn.kugou.com/i/v2/?cdnBackup=1&behavior=download&pid=1&cmd=21&appid=1001&hash=$hash&key=$key"
            val root = JSONObject(get(url))
            optStringOrFirst(root, "url")
                ?: optStringOrFirst(root, "backup_url")
                ?: optStringOrFirst(root, "backupUrl")
                ?: optStringOrFirst(root, "mp3Url")
                ?: optStringOrFirst(root, "backupMp3Url")
        } catch (e: Exception) {
            CrashLogManager.logException("KugouMusicApi", "获取播放地址失败", e)
            null
        }
    }

    /** 获取歌词：先按关键词/hash 搜候选，再下载 base64 编码的 LRC */
    suspend fun lyricLines(result: NeteaseSongSearchResult): List<LyricLine>? = withContext(Dispatchers.IO) {
        val hash = result.sourceId ?: return@withContext null
        try {
            val keyword = if (result.artist.isBlank()) result.title else "${result.artist} - ${result.title}"
            val duration = result.duration / 1000L
            val searchUrl = "https://lyrics.kugou.com/search?keyword=${URLEncoder.encode(keyword, "UTF-8")}" +
                    "&duration=$duration&hash=$hash"
            val candidate = JSONObject(get(searchUrl)).optJSONArray("candidates")
                ?.optJSONObject(0) ?: return@withContext null
            val id = candidate.optString("id")
            val accesskey = candidate.optString("accesskey")
            if (id.isBlank() || accesskey.isBlank()) return@withContext null
            val dlUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accesskey&fmt=lrc&charset=utf8"
            val content = JSONObject(get(dlUrl)).optString("content").ifBlank { return@withContext null }
            val lrc = String(Base64.getDecoder().decode(content), Charsets.UTF_8)
            parseLrcText(lrc).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            CrashLogManager.logException("KugouMusicApi", "获取歌词失败", e)
            null
        }
    }

    // 酷狗搜索结果文件名形如 "歌手 - 歌名.mp3"，无 songname 字段时从中提取歌名
    private fun titleFromFilename(filename: String): String {
        val base = filename.removeSuffix(".mp3")
        val idx = base.indexOf(" - ")
        return if (idx >= 0) base.substring(idx + 3) else base
    }

    // url 字段可能是字符串也可能是数组，统一取出第一个非空值
    private fun optStringOrFirst(obj: JSONObject, key: String): String? {
        val value = obj.opt(key) ?: return null
        return when (value) {
            is JSONArray -> value.optString(0).takeIf { it.isNotBlank() }
            else -> value.toString().takeIf { it.isNotBlank() }
        }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MusicHttpClient.MUSIC_USER_AGENT)
            .build()
        return MusicHttpClient.client.newCall(request).execute().use { resp ->
            val response = resp.body.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            response
        }
    }
}
