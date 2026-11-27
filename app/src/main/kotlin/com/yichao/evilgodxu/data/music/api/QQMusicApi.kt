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

// QQ 在线源：网页搜索、Vkey 播放与歌词
// 歌曲标识为 songmid，入库时转成稳定数字 id
internal object QQMusicApi : OnlineMusicSource {

    private const val ENDPOINT = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val MUSIC_DOMAIN = "https://isure.stream.qqmusic.qq.com/"
    private const val WEB_SEARCH_ENDPOINT = "https://c.y.qq.com/soso/fcgi-bin/search_for_qq_cp"
    // 网页版单曲详情补齐音轨号、碟号、发行日期
    private const val SONG_DETAIL_ENDPOINT = "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg"
    // 网页版搜索单页上限 30，超出被截断
    private const val WEB_SEARCH_MAX_PAGE_SIZE = 30
    // 歌词模块名，同时用作请求体中的键
    private const val LYRIC_MODULE = "music.musichallSong.PlayLyricInfo"
    private const val LYRIC_MODULE_METHOD = "$LYRIC_MODULE.GetPlayLyricInfo"
    // comm 参数所需 QIMEI36，取不到设备标识时用兜底值
    private const val QIMEI36 = "6c9d3cd110abca9b16311cee10001e717614"
    private const val VERSION_CODE = 13020508
    private const val UID = "3931641530"
    private const val GUID_CHARS = "abcdef1234567890"

    // 音质代号+扩展名，高到低分组
    // 母带、全景声等升频代号不参与匹配
    private val LOSSLESS_QUALITIES = arrayOf(
        "F000" to ".flac",
    )
    private val HIGH_QUALITIES = arrayOf(
        "O801" to ".ogg", "O800" to ".ogg", "O600" to ".ogg", "O400" to ".ogg",
    )
    private val STANDARD_QUALITIES = arrayOf(
        "M800" to ".mp3", "M500" to ".mp3", "C600" to ".m4a", "C400" to ".m4a", "C200" to ".m4a"
    )

