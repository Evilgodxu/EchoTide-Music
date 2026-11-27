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

// 酷狗在线源：搜索、播放地址与歌词
// 歌曲标识为文件 hash，入库转稳定数字 id
internal object KugouMusicApi : OnlineMusicSource {

    // 缩略图尺寸 120，占位符支持 64/120/400/480
    private const val COVER_THUMB_SIZE = "120"

    // 歌词格式：krc 逐字，lrc 逐行
    private const val KRC_FORMAT = "krc"
    private const val LRC_FORMAT = "lrc"

    private val KRC_MAGIC = "krc1".toByteArray(Charsets.US_ASCII)

    // KRC 16 字节异或密钥，循环使用
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

    // 各接口字段名有差异，统一映射为结果模型
    private fun songResult(item: JSONObject): NeteaseSongSearchResult {
        val hash = item.optString("hash").ifBlank { item.optString("FileHash") }
        val filename = item.optString("filename").ifBlank { item.optString("FileName") }
        val rawTitle = item.optString("songname").ifBlank { item.optString("SongName") }
        val artist = item.optString("singername").ifBlank { item.optString("SingerName") }
        val rawCover = item.optJSONObject("trans_param")?.optString("union_cover")
            ?.takeIf { it.isNotBlank() }
            ?: item.optString("cover_url").takeIf { it.isNotBlank() }
            ?: item.optString("Image").takeIf { it.isNotBlank() }
        // duration 秒、timelen 毫秒，取其一
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
            sourceId = hash,
            // 搜索直接给专辑名与发行日期，无需详情
            album = item.optString("AlbumName").ifBlank { item.optString("album_name") }
                .takeIf { it.isNotBlank() },
            releaseDate = item.optString("PublishDate").ifBlank { item.optString("publish_date") }
                .takeIf { it.isNotBlank() },
        )
    }

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

    // 获取歌词：先搜候选再按格式下载
    // krc 带逐字，缺失或解密失败回退 lrc
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

    // null 时由调用方决定是否回退格式
    private fun downloadLyrics(id: String, accesskey: String, format: String): List<LyricLine>? = try {
        val url = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accesskey&fmt=$format&charset=utf8"
        val content = JSONObject(get(url)).optString("content")
        val raw = content.takeIf { it.isNotBlank() }?.let { Base64.getDecoder().decode(it) }
        // LRC 为明文；KRC 为 krc1 头+异或+zlib
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

    // KRC 解密：去 krc1 头，循环异或后 zlib 解压
    private fun decodeKrc(raw: ByteArray): String? {
        if (raw.size <= KRC_MAGIC.size || !raw.copyOf(KRC_MAGIC.size).contentEquals(KRC_MAGIC)) return null
        val body = raw.copyOfRange(KRC_MAGIC.size, raw.size)
        for (index in body.indices) {
            body[index] = (body[index].toInt() xor (KRC_KEY[index % KRC_KEY.size].toInt() and 0xFF)).toByte()
        }
        return inflateBytes(body)?.let { String(it, Charsets.UTF_8) }
    }

    // 无 songname 时从「歌手 - 歌名.mp3」提取歌名
    private fun titleFromFilename(filename: String): String {
        val base = filename.removeSuffix(".mp3")
        val idx = base.indexOf(" - ")
        return if (idx >= 0) base.substring(idx + 3) else base
    }

    // url 可能是字符串或数组，取第一个非空值
    private fun optStringOrFirst(obj: JSONObject, key: String): String? {
        val value = obj.opt(key) ?: return null
        return when (value) {
            is JSONArray -> value.optString(0).takeIf { it.isNotBlank() }
            else -> value.toString().takeIf { it.isNotBlank() }
        }
    }

    // 封面 {size} 为尺寸段，去掉或替换得原图/缩略
    // CDN 只发 http，统一升级为 https
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
