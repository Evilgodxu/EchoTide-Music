package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.log.CrashLogManager
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 咪咕音乐在线源：官方 search_all.do 搜索 + listen-url 加密接口取播放地址。
 * 歌曲标识为 contentId|copyrightId|音质列表 的组合串，音质列表形如 SQ:2,HQ:2,PQ:2。
 */
internal object MiguMusicApi : OnlineMusicSource {

    private const val SEARCH_ENDPOINT = "https://c.musicapp.migu.cn/v1.0/content/search_all.do"
    private const val LISTEN_ENDPOINT = "https://c.musicapp.migu.cn/strategy/listen-url/h5/v2.4"
    private const val LYRIC_ENDPOINT = "https://app.c.nf.migu.cn/MIGUM3.0/strategy/pc/listen/v1.0"

    // 响应加密密钥与魔术头：命中时按 (密文 + seed - key[i]) 逐字节还原明文
    private val MIGU_KEY = "Jk8qzuePiJ1qE3mDYhLQ3T73DtDoAhLP".toByteArray()
    private val MAGIC = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0x01)

    private val MIGU_HEADERS = mapOf(
        "Origin" to "https://h5.nf.migu.cn",
        "Referer" to "https://h5.nf.migu.cn/",
        "ua" to "Android_migu",
        "version" to "6.8.8",
        "channel" to "014021I",
        "subchannel" to "014021I",
    )

    override suspend fun search(keyword: String, page: Int, pageSize: Int): List<NeteaseSongSearchResult> = withContext(Dispatchers.IO) {
        try {
            val searchSwitch = JSONObject().apply {
                put("song", 1); put("album", 0); put("singer", 0)
                put("tagSong", 1); put("mvSong", 0); put("bestShow", 1)
            }
            val query = "text=${URLEncoder.encode(keyword, "UTF-8")}&pageNo=$page&pageSize=$pageSize" +
                    "&isCopyright=1&sort=1&searchSwitch=${URLEncoder.encode(searchSwitch.toString(), "UTF-8")}"
            val root = getJson("$SEARCH_ENDPOINT?$query")
            val songData = root.optJSONObject("songResultData")
                ?: root.optJSONObject("data")?.optJSONObject("songResultData")
            val lists = songData?.optJSONArray("result") ?: JSONArray()
            List(lists.length()) { index ->
                val item = lists.getJSONObject(index)
                val contentId = item.optString("contentId")
                val copyrightId = item.optString("copyrightId")
                val singers = item.optJSONArray("singers") ?: JSONArray()
                val artist = List(singers.length()) { singers.getJSONObject(it).optString("name") }
                    .filter { it.isNotBlank() }
                    .joinToString(" / ")
                val cover = obtainCover(item)
                val identifier = if (contentId.isNotBlank()) {
                    "$contentId|$copyrightId|${buildQualities(item)}"
                } else null
                NeteaseSongSearchResult(
                    id = stableIdFromString(identifier.orEmpty()),
                    title = item.optString("name"),
                    artist = artist,
                    coverUrl = cover,
                    coverThumbUrl = cover,
                    duration = parseDuration(item),
                    source = MusicSearchSource.MIGU,
                    sourceId = identifier
                )
            }
        } catch (e: Exception) {
            CrashLogManager.logException("MiguMusicApi", "搜索歌曲失败", e)
            emptyList()
        }
    }

    /**
     * 内置榜单解析：咪咕官方榜单接口已下线，据实返回空而不伪造数据。
     *
     * 原 `content/billlist.do` 现一律返回 `299996 路由请求不支持`，旧版 `cms_list_tag`（m.music.migu.cn）
     * 亦 301 跳转至网页版 /v5，服务端不再提供榜单 JSON。咪咕因此只参与搜索，不进入推荐候选池。
     */
    override suspend fun chart(limit: Int): List<NeteaseSongSearchResult> = emptyList()

    /** 获取播放地址；quality 为空时按全部音质组合依次尝试，指定时仅尝试对应档位 */
    suspend fun songUrl(identifier: String, quality: MusicQuality? = null): String? = withContext(Dispatchers.IO) {
        if (identifier.isBlank()) return@withContext null
        try {
            val parts = identifier.split("|")
            val contentId = parts.getOrNull(0).orEmpty()
            val copyrightId = parts.getOrNull(1).orEmpty()
            if (contentId.isBlank() || copyrightId.isBlank()) return@withContext null
            val formats = parts.getOrNull(2).orEmpty().split(",").filter { it.contains(':') }
                .mapNotNull {
                    val pair = it.split(":")
                    if (pair.size == 2) pair[0] to pair[1] else null
                }
                .ifEmpty { listOf("SQ" to "2", "HQ" to "2", "PQ" to "2", "LQ" to "2") }
            // 指定音质时仅匹配对应档位；标准档优先 PQ，缺省时退而求其次使用 LQ
            val targets = when (quality) {
                MusicQuality.LOSSLESS -> formats.filter { it.first == "SQ" }
                MusicQuality.HIGH -> formats.filter { it.first == "HQ" }
                MusicQuality.STANDARD -> formats.filter { it.first == "PQ" }
                    .ifEmpty { formats.filter { it.first == "LQ" } }
                null -> formats
            }
            for ((formatType, resourceType) in targets) {
                if (formatType == "Z3D") continue
                val url = fetchListenUrl(contentId, copyrightId, formatType, resourceType) ?: continue
                val target = url.replace("/MP3_128_16_Stero/", "/MP3_320_16_Stero/").toHttps()
                if (isPlayable(target)) return@withContext target
            }
            null
        } catch (e: Exception) {
            CrashLogManager.logException("MiguMusicApi", "获取播放地址失败", e)
            null
        }
    }

    /** 获取歌词：先取 lrcUrl 再下载 LRC 文本 */
    suspend fun lyricLines(result: NeteaseSongSearchResult): List<LyricLine>? = withContext(Dispatchers.IO) {
        val parts = result.sourceId?.split("|").orEmpty()
        val contentId = parts.getOrNull(0).orEmpty()
        val copyrightId = parts.getOrNull(1).orEmpty()
        if (contentId.isBlank() || copyrightId.isBlank()) return@withContext null
        try {
            val query = "scene=&netType=01&resourceType=2&copyrightId=$copyrightId&contentId=$contentId&toneFlag=PQ"
            var lrcUrl = getJson("$LYRIC_ENDPOINT?$query").optJSONObject("data")?.optString("lrcUrl")
            if (lrcUrl.isNullOrBlank()) return@withContext null
            if (!lrcUrl.startsWith("http")) lrcUrl = "https://d.musicapp.migu.cn$lrcUrl"
            val request = Request.Builder()
                .url(lrcUrl.toHttps())
                .header("User-Agent", MusicHttpClient.MUSIC_USER_AGENT)
                .header("Referer", "https://y.migu.cn/")
                .build()
            val text = MusicHttpClient.client.newCall(request).execute().use { resp ->
                val body = resp.body.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                body
            }
            parseLrcText(text).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            CrashLogManager.logException("MiguMusicApi", "获取歌词失败", e)
            null
        }
    }

    private fun fetchListenUrl(
        contentId: String,
        copyrightId: String,
        formatType: String,
        resourceType: String,
    ): String? {
        val query = "contentId=$contentId&copyrightId=$copyrightId&resourceType=$resourceType" +
                "&netType=01&toneFlag=$formatType&scene=&lowerQualityContentId=$contentId"
        val root = getJson("$LISTEN_ENDPOINT?$query", extraHeaders = mapOf("birth" to "h5page", "signature" to "1"))
        val url = root.optJSONObject("data")?.optString("url")
        return url?.takeIf { it.startsWith("http") }
    }

    // 音质大小优先排序：size 多为 "xxMB" 字符串，取前 6 组写入歌曲标识
    private fun buildQualities(item: JSONObject): String {
        val formats = mutableListOf<Triple<String, String, Double>>()
        for (key in arrayOf("rateFormats", "newRateFormats", "audioFormats")) {
            val arr = item.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                val formatType = f.optString("formatType")
                val resourceType = f.optString("resourceType")
                if (formatType.isBlank() || resourceType.isBlank() || formatType == "Z3D") continue
                formats.add(Triple(formatType, resourceType, formatSize(f.opt("size"))))
            }
        }
        return formats.distinctBy { it.first to it.second }
            .sortedByDescending { it.third }
            .take(6)
            .joinToString(",") { "${it.first}:${it.second}" }
    }

    private fun formatSize(value: Any?): Double {
        val s = value?.toString()?.removeSuffix("MB")?.trim() ?: return 0.0
        return s.toDoubleOrNull() ?: 0.0
    }

    // duration 可能是纯秒或 分:秒 / 时:分:秒，统一转成毫秒
    private fun parseDuration(item: JSONObject): Long {
        val raw = item.optString("duration").ifBlank { return 0L }
        return raw.split(":").fold(0L) { acc, part -> acc * 60 + (part.toLongOrNull() ?: 0L) } * 1000L
    }

    private fun obtainCover(item: JSONObject): String? {
        var cover: String? = null
        val imgItems = item.optJSONArray("imgItems")
        if (imgItems != null && imgItems.length() > 0) {
            cover = imgItems.optJSONObject(imgItems.length() - 1)?.optString("img")
        }
        if (cover.isNullOrBlank()) {
            cover = item.optString("img3").ifBlank { item.optString("img2").ifBlank { item.optString("img1") } }
        }
        if (cover.isNullOrBlank()) return null
        return if (cover.startsWith("http")) cover.toHttps() else "https://d.musicapp.migu.cn$cover"
    }

    private fun getJson(url: String, extraHeaders: Map<String, String> = emptyMap()): JSONObject {
        val builder = Request.Builder().url(url)
            .header("User-Agent", MusicHttpClient.MUSIC_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Content-Type", "application/json;charset=UTF-8")
        MIGU_HEADERS.forEach { (k, v) -> builder.header(k, v) }
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        return MusicHttpClient.client.newCall(builder.build()).execute().use { resp ->
            val body = resp.body.bytes()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            decryptResponse(body, resp.header("signature"))
        }
    }

    private fun decryptResponse(raw: ByteArray, signatureHeader: String?): JSONObject {
        val encrypted = signatureHeader == "1" || startsWithMagic(raw)
        val jsonText = if (encrypted) {
            val seed = raw[3].toInt() and 0xFF
            val plain = ByteArray(raw.size - 4)
            for (i in plain.indices) {
                val cipher = raw[4 + i].toInt() and 0xFF
                plain[i] = ((cipher + seed - (MIGU_KEY[i % MIGU_KEY.size].toInt() and 0xFF)) and 0xFF).toByte()
            }
            String(plain, Charsets.UTF_8)
        } else {
            String(raw, Charsets.UTF_8)
        }
        return JSONObject(jsonText)
    }

    private fun startsWithMagic(raw: ByteArray): Boolean {
        if (raw.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (raw[i] != MAGIC[i]) return false
        return true
    }

    private fun isPlayable(url: String): Boolean = try {
        val head = Request.Builder().url(url).head()
            .header("User-Agent", MusicHttpClient.MUSIC_USER_AGENT)
            .header("Referer", "https://h5.nf.migu.cn/")
            .build()
        val headCode = MusicHttpClient.client.newCall(head).execute().use { it.code }
        if (headCode in 200..399) {
            true
        } else if (headCode == 405 || headCode == 501) {
            val get = Request.Builder().url(url)
                .header("Range", "bytes=0-0")
                .header("User-Agent", MusicHttpClient.MUSIC_USER_AGENT)
                .header("Referer", "https://h5.nf.migu.cn/")
                .build()
            MusicHttpClient.client.newCall(get).execute().use { it.code in 200..399 }
        } else {
            false
        }
    } catch (e: Exception) {
        false
    }

    private fun String.toHttps(): String = if (startsWith("http://")) replaceFirst("http://", "https://") else this
}
