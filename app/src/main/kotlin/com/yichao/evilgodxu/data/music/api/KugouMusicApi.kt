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

    // 列表缩略图尺寸段：封面 CDN 的 {size} 占位符支持 64/120/400/480
    private const val COVER_THUMB_SIZE = "120"

    // 歌词下载格式：krc 为逐字歌词，lrc 为逐行歌词
    private const val KRC_FORMAT = "krc"
    private const val LRC_FORMAT = "lrc"

    private val KRC_MAGIC = "krc1".toByteArray(Charsets.US_ASCII)

    // KRC 密文的 16 字节异或密钥，按字节循环使用
    private val KRC_KEY = byteArrayOf(
        0x40, 0x47, 0x61, 0x77, 0x5E, 0x32, 0x74, 0x47,
        0x51, 0x36, 0x31, 0x2D, 0xCE.toByte(), 0xD2.toByte(), 0x6E, 0x69,
    )

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
        // 封面地址带 {size} 尺寸段：原图去除该段，缩略图按值替换
        val rawCover = item.optString("album_sizable_cover").takeIf { it.isNotBlank() }
        return NeteaseSongSearchResult(
            id = stableIdFromString(hash),
            title = item.optString("songname").ifBlank { titleFromFilename(item.optString("filename")) },
            artist = artist,
            coverUrl = originalCover(rawCover),
            coverThumbUrl = sizedCover(rawCover, COVER_THUMB_SIZE),
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
        val rawCover = item.optJSONObject("trans_param")?.optString("union_cover")
            ?.takeIf { it.isNotBlank() }
            ?: item.optString("cover_url").takeIf { it.isNotBlank() }
            ?: item.optString("Image").takeIf { it.isNotBlank() }
        // duration 为秒，timelen 为毫秒，二者取其一
        val durationSec = item.optString("duration").toLongOrNull()
            ?: item.optLong("Duration", 0L)
        val timelen = item.optLong("timelen", 0L)
        return NeteaseSongSearchResult(
            id = stableIdFromString(hash),
            title = rawTitle.ifBlank { titleFromFilename(filename) },
            artist = artist,
            coverUrl = originalCover(rawCover),
            coverThumbUrl = sizedCover(rawCover, COVER_THUMB_SIZE),
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

    /**
     * 获取歌词：先按关键词/hash 搜候选，再做格式下载。
     *
     * 同一候选有两种下载格式：`fmt=krc` 带逐字时间轴，`fmt=lrc` 只有逐行时间轴。
     * 酷狗对多数歌曲都备有 krc，故优先取 krc，其缺失或解密失败时回退 lrc。
     */
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
            downloadLyrics(id, accesskey, KRC_FORMAT)?.let { return@withContext it }
            downloadLyrics(id, accesskey, LRC_FORMAT)
        } catch (e: Exception) {
            CrashLogManager.logException("KugouMusicApi", "获取歌词失败", e)
            null
        }
    }

    // 下载并解析指定格式的歌词；格式不可用、载荷为空或解析不出内容时返回 null，由调用方决定是否回退
    private fun downloadLyrics(id: String, accesskey: String, format: String): List<LyricLine>? = try {
        val url = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accesskey&fmt=$format&charset=utf8"
        val content = JSONObject(get(url)).optString("content")
        val raw = content.takeIf { it.isNotBlank() }?.let { Base64.getDecoder().decode(it) }
        // LRC 载荷是明文，KRC 载荷是「krc1」头 + 异或 + zlib 的密文
        val text = when {
            raw == null -> null
            format == KRC_FORMAT -> decodeKrc(raw)
            else -> String(raw, Charsets.UTF_8)
        }
        if (text.isNullOrBlank()) {
            null
        } else {
            val lines = if (format == KRC_FORMAT) parseKrcText(text) else parseWordTimedLrcText(text)
            lines.takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        CrashLogManager.logException("KugouMusicApi", "下载歌词失败: 格式=$format", e)
        null
    }

    // KRC 解密：丢弃「krc1」头后按 16 字节密钥循环异或，再 zlib 解压
    private fun decodeKrc(raw: ByteArray): String? {
        if (raw.size <= KRC_MAGIC.size || !raw.copyOf(KRC_MAGIC.size).contentEquals(KRC_MAGIC)) return null
        val body = raw.copyOfRange(KRC_MAGIC.size, raw.size)
        for (index in body.indices) {
            body[index] = (body[index].toInt() xor (KRC_KEY[index % KRC_KEY.size].toInt() and 0xFF)).toByte()
        }
        return inflateBytes(body)?.let { String(it, Charsets.UTF_8) }
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

    // 封面 CDN 地址形如 http://imge.kugou.com/stdmusic/{size}/{path}：{size} 是尺寸段，
    // 替换为具体值得到对应尺寸的缩略图，去除该段则返回原图；CDN 只发 http，统一升级为 https
    private fun originalCover(raw: String?): String? = normalizeCoverUrl(raw?.replace("/{size}/", "/"))

    private fun sizedCover(raw: String?, size: String): String? =
        normalizeCoverUrl(raw?.replace("{size}", size))

    private fun normalizeCoverUrl(url: String?): String? {
        val value = url?.takeIf { it.isNotBlank() } ?: return null
        return if (value.startsWith("http://")) "https://${value.removePrefix("http://")}" else value
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
