package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.log.CrashLogManager
import java.net.URLEncoder
import java.util.Base64
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * QQ 音乐在线源：网页版搜索接口（客户端接口作兜底）+ GetVkey 获取播放地址 + 歌词接口。
 * 歌曲标识为 songmid 字符串，转成稳定数字 id 存入搜索结果。
 */
internal object QQMusicApi : OnlineMusicSource {

    private const val ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val MUSIC_DOMAIN = "https://isure.stream.qqmusic.qq.com/"
    private const val TOPLIST_ENDPOINT = "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg"
    private const val WEB_SEARCH_ENDPOINT = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp"
    // 网页版搜索接口单页上限，超出会被服务端截断
    private const val WEB_SEARCH_MAX_PAGE_SIZE = 30
    // 官方接口的 comm 参数需要 QIMEI36，取不到设备标识时用该固定兜底值
    private const val QIMEI36 = "6c9d3cd110abca9b16311cee10001e717614"
    private const val VERSION_CODE = 13020508
    private const val UID = "3931641530"
    private const val GUID_CHARS = "abcdef1234567890"
    // 默认榜单：热歌榜
    private const val CHART_TOP_ID = 26

    // 音质代号 + 扩展名，按从高到低分组：无损 flac / 高品 ogg / 标准 mp3、m4a。
    // 母带、全景声等平台升频代号不参与匹配，故不入表
    private val LOSSLESS_QUALITIES = arrayOf(
        "F000" to ".flac",
    )
    private val HIGH_QUALITIES = arrayOf(
        "O801" to ".ogg", "O800" to ".ogg", "O600" to ".ogg", "O400" to ".ogg",
    )
    private val STANDARD_QUALITIES = arrayOf(
        "M800" to ".mp3", "M500" to ".mp3", "C600" to ".m4a", "C400" to ".m4a", "C200" to ".m4a"
    )

    // 音质档位对应的编码组合；只列本档位自身的代号，跨档降级由 adaptiveCandidates 逐档驱动
    private fun qualityCandidates(quality: MusicQuality): Array<Pair<String, String>> = when (quality) {
        // QQ 无独立 Hi-Res 代号，该档回退到无损
        MusicQuality.HI_RES,
        MusicQuality.LOSSLESS -> LOSSLESS_QUALITIES
        MusicQuality.HIGH -> HIGH_QUALITIES
        MusicQuality.STANDARD -> STANDARD_QUALITIES
    }

    override suspend fun search(keyword: String, page: Int, pageSize: Int): List<NeteaseSongSearchResult> = withContext(Dispatchers.IO) {
        // 网页版接口不校验登录票据，作为首选
        val webResults = runCatching { doWebSearch(keyword, page, pageSize) }
            .onFailure { CrashLogManager.logException("QQMusicApi", "搜索歌曲失败", it) }
            .getOrDefault(emptyList())
        if (webResults.isNotEmpty()) return@withContext webResults
        // 客户端接口对未登录请求一律返回空列表，仅在其风控放行时段可用，故只作兜底
        try {
            delay(300)
            doSearch(keyword, page, pageSize)
        } catch (e: Exception) {
            CrashLogManager.logException("QQMusicApi", "搜索歌曲失败", e)
            emptyList()
        }
    }

    // 网页版搜索接口：无需登录票据，条目字段与客户端接口同源，复用 [songResult] 映射
    private fun doWebSearch(keyword: String, page: Int, pageSize: Int): List<NeteaseSongSearchResult> {
        val count = pageSize.coerceIn(1, WEB_SEARCH_MAX_PAGE_SIZE)
        val query = URLEncoder.encode(keyword, Charsets.UTF_8.name())
        val url = "$WEB_SEARCH_ENDPOINT?w=$query&format=json&p=$page&n=$count&cr=1&new_json=1"
        val list = JSONObject(get(url)).optJSONObject("data")
            ?.optJSONObject("song")?.optJSONArray("list") ?: JSONArray()
        return List(list.length()) { index -> songResult(list.getJSONObject(index)) }
    }