    // 本档位代号，跨档降级由候选链驱动
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
        // 客户端接口未登录返回空，仅作兜底
        try {
            delay(300)
            doSearch(keyword, page, pageSize)
        } catch (e: Exception) {
            CrashLogManager.logException("QQMusicApi", "搜索歌曲失败", e)
            emptyList()
        }
    }

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

    // 各接口字段名有差异，统一映射为结果模型
    private fun songResult(item: JSONObject): NeteaseSongSearchResult {
        val mid = item.optString("mid").ifBlank { item.optString("songmid") }
        val singer = item.optJSONArray("singer") ?: JSONArray()
        val artist = List(singer.length()) { singer.getJSONObject(it).optString("name") }
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        val albumObject = item.optJSONObject("album")
        val albumMid = albumObject?.optString("mid").orEmpty().ifBlank { item.optString("albummid") }
        // 封面按尺寸段生成：列表 150，其余 800
        val cover = albumMid.takeIf { it.isNotBlank() }
            ?.let { "https://y.gtimg.cn/music/photo_new/T002R800x800M000$it.jpg" }
        val thumb = albumMid.takeIf { it.isNotBlank() }
            ?.let { "https://y.gtimg.cn/music/photo_new/T002R150x150M000$it.jpg" }
        // 搜索仅有专辑名与发行时间，音轨/碟号由详情补齐
        val publishSeconds = item.optLong("pubtime", 0L)
            .takeIf { it > 0L }
            ?: albumObject?.optLong("time_public", 0L)?.takeIf { it > 0L }
        return NeteaseSongSearchResult(
            id = stableIdFromString(mid),
            // 字段名因接口而异，逐级回退
            title = item.optString("title").ifBlank { item.optString("name") }
                .ifBlank { item.optString("songname") },
            artist = artist,
            coverUrl = cover,
            coverThumbUrl = thumb,
            duration = item.optLong("interval", 0L) * 1000L,
            source = MusicSearchSource.QQ,
            sourceId = mid,
            album = albumObject?.optString("name")?.takeIf { it.isNotBlank() }
                ?: item.optString("albumname").takeIf { it.isNotBlank() },
            releaseDate = publishSeconds?.let { dateTextFromEpochSeconds(it) }
                ?: item.optString("time_public").takeIf { it.isNotBlank() },
        )
    }

    // 单曲详情：补齐搜索缺失的音轨号、碟号与发行日期
    // 字段缺失为 null，合并时保留搜索结果的值
    suspend fun songDetail(mid: String): NeteaseSongSearchResult? = withContext(Dispatchers.IO) {
        if (mid.isBlank()) return@withContext null
        try {
            val url = "$SONG_DETAIL_ENDPOINT?songmid=$mid&format=json&platform=yqq&needNewCode=0"
            val song = JSONObject(get(url)).optJSONArray("data")?.optJSONObject(0)
                ?: return@withContext null
            val album = song.optJSONObject("album")
            val singers = song.optJSONArray("singer") ?: JSONArray()
            val artist = List(singers.length()) { singers.getJSONObject(it).optString("name") }
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            val songMid = song.optString("mid").ifBlank { mid }
            val albumMid = album?.optString("mid").orEmpty()
            val volume = song.optJSONObject("volume")
            NeteaseSongSearchResult(
                id = stableIdFromString(songMid),
                title = song.optString("title").ifBlank { song.optString("name") },
                artist = artist,
                coverUrl = albumMid.takeIf { it.isNotBlank() }
                    ?.let { "https://y.gtimg.cn/music/photo_new/T002R800x800M000$it.jpg" },
                coverThumbUrl = albumMid.takeIf { it.isNotBlank() }
                    ?.let { "https://y.gtimg.cn/music/photo_new/T002R150x150M000$it.jpg" },
                duration = song.optLong("interval", 0L) * 1000L,
                source = MusicSearchSource.QQ,
                sourceId = songMid,
                album = album?.optString("name")?.takeIf { it.isNotBlank() },
                releaseDate = song.optString("time_public").takeIf { it.isNotBlank() }
                    ?: album?.optString("time_public")?.takeIf { it.isNotBlank() },
                trackNumber = song.optInt("index_album", 0).takeIf { it > 0 },
                // index_cd 从 0 起算，单碟不写碟号
                discNumber = song.optInt("index_cd", 0).takeIf { it > 0 }?.plus(1),
                // 平台响度：gain 为 dB 增益，peak 为线性峰值
                // 缺省或非法即未给，不写 0（0 dB 表示无需调整）
                replayGainTrackGainDb = volume?.optDouble("gain", Double.NaN)?.takeIf { it.isFinite() },
                replayGainTrackPeak = volume?.optDouble("peak", Double.NaN)?.takeIf { it.isFinite() },
            )
        } catch (e: Exception) {
            CrashLogManager.logException("QQMusicApi", "获取歌曲详情失败: $mid", e)
            null
        }
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

    // 获取歌词：逐字优先
    // qrc 密文解密后解析；无覆盖或解密失败则回退逐行 LRC
    suspend fun lyricLines(result: NeteaseSongSearchResult): List<LyricLine>? = withContext(Dispatchers.IO) {
        val mid = result.sourceId ?: return@withContext null
        try {
            qrcLyricLines(mid)?.let { return@withContext it }
            lrcLyricLines(mid)
        } catch (e: Exception) {
            CrashLogManager.logException("QQMusicApi", "获取歌词失败", e)
            null
        }
    }

    // 逐字歌词：十六进制密文，明文为 XML 封装的 QRC
    private fun qrcLyricLines(mid: String): List<LyricLine>? = try {
        val info = requestPlayLyricInfo(mid)
        val lyrics = info?.optString("lyric")?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("<")) it else QrcCipher.decrypt(it) }
            ?.let { parseQrcText(extractQrcContent(it)) }
            ?.takeIf { it.isNotEmpty() }
        if (info == null || lyrics == null) {
            null
        } else {
            // 译文同为 QRC 密文，缺失则静默跳过
            val translations = info.optString("trans").takeIf { it.isNotBlank() }
                ?.let { QrcCipher.decrypt(it) }
                ?.let { parseQrcText(extractQrcContent(it)) }
                .orEmpty()
            mergeTranslations(lyrics, translations)
        }
    } catch (e: Exception) {
        CrashLogManager.logException("QQMusicApi", "获取 QRC 歌词失败: songmid=$mid", e)
        null
    }

    private fun requestPlayLyricInfo(mid: String): JSONObject? {
        val body = JSONObject()
        body.put("comm", commonParams())
        val info = JSONObject()
        info.put("module", LYRIC_MODULE)
        info.put("method", "GetPlayLyricInfo")
        val param = JSONObject()
        param.put("songMID", mid)
        param.put("songID", 0)
        param.put("lrc_t", 0)
        param.put("qrc", 1)
        param.put("trans", 1)
        param.put("roma", 0)
        param.put("type", -1)
        info.put("param", param)
        body.put(LYRIC_MODULE_METHOD, info)
        return post(body).optJSONObject(LYRIC_MODULE_METHOD)?.optJSONObject("data")
    }

    // QRC 明文为 XML，正文在 LyricContent
    private fun extractQrcContent(text: String): String {
        val content = QRC_CONTENT_PATTERN.find(text)?.groupValues?.get(1) ?: return text
        return content
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }

    // 逐行歌词兜底：base64 编码的 LRC
    private fun lrcLyricLines(mid: String): List<LyricLine>? = try {
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
        val lrc = json.optString("lyric").takeIf { it.isNotBlank() }
            ?.let { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
        if (lrc == null) {
            null
        } else {
            // trans 为 base64 译文，取不到则跳过
            val trans = runCatching {
                json.optString("trans").takeIf { it.isNotBlank() }
                    ?.let { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
                    .orEmpty()
            }.getOrDefault("")
            // 该接口 LRC 偶带逐字标签，交给增强解析器
            mergeTranslations(parseWordTimedLrcText(lrc), parseWordTimedLrcText(trans))
                .takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        CrashLogManager.logException("QQMusicApi", "获取歌词失败: songmid=$mid", e)
        null
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
            // 缺 Referer/Origin 时搜索返回空
            .header("Referer", "https://y.qq.com/")
            .header("Origin", "https://y.qq.com/")
            .build()
        return MusicHttpClient.client.newCall(request).execute().use { resp ->
            val response = resp.body.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: $response")
            JSONObject(response)
        }
    }

    private fun randomSearchId(): String {
        val t = (1 + Random.nextInt(20)) * 18014398509481984L
        val n = Random.nextInt(0, 4194305) * 4294967296L
        val r = System.currentTimeMillis() % (24 * 60 * 60 * 1000)
        return (t + n + r).toString()
    }

    private fun randomGuid(): String = buildString(32) {
        repeat(32) { append(GUID_CHARS[Random.nextInt(GUID_CHARS.length)]) }
    }

    // XML 信封的歌词正文属性，内层引号已转义
    private val QRC_CONTENT_PATTERN = Regex("""LyricContent="([^"]*)"""")
}
