package com.yichao.evilgodxu.data.music.api

import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Base64
import java.util.zip.Inflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 酷我音乐在线源：官方 searchMusicBykeyWord 搜索 + mobi.s 加密链接 + newlyric 歌词。
 * 歌曲标识为 rid 数字串，播放与歌词接口均按 rid 请求。
 */
internal object KuwoMusicApi : OnlineMusicSource {

    private const val SEARCH_ENDPOINT = "https://www.kuwo.cn/search/searchMusicBykeyWord"
    private const val MOBI_ENDPOINT = "https://mobi.kuwo.cn/mobi.s"
    private const val LYRIC_ENDPOINT = "https://newlyric.kuwo.cn/newlyric.lrc"
    private const val BANG_ENDPOINT = "http://kbangserver.kuwo.cn/ksong.s"
    private const val PIC_ENDPOINT = "https://artistpicserver.kuwo.cn/pic.web"
    // 默认榜单：飙升榜
    private const val CHART_BANG_ID = "93"

    // 播放参数加密密钥与歌词加密密钥
    private val SONG_KEY = "ylzsxkwm".toByteArray()
    private val LYRIC_KEY = "yeelion".toByteArray()
    private val LRC_PREFIX = "tp=content".toByteArray()
    private val LRC_SEPARATOR = "\r\n\r\n".toByteArray()
    private val GBK: Charset = runCatching { Charset.forName("GBK") }.getOrDefault(Charsets.UTF_8)

    override suspend fun search(keyword: String, page: Int, pageSize: Int): List<NeteaseSongSearchResult> = withContext(Dispatchers.IO) {
        try {
            val query = "vipver=1&client=kt&ft=music&cluster=0&strategy=2012&encoding=utf8" +
                    "&rformat=json&mobi=1&issubtitle=1&show_copyright_off=1" +
                    "&pn=${page - 1}&rn=$pageSize&all=" + URLEncoder.encode(keyword, "UTF-8")
            val lists = JSONObject(get("$SEARCH_ENDPOINT?$query")).optJSONArray("abslist") ?: JSONArray()
            List(lists.length()) { index ->
                val item = lists.getJSONObject(index)
                val rid = item.optString("MUSICRID").ifBlank { item.optString("musicrid") }.removePrefix("MUSIC_")
                // 专辑封面优先取 web_albumpic_short（形如 120/xx/xx.jpg），拼接 CDN 前缀并放大为 300 尺寸；
                // 无专辑封面时回退 hts_MVPIC（MV 图）等兜底字段
                val albumShort = item.optString("web_albumpic_short")
                val cover = if (albumShort.isNotBlank()) {
                    "https://img1.kuwo.cn/star/albumcover/300/" + albumShort.removePrefix("120/")
                } else {
                    item.optString("hts_MVPIC").ifBlank { item.optString("albumpic") }
                        .ifBlank { item.optString("pic") }.takeIf { it.isNotBlank() }?.toHttps()
                }
                val durationSec = item.optString("DURATION").toLongOrNull()
                    ?: item.optLong("duration", 0L)
                NeteaseSongSearchResult(
                    id = stableIdFromString(rid),
                    title = item.optString("SONGNAME").ifBlank { item.optString("name") }
                        .ifBlank { item.optString("songName") },
                    artist = item.optString("ARTIST").ifBlank { item.optString("artist") },
                    coverUrl = cover,
                    coverThumbUrl = cover,
                    duration = durationSec * 1000L,
                    source = MusicSearchSource.KUWO,
                    sourceId = rid.ifBlank { null }
                )
            }
        } catch (e: Exception) {
            CrashLogManager.logException("KuwoMusicApi", "搜索歌曲失败", e)
            emptyList()
        }
    }