    private fun doSearch(keyword: String, page: Int, pageSize: Int): List<NeteaseSongSearchResult> {
        val body = JSONObject()
        body.put("comm", commonParams())
        val search = JSONObject()
        search.put("module", "music.search.SearchCgiService")
        search.put("method", "DoSearchForQQMusicMobile")
        val param = JSONObject()
        param.put("searchid", randomSearchId())
        param.put("query", keyword)
        param.put("search_type", 0)
        param.put("num_per_page", pageSize)
        param.put("page_num", page)
        param.put("highlight", 1)
        param.put("grp", 1)
        search.put("param", param)
        body.put("music.search.SearchCgiService.DoSearchForQQMusicMobile", search)

        val root = post(body)
        val itemSong = root.optJSONObject("music.search.SearchCgiService.DoSearchForQQMusicMobile")
            ?.optJSONObject("data")?.optJSONObject("body")?.optJSONArray("item_song") ?: JSONArray()
        return List(itemSong.length()) { index -> songResult(itemSong.getJSONObject(index)) }
    }

    /**
     * 内置榜单解析：热歌榜。榜单条目的字段名与搜索结果同源，统一走 [songResult] 映射。
     *
     * 取 fcg_v8_toplist_cp：一条 GET 即返回曲目的 songmid/专辑/歌手/时长，
     * songmid 是播放与歌词接口的入参，musicu 的 GetDetail 只给 songId，无法直接使用。
     */
    override suspend fun chart(limit: Int): List<NeteaseSongSearchResult> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        try {
            val url = "$TOPLIST_ENDPOINT?topid=$CHART_TOP_ID&format=json&page=detail" +
                    "&num=$limit&song_begin=0&tpl=3&notice=0&need_new_cover=1"
            val songlist = JSONObject(get(url)).optJSONArray("songlist") ?: JSONArray()
            List(minOf(songlist.length(), limit)) { index ->
                songResult(songlist.optJSONObject(index)?.optJSONObject("data") ?: JSONObject())
            }.filter { it.title.isNotBlank() }
        } catch (e: Exception) {
            CrashLogManager.logException("QQMusicApi", "解析榜单失败", e)
            emptyList()
        }
    }

    // 榜单与搜索的歌曲字段同源，统一映射为搜索结果模型
    private fun songResult(item: JSONObject): NeteaseSongSearchResult {
        val mid = item.optString("mid").ifBlank { item.optString("songmid") }
        val singer = item.optJSONArray("singer") ?: JSONArray()
        val artist = List(singer.length()) { singer.getJSONObject(it).optString("name") }
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        val albumMid = item.optJSONObject("album")?.optString("mid")
            .orEmpty().ifBlank { item.optString("albummid") }
        // 封面 CDN 按尺寸段生成，列表行用 150x150，其余场景用 800x800
        val cover = albumMid.takeIf { it.isNotBlank() }
            ?.let { "https://y.gtimg.cn/music/photo_new/T002R800x800M000$it.jpg" }
        val thumb = albumMid.takeIf { it.isNotBlank() }
            ?.let { "https://y.gtimg.cn/music/photo_new/T002R150x150M000$it.jpg" }
        return NeteaseSongSearchResult(
            id = stableIdFromString(mid),
            // 搜索接口给 title/name，榜单接口给 songname，逐级回退
            title = item.optString("title").ifBlank { item.optString("name") }
                .ifBlank { item.optString("songname") },
            artist = artist,
            coverUrl = cover,
            coverThumbUrl = thumb,
            duration = item.optLong("interval", 0L) * 1000L,
            source = MusicSearchSource.QQ,
            sourceId = mid,
        )
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MusicHttpClient.MUSIC_USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .build()
        return MusicHttpClient.client.newCall(request).execute().use { resp ->
            val body = resp.body.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            body
        }
    }

    /** 获取指定音质的播放地址；只尝试该档位的候选代号，跨档降级由调用方逐档驱动 */
    suspend fun songUrl(mid: String, quality: MusicQuality = MusicQuality.LOSSLESS): String? = withContext(Dispatchers.IO) {
        if (mid.isBlank()) return@withContext null
        try {
            for ((code, ext) in qualityCandidates(quality)) {
                val body = JSONObject()
                body.put("comm", commonParams(ct = 19))
                val vkey = JSONObject()
                vkey.put("module", "music.vkey.GetVkey")
                vkey.put("method", "UrlGetVkey")
                val param = JSONObject()
                param.put("filename", JSONArray().put("$code$mid$mid$ext"))
                param.put("guid", randomGuid())
                param.put("songmid", JSONArray().put(mid))
                param.put("songtype", JSONArray().put(0))
                vkey.put("param", param)
                body.put("music.vkey.GetVkey.UrlGetVkey", vkey)

                val root = post(body)
                val purl = root.optJSONObject("music.vkey.GetVkey.UrlGetVkey")
                    ?.optJSONObject("data")?.optJSONArray("midurlinfo")?.optJSONObject(0)
                    ?.optString("purl")?.ifBlank { null }
                    ?: continue
                return@withContext MUSIC_DOMAIN + purl
            }
            null
        } catch (e: Exception) {
            CrashLogManager.logException("QQMusicApi", "获取播放地址失败", e)
            null
        }
    }

    /** 获取歌词，接口返回 base64 编码的 LRC 文本 */
    suspend fun lyricLines(result: NeteaseSongSearchResult): List<LyricLine>? = withContext(Dispatchers.IO) {
        val mid = result.sourceId ?: return@withContext null
        try {
            val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                    "?songmid=$mid&g_tk=5381&loginUin=0&hostUin=0&format=json" +
                    "&inCharset=utf8&outCharset=utf-8&platform=yqq"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", MusicHttpClient.MUSIC_USER_AGENT)
                .header("Referer", "https://y.qq.com/portal/player.html")
                .build()
            val response = MusicHttpClient.client.newCall(request).execute().use { resp ->
                val text = resp.body.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                text
            }
            val json = JSONObject(response)
            val b64 = json.optString("lyric").ifBlank { return@withContext null }
            val lrc = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
            // trans 字段为 base64 翻译歌词，取不到时静默跳过
            val trans = runCatching {
                json.optString("trans").takeIf { it.isNotBlank() }
                    ?.let { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
                    .orEmpty()
            }.getOrDefault("")
            mergeTranslations(parseLrcText(lrc), parseLrcText(trans)).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            CrashLogManager.logException("QQMusicApi", "获取歌词失败", e)
            null
        }
    }

    private fun commonParams(ct: Int = 11): JSONObject = JSONObject().apply {
        put("cv", VERSION_CODE)
        put("v", VERSION_CODE)
        put("QIMEI36", QIMEI36)
        put("ct", ct)
        put("tmeAppID", "qqmusic")
        put("format", "json")
        put("inCharset", "utf-8")
        put("outCharset", "utf-8")
        put("uid", UID)
    }

    private fun post(body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", MusicHttpClient.MUSIC_USER_AGENT)
            // 缺 Referer/Origin 时搜索接口会返回空列表
            .header("Referer", "https://y.qq.com/")
            .header("Origin", "https://y.qq.com/")
            .build()
        return MusicHttpClient.client.newCall(request).execute().use { resp ->
            val response = resp.body.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: $response")
            JSONObject(response)
        }
    }

    // searchid 按时间戳与随机数拼出大整数
    private fun randomSearchId(): String {
        val t = (1 + Random.nextInt(20)) * 18014398509481984L
        val n = Random.nextInt(0, 4194305) * 4294967296L
        val r = System.currentTimeMillis() % (24 * 60 * 60 * 1000)
        return (t + n + r).toString()
    }

    private fun randomGuid(): String = buildString(32) {
        repeat(32) { append(GUID_CHARS[Random.nextInt(GUID_CHARS.length)]) }
    }
}
