package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.LyricWord
import com.yichao.evilgodxu.data.music.model.NeteaseLyricData
import com.yichao.evilgodxu.data.music.model.NeteasePlaylistData
import com.yichao.evilgodxu.data.music.model.NeteaseSongMatch
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

internal object NeteaseMusicApi : OnlineMusicSource {

    // 默认榜单：飙升榜
    private const val CHART_ID = "19723756"
    suspend fun loadCoverBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        try {
            MusicHttpClient.client.newCall(Request.Builder().url(url).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body.bytes()
                }
        } catch (e: Exception) {
            CrashLogManager.logException("NeteaseMusicApi", "下载封面失败: $url", e)
            null
        }
    }

    suspend fun match(title: String, artist: String, durationMs: Long): NeteaseSongMatch? = withContext(Dispatchers.IO) {
        val keyword = if (artist.isBlank() || artist == "未知艺术家" || artist == "<unknown>") title else "$title $artist"
        val songs = searchMatch(keyword)
        val best = songs.minByOrNull { score(it, title, artist, durationMs) } ?: return@withContext null
        if (!best.coverUrl.isNullOrBlank()) best else detail(best)
    }

    suspend fun lyric(songId: Long): NeteaseLyricData = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("id", songId)
            put("lv", 1)
            put("kv", 1)
            put("tv", 1)
            put("rv", 1)
            put("yv", 1)
        }
        val root = request("song/lyric/v1", body)
        val lrc = root.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val tlyric = root.optJSONObject("tlyric")?.optString("lyric").orEmpty()
        parseLrc(
            lrc,
            yrc = root.optJSONObject("yrc")?.optString("lyric").orEmpty(),
            tlyric = tlyric
        )
    }

    private fun detail(song: NeteaseSongMatch): NeteaseSongMatch {
        val root = request("v3/song/detail", JSONObject().put("c", "[{\"id\":${song.id}}]"))
        val item = root.optJSONArray("songs")?.optJSONObject(0) ?: return song
        val album = item.optJSONObject("al") ?: item.optJSONObject("album")
        return song.copy(coverUrl = album?.optString("picUrl")?.takeIf { it.isNotBlank() })
    }

    // 在线音乐源统一接口实现，返回完整的搜索结果列表
    override suspend fun search(keyword: String, page: Int, pageSize: Int): List<NeteaseSongSearchResult> = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("s", keyword)
            put("type", 1)
            put("limit", pageSize)
            put("offset", (page - 1) * pageSize)
        }
        val root = request("search/get", body)
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
        val results = List(songs.length()) { index -> songResult(songs.getJSONObject(index)) }
        // 按与查询关键词的相关性重排，优先展示与原曲(歌名+歌手)更匹配的结果，再补全缺失封面
        fillMissingCovers(rankSearchResults(results, keyword))
    }

    // 网易模糊搜索常把同名翻唱/伴奏等排在原唱之前，这里按相关性降权重排
    internal fun rankSearchResults(
        results: List<NeteaseSongSearchResult>,
        query: String,
    ): List<NeteaseSongSearchResult> {
        val nq = normalize(query)
        return results.sortedBy { result ->
            val nt = normalize(result.title)
            var score = if (nq.isNotBlank() && nt.isNotEmpty() && (nq == nt || nq.contains(nt) || nt.contains(nq))) -100 else 0
            if (VERSION_MARKERS.any { result.title.lowercase().contains(it) }) score += 60
            // 查询中剔除歌名部分后仍剩歌手关键词，而结果歌手未匹配该关键词时降权
            val normArtist = normalize(result.artist)
            val residue = nq.replace(nt, "")
            if (residue.isNotEmpty() && normArtist.isNotEmpty() && !nq.contains(normArtist)) score += 80
            score
        }.distinctBy { it.id }
    }

    // 原曲以外的版本/翻唱等标记，命中即降权
    private val VERSION_MARKERS = listOf(
        "深情版", "女声版", "男声版", "童声", "翻唱", "现场", "弹唱", "伴奏",
        "演唱会", "钢琴", "吉他", "纯音乐", "dj版", "remix", "cover", "live版", "重唱"
    )

    /** 批量补全搜索结果中缺失封面 URL 的条目 */
    private fun fillMissingCovers(results: List<NeteaseSongSearchResult>): List<NeteaseSongSearchResult> {
        val missingIds = results.filter { it.coverUrl.isNullOrBlank() }.map { it.id }
        if (missingIds.isEmpty()) return results
        try {
            val c = missingIds.joinToString(",") { "{\"id\":$it}" }
            val root = request("v3/song/detail", JSONObject().put("c", "[$c]"))
            val songs = root.optJSONArray("songs") ?: return results
            val coverMap = mutableMapOf<Long, String>()
            for (i in 0 until songs.length()) {
                val item = songs.getJSONObject(i)
                val id = item.optLong("id")
                val album = item.optJSONObject("al") ?: item.optJSONObject("album") ?: continue
                val picUrl = album.optString("picUrl").takeIf { it.isNotBlank() } ?: continue
                coverMap[id] = ensureHttps(picUrl)
            }
            return results.map { result ->
                if (result.coverUrl.isNullOrBlank()) {
                    val cover = coverMap[result.id]
                    if (cover != null) {
                        result.copy(coverUrl = cover, coverThumbUrl = thumbUrl(cover))
                    } else result
                } else result
            }
        } catch (e: Exception) {
            CrashLogManager.logException("NeteaseMusicApi", "补全歌曲封面失败", e)
            return results
        }
    }

    // 获取歌曲播放 URL，返回 null 表示完全不可播；试听片段也如实返回
    suspend fun getSongUrlInfo(songId: Long, level: String = "standard"): SongUrlInfo? = withContext(Dispatchers.IO) {
        try {
            val root = requestEapi("song/enhance/player/url/v1", eapiSongUrlPayload(songId, level))
            val data = root.optJSONArray("data")?.optJSONObject(0) ?: return@withContext null
            val url = data.optString("url", "")
            if (url.isBlank()) return@withContext null
            val hasFreeTrial = data.has("freeTrialInfo") && !data.isNull("freeTrialInfo")
            SongUrlInfo(url = ensureHttps(url), trial = hasFreeTrial)
        } catch (e: Exception) {
            CrashLogManager.logException("NeteaseMusicApi", "获取歌曲播放地址失败", e)
            null
        }
    }

    /** 歌曲播放 URL 信息 */
    data class SongUrlInfo(val url: String, val trial: Boolean)

    // 多音质回退获取播放 URL：优先非试听完整 URL，全为试听时降级返回试听片段
    suspend fun getSongUrlWithFallback(songId: Long): String? {
        for (level in arrayOf("standard", "higher", "exhigh")) {
            val info = getSongUrlInfo(songId, level) ?: continue
            if (!info.trial && info.url.isNotBlank()) return info.url
        }
        // 全为试听片段时降级返回第一个试听 URL
        for (level in arrayOf("standard", "higher", "exhigh")) {
            val info = getSongUrlInfo(songId, level) ?: continue
            if (info.url.isNotBlank()) return info.url
        }
        return null
    }

    // 按音质档位解析播放地址；返回 null 表示该层级不可用（无版权/试听受限等）
    suspend fun songUrl(songId: Long, quality: MusicQuality): String? {
        val level = when (quality) {
            MusicQuality.HI_RES -> "hires"
            MusicQuality.LOSSLESS -> "lossless"
            MusicQuality.HIGH -> "exhigh"
            MusicQuality.STANDARD -> "standard"
        }
        return getSongUrlInfo(songId, level)?.url
    }

    /** 补全歌曲元数据（标题/艺术家/封面等） */
    suspend fun songDetail(songId: Long): NeteaseSongSearchResult? = withContext(Dispatchers.IO) {
        try {
            val root = request("v3/song/detail", JSONObject().put("c", "[{\"id\":$songId}]"))
            root.optJSONArray("songs")?.optJSONObject(0)?.let { songResult(it) }
        } catch (e: Exception) {
            CrashLogManager.logException("NeteaseMusicApi", "获取歌曲详情失败", e)
            null
        }
    }

    /**
     * 曲目字段映射：搜索、详情、歌单与榜单的响应同源（`ar`/`al`/`dt` 或旧版 `artists`/`album`/`duration`），
     * 统一在此收敛，避免同一组字段在各调用点各写一遍。
     */
    private fun songResult(item: JSONObject): NeteaseSongSearchResult {
        val artists = item.optJSONArray("ar") ?: item.optJSONArray("artists") ?: JSONArray()
        val artist = List(artists.length()) { artists.getJSONObject(it).optString("name") }
            .filter { it.isNotBlank() }
            .joinToString(" / ")
        val album = item.optJSONObject("al") ?: item.optJSONObject("album")
        val cover = album?.optString("picUrl")?.takeIf { it.isNotBlank() }?.let { ensureHttps(it) }
        val duration = item.optLong("dt", 0L).takeIf { it > 0L } ?: item.optLong("duration", 0L)
        return NeteaseSongSearchResult(
            id = item.optLong("id"),
            title = item.optString("name"),
            artist = artist,
            coverUrl = cover,
            coverThumbUrl = cover?.let { thumbUrl(it) },
            duration = duration,
        )
    }

    // 内置歌单解析：按歌单 ID 拉取名称与全部歌曲
    suspend fun fetchPlaylist(playlistId: String): NeteasePlaylistData? = withContext(Dispatchers.IO) {
        try {
            val root = request("v6/playlist/detail", JSONObject().apply {
                put("id", playlistId)
                put("n", 100000)
                put("s", 8)
            })
            val playlist = root.optJSONObject("playlist") ?: return@withContext null
            val trackIds = (playlist.optJSONArray("trackIds") ?: JSONArray()).let { arr ->
                List(arr.length()) { arr.optJSONObject(it)?.optLong("id") }.filterNotNull()
            }
            val songs = fetchSongDetails(trackIds)
            if (songs.isEmpty()) return@withContext null
            NeteasePlaylistData(playlist.optString("name"), songs)
        } catch (e: Exception) {
            CrashLogManager.logException("NeteaseMusicApi", "解析歌单失败: $playlistId", e)
            null
        }
    }

    /**
     * 内置榜单解析：飙升榜（官方歌单 [CHART_ID]）。
     *
     * 走公开的 playlist/detail：带上 n 参数时响应直接给出前 n 首的完整曲目信息（含专辑封面与时长），
     * 一次请求即可，无需再为每首补 v3/song/detail。
     */
    override suspend fun chart(limit: Int): List<NeteaseSongSearchResult> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        try {
            val root = getJson("https://music.163.com/api/v6/playlist/detail?id=$CHART_ID&n=$limit")
            val tracks = root.optJSONObject("playlist")?.optJSONArray("tracks") ?: JSONArray()
            List(tracks.length()) { index -> songResult(tracks.getJSONObject(index)) }
                .filter { it.title.isNotBlank() }
        } catch (e: Exception) {
            CrashLogManager.logException("NeteaseMusicApi", "解析榜单失败", e)
            emptyList()
        }
    }

    // 公开 REST 接口（榜单等只读数据）：与 weapi 同一站点，免加密，参数直接进查询串
    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MusicHttpClient.MUSIC_USER_AGENT)
            .header("Referer", "https://music.163.com")
            .build()
        return MusicHttpClient.client.newCall(request).execute().use { resp ->
            val body = resp.body.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: $body")
            JSONObject(body)
        }
    }

    // 按歌单内歌曲 ID 分批拉取详情并映射为统一曲目
    private suspend fun fetchSongDetails(ids: List<Long>): List<NeteaseSongSearchResult> {
        if (ids.isEmpty()) return emptyList()
        val songs = mutableListOf<NeteaseSongSearchResult>()
        ids.chunked(200).forEach { batch ->
            val c = batch.joinToString(",") { "{\"id\":$it}" }
            val array = runCatching {
                request("v3/song/detail", JSONObject().put("c", "[$c]")).optJSONArray("songs")
            }.getOrNull() ?: return@forEach
            val batchIds = batch.toSet()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optLong("id")
                if (id !in batchIds) continue
                val artists = item.optJSONArray("ar") ?: item.optJSONArray("artists") ?: JSONArray()
                val artist = List(artists.length()) { artists.getJSONObject(it).optString("name") }
                    .filter { it.isNotBlank() }
                    .joinToString(" / ")
                val album = item.optJSONObject("al") ?: item.optJSONObject("album")
                val cover = album?.optString("picUrl")?.takeIf { it.isNotBlank() }?.let { ensureHttps(it) }
                songs += NeteaseSongSearchResult(
                    id = id,
                    title = item.optString("name"),
                    artist = artist,
                    coverUrl = cover,
                    coverThumbUrl = cover?.let { thumbUrl(it) },
                    duration = item.optLong("dt", 0L),
                )
            }
        }
        return songs
    }

    private fun searchMatch(keyword: String): List<NeteaseSongMatch> {
        val body = JSONObject().apply {
            put("s", keyword)
            put("type", 1)
            put("limit", 10)
            put("offset", 0)
        }
        val root = request("search/get", body)
        val songs = root.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
        return List(songs.length()) { index ->
            val song = songs.getJSONObject(index)
            val artists = song.optJSONArray("artists") ?: song.optJSONArray("ar") ?: JSONArray()
            val artist = List(artists.length()) { artists.getJSONObject(it).optString("name") }
                .filter { it.isNotBlank() }
                .joinToString(" / ")
            val album = song.optJSONObject("album") ?: song.optJSONObject("al")
            NeteaseSongMatch(
                id = song.optLong("id"),
                title = song.optString("name"),
                artist = artist,
                coverUrl = album?.optString("picUrl")?.takeIf { it.isNotBlank() }
            )
        }
    }

    private fun score(song: NeteaseSongMatch, title: String, artist: String, _durationMs: Long): Int {
        val normalizedTitle = normalize(song.title)
        val normalizedArtist = normalize(song.artist)
        var score = if (normalizedTitle == normalize(title)) 0 else 100
        if (artist.isNotBlank() && artist != "未知艺术家" && artist != "<unknown>" && normalizedArtist != normalize(artist)) score += 30
        return score
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace("（", "(")
        .replace("）", ")")
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), "")
        .replace(Regex("\\s+"), "")

    private fun request(path: String, body: JSONObject): JSONObject {
        val encrypted = NeteaseCrypto.weapi(body.toString())
        val form = "params=${URLEncoder.encode(encrypted.getValue("params"), "UTF-8")}&encSecKey=${URLEncoder.encode(encrypted.getValue("encSecKey"), "UTF-8")}"
        val request = Request.Builder()
            .url("https://music.163.com/weapi/$path")
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("User-Agent", MusicHttpClient.MUSIC_USER_AGENT)
            .header("Referer", "https://music.163.com")
            .header("X-Real-IP", randomChinaIp())
            .header("X-Forwarded-For", randomChinaIp())
            .build()
        return MusicHttpClient.client.newCall(request).execute().use { resp ->
            val response = resp.body.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: $response")
            JSONObject(response)
        }
    }

    // EAPI 加密请求（播放直链接口使用）：body 须为与加密摘要一致的 JSON 文本
    private fun requestEapi(path: String, body: String): JSONObject {
        val params = NeteaseCrypto.eapi("/api/$path", body)
        val form = "params=${URLEncoder.encode(params, "UTF-8")}"
        val request = Request.Builder()
            .url("https://interface3.music.163.com/eapi/$path")
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .header("User-Agent", MusicHttpClient.MUSIC_USER_AGENT)
            .header("Referer", "https://music.163.com")
            .header("Cookie", "os=pc; appver=; osver=; deviceId=pyncm!; channel=netease")
            .header("X-Real-IP", randomChinaIp())
            .header("X-Forwarded-For", randomChinaIp())
            .build()
        return MusicHttpClient.client.newCall(request).execute().use { resp ->
            val response = resp.body.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: $response")
            JSONObject(response)
        }
    }

    // 构造播放接口请求体（std 键序/分隔风格，需与 EAPI 摘要算法保持一致）；无损档返回 FLAC
    private fun eapiSongUrlPayload(songId: Long, level: String): String {
        val header = """{"os": "pc", "appver": "", "osver": "", "deviceId": "pyncm!", "requestId": "${(20000000..29999999).random()}"}"""
        val escaped = header.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"ids": [$songId], "level": "$level", "encodeType": "${if (level == "lossless") "flac" else "mp3"}", "header": "$escaped"}"""
    }

    private fun parseLrc(lrc: String, yrc: String, tlyric: String): NeteaseLyricData {
        val wordLines = parseYrc(yrc)
        if (wordLines.isNotEmpty()) return NeteaseLyricData(mergeTranslations(wordLines, parseLrcText(tlyric)))
        return NeteaseLyricData(mergeTranslations(parseLrcText(lrc), parseLrcText(tlyric)))
    }

    private fun parseYrc(raw: String): List<LyricLine> {
        val linePattern = Regex("\\[(\\d+),(\\d+)](.*)")
        val wordPattern = Regex("\\((\\d+),(\\d+),\\d+\\)([^()]*)")
        return raw.lineSequence().mapNotNull { rawLine ->
            val line = linePattern.find(rawLine) ?: return@mapNotNull null
            val start = line.groupValues[1].toLong()
            val words = wordPattern.findAll(line.groupValues[3]).map {
                val rawStart = it.groupValues[1].toLong()
                LyricWord(
                    // YRC 字标签的起点是绝对时间（与行标签同一时间基准）；个别源给的是相对行首的偏移，
                    // 小于行起点时按相对偏移补上行起点，统一归一为绝对时间
                    startMs = if (rawStart >= start) rawStart else start + rawStart,
                    durationMs = it.groupValues[2].toLong(),
                    text = it.groupValues[3]
                )
            }.filter { it.text.isNotEmpty() }.toList()
            LyricLine(start, words.joinToString("") { it.text }.trim(), words)
                .takeIf { it.text.isNotBlank() }
        }.toList()
    }

    // 随机中国大陆 IP（降低风控概率）
    private val chinaIpPrefixes = intArrayOf(36, 39, 42, 58, 59, 60, 101, 106, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 175, 180, 182, 183, 202, 203, 210, 211, 218, 219, 220, 221, 222, 223)
    private fun randomChinaIp(): String {
        val a = chinaIpPrefixes[kotlin.random.Random.nextInt(chinaIpPrefixes.size)]
        return "$a.${kotlin.random.Random.nextInt(256)}.${kotlin.random.Random.nextInt(256)}.${1 + kotlin.random.Random.nextInt(254)}"
    }

    // CDN 缩略图 URL：追加 ?param=128y128 请求小图
    internal fun thumbUrl(coverUrl: String): String {
        return coverUrl + if (coverUrl.contains("?")) "&param=128y128" else "?param=128y128"
    }

    // 确保 URL 使用 HTTPS（网易云 CDN 可能返回 HTTP 链接）
    private fun ensureHttps(url: String): String {
        return if (url.startsWith("http://")) url.replace("http://", "https://") else url
    }
}

// 标准 LRC 解析：网易云（无逐字标签时）、QQ、酷狗均复用
internal fun parseLrcText(lrc: String): List<LyricLine> {
    return lrc.lineSequence().mapNotNull { line ->
        val match = Regex("\\[(\\d+):(\\d+)(?:\\.(\\d+))?](.*)").find(line) ?: return@mapNotNull null
        LyricLine(
            timeMs = match.groupValues[1].toLong() * 60_000 +
                    match.groupValues[2].toLong() * 1_000 +
                    match.groupValues[3].padEnd(3, '0').take(3).toLong(),
            text = match.groupValues[4].trim()
        ).takeIf { it.text.isNotBlank() }
    }.sortedBy { it.timeMs }.toList()
}

// 按时间戳把翻译歌词合并进原歌词：优先精确匹配，其次取 500ms 内最近的一条
internal fun mergeTranslations(lines: List<LyricLine>, transLines: List<LyricLine>): List<LyricLine> {
    if (lines.isEmpty() || transLines.isEmpty()) return lines
    val byTime = transLines.associateBy { it.timeMs }
    val sorted = transLines.sortedBy { it.timeMs }
    return lines.map { line ->
        val translation = byTime[line.timeMs]?.text
            ?: sorted.minByOrNull { kotlin.math.abs(it.timeMs - line.timeMs) }
                ?.takeIf { kotlin.math.abs(it.timeMs - line.timeMs) <= 500L }
                ?.text
            ?: return@map line
        line.copy(translation = translation)
    }
}

// 把字符串平台标识（QQ 的 songmid、酷狗的 hash）转成稳定的数字 id，
// 搜索结果列表和播放列表统一用 Long 类型 id 做去重与关联
internal fun stableIdFromString(value: String): Long {
    var hash = -0x340d631b7bdddcdbL
    for (c in value) {
        hash = hash xor c.code.toLong()
        hash *= 0x100000001b3L
    }
    return hash
}