    /**
     * 内置榜单解析：飙升榜。
     *
     * 走 kbangserver 的老版接口：新版 m.kuwo.cn / www.kuwo.cn 的同一数据需要 kw_token 与 csrf 头，
     * 无凭据直接返回 404 CSRF Token Not Found，且站点已不再下发该 cookie。此处仅提供明文 http 站点。
     */
    override suspend fun chart(limit: Int): List<NeteaseSongSearchResult> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        try {
            val url = "$BANG_ENDPOINT?from=pc&fmt=json&pn=0&rn=$limit&type=bang&data=content" +
                    "&id=$CHART_BANG_ID&show_copyright_off=0&pcmp4=1&isbang=1"
            val list = JSONObject(get(url)).optJSONArray("musiclist") ?: JSONArray()
            List(minOf(list.length(), limit)) { index -> bangSong(list.getJSONObject(index)) }
                .filter { it.title.isNotBlank() }
        } catch (e: Exception) {
            CrashLogManager.logException("KuwoMusicApi", "解析榜单失败", e)
            emptyList()
        }
    }

    /** 榜单条目映射：字段命名与搜索接口不同，且只给 rid，封面需另取 */
    private fun bangSong(item: JSONObject): NeteaseSongSearchResult {
        val rid = item.optString("id").ifBlank { item.optString("rid") }.removePrefix("MUSIC_")
        val cover = rid.takeIf { it.isNotBlank() }?.let { fetchCover(it) }
        return NeteaseSongSearchResult(
            id = stableIdFromString(rid),
            title = item.optString("name").ifBlank { item.optString("songName") },
            artist = item.optString("artist").ifBlank { item.optString("artistName") },
            coverUrl = cover,
            coverThumbUrl = cover,
            // song_duration 为秒；同条目的 duration 字段不是时长
            duration = item.optString("song_duration").toLongOrNull()?.times(1000L) ?: 0L,
            source = MusicSearchSource.KUWO,
            sourceId = rid.ifBlank { null },
        )
    }

    // 榜单接口不含封面地址，仅给 rid；该接口按 rid 返回封面直链，取不到时留空由占位图兜底
    private fun fetchCover(rid: String): String? = runCatching {
        get("$PIC_ENDPOINT?type=rid_pic&pictype=300&size=300&rid=$rid").trim()
            .takeIf { it.startsWith("http") }
            ?.toHttps()
    }.getOrNull()

    /** 获取指定音质播放地址；无损/高品优先 flac，标准使用 mp3，组的后项兜底 */
    suspend fun songUrl(rid: String, quality: MusicQuality = MusicQuality.LOSSLESS): String? = withContext(Dispatchers.IO) {
        if (rid.isBlank()) return@withContext null
        try {
            val formats = when (quality) {
                MusicQuality.LOSSLESS, MusicQuality.HIGH -> arrayOf("flac", "mp3")
                MusicQuality.STANDARD -> arrayOf("mp3")
            }
            for (format in formats) {
                val query = "user=0&corp=kuwo&source=kwplayer_ar_5.1.0.0_B_jiakong_vh.apk&p2p=1" +
                        "&type=convert_url2&sig=0&format=$format&rid=$rid"
                val body = get("$MOBI_ENDPOINT?f=kuwo&q=${encryptQuery(query)}", userAgent = "okhttp/3.10.0")
                val url = Regex("http[^\\s$\"]+").find(body)?.value
                if (!url.isNullOrBlank()) return@withContext url.toHttps()
            }
            null
        } catch (e: Exception) {
            CrashLogManager.logException("KuwoMusicApi", "获取播放地址失败", e)
            null
        }
    }

    /** 获取歌词：解密 newlyric 响应后压平逐字标签，翻译按时间戳并入主行 */
    suspend fun lyricLines(result: NeteaseSongSearchResult): List<LyricLine>? = withContext(Dispatchers.IO) {
        val rid = result.sourceId ?: return@withContext null
        try {
            val raw = getBytes("$LYRIC_ENDPOINT?${buildLyricParams(rid)}")
            val lrc = decodeLyrics(raw) ?: return@withContext null
            parseKuwoLrc(lrc).takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            CrashLogManager.logException("KuwoMusicApi", "获取歌词失败", e)
            null
        }
    }

    private fun encryptQuery(query: String): String {
        val encrypted = kuwoCrypt(query.toByteArray(Charsets.UTF_8), SONG_KEY, 0)
        return Base64.getEncoder().encodeToString(encrypted)
    }

    private fun buildLyricParams(rid: String): String {
        val params = "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_$rid&lrcx=1"
        val encrypted = xorEncrypt(params.toByteArray(Charsets.UTF_8), LYRIC_KEY)
        return Base64.getEncoder().encodeToString(encrypted)
    }

    private fun decodeLyrics(raw: ByteArray): String? {
        if (raw.size < LRC_PREFIX.size || !raw.copyOfRange(0, LRC_PREFIX.size).contentEquals(LRC_PREFIX)) return null
        val sep = indexOf(raw, LRC_SEPARATOR) ?: return null
        val lrcData = inflate(raw.copyOfRange(sep + LRC_SEPARATOR.size, raw.size)) ?: return null
        val b64 = String(lrcData, Charsets.UTF_8)
        val decoded = Base64.getDecoder().decode(b64)
        return String(xorEncrypt(decoded, LYRIC_KEY), GBK)
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int? {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return null
    }

    private fun inflate(data: ByteArray): ByteArray? = try {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        inflater.end()
        out.toByteArray()
    } catch (e: Exception) {
        null
    }

    // 逐字歌词 <起,持续>字 标签压平为文本；酷我双语歌词为 [翻译行(<0,0> 开头), 主行] 同时间戳成对结构，
    // 翻译行按时间戳并入主行的 translation 字段，避免作为独立行参与当前行定位
    private fun parseKuwoLrc(raw: String): List<LyricLine> {
        val rxLine = Regex("^\\[(\\d{2}:\\d{2}\\.\\d{3})\\](.*)$")
        val rxWord = Regex("<(-?\\d+),(-?\\d+)>([^<]*)")
        val rxZh = Regex("[\\u4e00-\\u9fa5]")
        val main = mutableListOf<LyricLine>()
        val trans = mutableListOf<LyricLine>()
        raw.split(Regex("\r\n|\r|\n")).forEach { line ->
            val match = rxLine.find(line) ?: return@forEach
            val ts = match.groupValues[1]
            val timeMs = ts.substringBefore(':').toLong() * 60_000 +
                    ts.substringAfter(':').substringBefore('.').toLong() * 1_000 +
                    ts.substringAfter('.').toLong()
            val payload = match.groupValues[2]
            if (payload.startsWith("<0,0>")) {
                val t = payload.replace("<0,0>", "").trim()
                if (t.isNotEmpty() && rxZh.containsMatchIn(t)) trans.add(LyricLine(timeMs, t))
            } else {
                val words = rxWord.findAll(payload).toList()
                val text = if (words.isNotEmpty()) words.joinToString("") { it.groupValues[3] }
                else payload.replace("<0,0>", "").trim()
                if (text.isNotBlank()) main.add(LyricLine(timeMs, text))
            }
        }
        return mergeTranslations(main, trans).sortedBy { it.timeMs }
    }

    // 酷我私有 DES 变体：mobi.s 参数用 SONG_KEY 加密（mode 0 加密 / 1 解密）
    private fun kuwoCrypt(msg: ByteArray, key: ByteArray, mode: Int): ByteArray {
        var keyBits = 0L
        for (i in 0 until 8) keyBits = keyBits or ((key[i].toLong() and 0xFF) shl (i * 8))
        val subKeys = LongArray(16)
        buildSubKeys(keyBits, subKeys, mode)
        val blockCount = msg.size / 8
        val outWords = LongArray(blockCount + 1)
        for (m in 0 until blockCount) {
            var word = 0L
            for (b in 0 until 8) word = word or ((msg[m * 8 + b].toLong() and 0xFF) shl (b * 8))
            outWords[m] = desCipher(subKeys, word)
        }
        var tail = 0L
        val rem = msg.size % 8
        for (b in 0 until rem) tail = tail or ((msg[blockCount * 8 + b].toLong() and 0xFF) shl (b * 8))
        outWords[blockCount] = if (rem != 0 || mode == 0) desCipher(subKeys, tail) else 0L
        val out = ByteArray(outWords.size * 8)
        for (wi in outWords.indices) {
            for (b in 0 until 8) out[wi * 8 + b] = ((outWords[wi] shr (b * 8)) and 0xFFL).toByte()
        }
        return out
    }

    private fun buildSubKeys(keyBits: Long, out: LongArray, mode: Int) {
        var bits = bitTransform(ARRAY_PC1, 56, keyBits)
        for (i in 0 until 16) {
            bits = lsShift(bits, ARRAY_LS[i])
            out[i] = bitTransform(ARRAY_PC2, 64, bits)
        }
        if (mode == 1) out.reverse()
    }

    private fun lsShift(x: Long, rounds: Int): Long {
        val mask = ARRAY_LS_MASK[rounds]
        return ((x and mask) shl (28 - rounds)) or ((x and mask.inv()) ushr rounds)
    }

    private fun desCipher(subKeys: LongArray, block: Long): Long {
        val initial = bitTransform(ARRAY_IP2, 64, block)
        var lo = (initial and 0xFFFFFFFFL).toInt()
        var hi = ((initial ushr 32) and 0xFFFFFFFFL).toInt()
        for (i in 0 until 16) {
            val r = bitTransform(ARRAY_E, 64, hi.toLong() and 0xFFFFFFFFL) xor subKeys[i]
            var sOut = 0
            for (sbi in 7 downTo 0) {
                sOut = (sOut shl 4) or (MATRIX_NSBOX[sbi][((r shr (sbi * 8)) and 0xFFL).toInt()] and 0xF)
            }
            val nextLo = hi
            hi = ((lo.toLong() and 0xFFFFFFFFL) xor bitTransform(ARRAY_P, 32, sOut.toLong() and 0xFFFFFFFFL)).toInt()
            lo = nextLo
        }
        val combined = ((lo.toLong() and 0xFFFFFFFFL) shl 32) or (hi.toLong() and 0xFFFFFFFFL)
        return bitTransform(ARRAY_IP1, 64, combined)
    }

    private fun bitTransform(table: IntArray, count: Int, value: Long): Long {
        var result = 0L
        for (i in 0 until count) {
            val idx = table[i]
            if (idx < 0 || (value and (1L shl idx)) == 0L) continue
            result = result or (1L shl i)
        }
        return result
    }

    private fun xorEncrypt(data: ByteArray, key: ByteArray): ByteArray =
        ByteArray(data.size) { (data[it].toInt() xor (key[it % key.size].toInt() and 0xFF)).toByte() }

    private fun get(url: String, userAgent: String = MusicHttpClient.MUSIC_USER_AGENT): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return MusicHttpClient.client.newCall(request).execute().use { resp ->
            val body = resp.body.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            body
        }
    }

    private fun getBytes(url: String): ByteArray {
        val request = Request.Builder().url(url)
            .header("User-Agent", MusicHttpClient.MUSIC_USER_AGENT)
            .header("Referer", "http://www.kuwo.cn/")
            .build()
        return MusicHttpClient.client.newCall(request).execute().use { resp ->
            val body = resp.body.bytes()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            body
        }
    }

    private fun String.toHttps(): String = if (startsWith("http://")) replaceFirst("http://", "https://") else this

    private val ARRAY_LS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val ARRAY_LS_MASK = longArrayOf(0L, 0x100001L, 0x300003L)
    private val ARRAY_E = intArrayOf(
        31, 0, 1, 2, 3, 4, -1, -1, 3, 4, 5, 6, 7, 8, -1, -1,
        7, 8, 9, 10, 11, 12, -1, -1, 11, 12, 13, 14, 15, 16, -1, -1,
        15, 16, 17, 18, 19, 20, -1, -1, 19, 20, 21, 22, 23, 24, -1, -1,
        23, 24, 25, 26, 27, 28, -1, -1, 27, 28, 29, 30, 31, 30, -1, -1
    )
    private val ARRAY_IP1 = intArrayOf(
        39, 7, 47, 15, 55, 23, 63, 31, 38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29, 36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27, 34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25, 32, 0, 40, 8, 48, 16, 56, 24
    )
    private val ARRAY_IP2 = intArrayOf(
        57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
        61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
        56, 48, 40, 32, 24, 16, 8, 0, 58, 50, 42, 34, 26, 18, 10, 2,
        60, 52, 44, 36, 28, 20, 12, 4, 62, 54, 46, 38, 30, 22, 14, 6
    )
    private val ARRAY_P = intArrayOf(
        15, 6, 19, 20, 28, 11, 27, 16, 0, 14, 22, 25, 4, 17, 30, 9,
        1, 7, 23, 13, 31, 26, 2, 8, 18, 12, 29, 5, 21, 10, 3, 24
    )
    private val ARRAY_PC1 = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1,
        58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35, 62, 54, 46, 38,
        30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36,
        28, 20, 12, 4, 27, 19, 11, 3
    )
    private val ARRAY_PC2 = intArrayOf(
        13, 16, 10, 23, 0, 4, -1, -1, 2, 27, 14, 5, 20, 9, -1, -1,
        22, 18, 11, 3, 25, 7, -1, -1, 15, 6, 26, 19, 12, 1, -1, -1,
        40, 51, 30, 36, 46, 54, -1, -1, 29, 39, 50, 44, 32, 47, -1, -1,
        43, 48, 38, 55, 33, 52, -1, -1, 45, 41, 49, 35, 28, 31, -1, -1
    )
    private val MATRIX_NSBOX = arrayOf(
        intArrayOf(14, 4, 3, 15, 2, 13, 5, 3, 13, 14, 6, 9, 11, 2, 0, 5, 4, 1, 10, 12, 15, 6, 9, 10, 1, 8, 12, 7, 8, 11, 7, 0, 0, 15, 10, 5, 14, 4, 9, 10, 7, 8, 12, 3, 13, 1, 3, 6, 15, 12, 6, 11, 2, 9, 5, 0, 4, 2, 11, 14, 1, 7, 8, 13),
        intArrayOf(15, 0, 9, 5, 6, 10, 12, 9, 8, 7, 2, 12, 3, 13, 5, 2, 1, 14, 7, 8, 11, 4, 0, 3, 14, 11, 13, 6, 4, 1, 10, 15, 3, 13, 12, 11, 15, 3, 6, 0, 4, 10, 1, 7, 8, 4, 11, 14, 13, 8, 0, 6, 2, 15, 9, 5, 7, 1, 10, 12, 14, 2, 5, 9),
        intArrayOf(10, 13, 1, 11, 6, 8, 11, 5, 9, 4, 12, 2, 15, 3, 2, 14, 0, 6, 13, 1, 3, 15, 4, 10, 14, 9, 7, 12, 5, 0, 8, 7, 13, 1, 2, 4, 3, 6, 12, 11, 0, 13, 5, 14, 6, 8, 15, 2, 7, 10, 8, 15, 4, 9, 11, 5, 9, 0, 14, 3, 10, 7, 1, 12),
        intArrayOf(7, 10, 1, 15, 0, 12, 11, 5, 14, 9, 8, 3, 9, 7, 4, 8, 13, 6, 2, 1, 6, 11, 12, 2, 3, 0, 5, 14, 10, 13, 15, 4, 13, 3, 4, 9, 6, 10, 1, 12, 11, 0, 2, 5, 0, 13, 14, 2, 8, 15, 7, 4, 15, 1, 10, 7, 5, 6, 12, 11, 3, 8, 9, 14),
        intArrayOf(2, 4, 8, 15, 7, 10, 13, 6, 4, 1, 3, 12, 11, 7, 14, 0, 12, 2, 5, 9, 10, 13, 0, 3, 1, 11, 15, 5, 6, 8, 9, 14, 14, 11, 5, 6, 4, 1, 3, 10, 2, 12, 15, 0, 13, 2, 8, 5, 11, 8, 0, 15, 7, 14, 9, 4, 12, 7, 10, 9, 1, 13, 6, 3),
        intArrayOf(12, 9, 0, 7, 9, 2, 14, 1, 10, 15, 3, 4, 6, 12, 5, 11, 1, 14, 13, 0, 2, 8, 7, 13, 15, 5, 4, 10, 8, 3, 11, 6, 10, 4, 6, 11, 7, 9, 0, 6, 4, 2, 13, 1, 9, 15, 3, 8, 15, 3, 1, 14, 12, 5, 11, 0, 2, 12, 14, 7, 5, 10, 8, 13),
        intArrayOf(4, 1, 3, 10, 15, 12, 5, 0, 2, 11, 9, 6, 8, 7, 6, 9, 11, 4, 12, 15, 0, 3, 10, 5, 14, 13, 7, 8, 13, 14, 1, 2, 13, 6, 14, 9, 4, 1, 2, 14, 11, 13, 5, 0, 1, 10, 8, 3, 0, 11, 3, 5, 9, 4, 15, 2, 7, 8, 12, 15, 10, 7, 6, 12),
        intArrayOf(13, 7, 10, 0, 6, 9, 5, 15, 8, 4, 3, 10, 11, 14, 12, 5, 2, 11, 9, 6, 15, 12, 0, 3, 4, 1, 14, 13, 1, 2, 7, 8, 1, 2, 12, 15, 10, 4, 0, 3, 13, 14, 6, 9, 7, 8, 9, 6, 15, 1, 5, 12, 3, 10, 14, 5, 8, 7, 11, 0, 4, 13, 2, 11)
    )
}
