package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 本地音频内嵌歌词读取：解析 MP3(ID3v2 USLT)、FLAC/OGG(Vorbis 注释 LYRICS)、M4A(©lyr)、
// WAV(尾部 ID3)、AIFF/AIFC 与 DSDIFF("ID3 " 块)、DSF(尾部 ID3)、APE(APEv2 条目) 中的歌词文本，
// 统一按增强 LRC 解析为时间轴歌词；非本地音频源或无内嵌歌词时返回空列表。
// 标签位于文件末尾的容器（WAV/DSF/APE 及标签后置的 AIFF/DSDIFF）需要尾窗定位，
// 尾窗之外的标签再按绝对偏移定点读取
internal object MusicEmbeddedLyricReader {

    // 非 MP3 容器读取的头部字节上限（FLAC/OGG 注释与 M4A moov 均位于文件头部附近）
    private const val HEADER_CAP = 512 * 1024
    // 尾部窗口上限：WAV/DSF/APE 的标签紧贴文件末尾，尾窗只需覆盖标签自身
    private const val TAIL_CAP = 2 * 1024 * 1024
    // MP3 ID3v2 标签大小上限（含封面等大帧，歌词 USLT 帧通常位于标签前部）
    private const val MAX_MP3_TAG = 4 * 1024 * 1024
    // ID3v2 标签头长度，用于先取小段前缀判定是否为 ID3 容器
    private const val ID3_HEADER_BYTES = 10

    suspend fun read(context: Context, track: MusicTrack): List<LyricLine> = withContext(Dispatchers.IO) {
        try {
            MusicMetadataCache.parseLyricsText(readTagText(context, track).orEmpty())
        } catch (e: Exception) {
            CrashLogManager.logException(
                "MusicEmbeddedLyricReader",
                "读取内嵌歌词失败: 歌曲=${track.title} 路径=${track.path}",
                e
            )
            emptyList()
        }
    }

    // 取内嵌歌词文本：无本地可读文件返回 null（在线流为 http，取不到流）。
    // 已缓存为 content/file 的在线曲目照常解析：缓存时会把歌词内嵌进文件，
    // 这里能读回来才是「歌词缓存文件丢失后仍可恢复」的兜底
    private fun readTagText(context: Context, track: MusicTrack): String? {
        val prefix = LocalAudioSource.read(context, track.path, track.audioUri, 0L, ID3_HEADER_BYTES)
            ?: return null
        // 带 ID3 头的文件按声明的标签尺寸精确读取：标签可能含大封面帧，读取量随之增大
        if (prefix.size >= ID3_HEADER_BYTES && prefix.startsWith("ID3")) {
            val tagSize = Id3v2Tag.syncsafe(prefix, 6)
            if (tagSize <= 0) return null
            val tag = LocalAudioSource.read(
                context, track.path, track.audioUri, 0L,
                (tagSize + ID3_HEADER_BYTES).coerceAtMost(MAX_MP3_TAG),
            ) ?: return null
            return Id3v2Tag.readUslt(tag, 0)
        }
        val header = LocalAudioSource.read(context, track.path, track.audioUri, 0L, HEADER_CAP) ?: prefix
        return extractLyrics(header) ?: extractLyricsFromTail(context, track, header)
    }

    // 按容器格式提取内嵌歌词文本，未找到返回 null。
    // 无独立解析分支的容器交给容器标签层处理（AIFF/DSDIFF 的 ID3 块置于音频之前，头窗即可覆盖）
    private fun extractLyrics(bytes: ByteArray): String? = when {
        isMp4(bytes) -> extractMp4Lyrics(bytes)
        isFlac(bytes) -> extractFlacLyrics(bytes)
        isOgg(bytes) -> extractOggLyrics(bytes)
        else -> LosslessContainerTags.readLyrics(bytes, null, 0L, null)
    }

    // 标签位于文件末尾的容器：WAV（带 footer 的尾部 ID3）、DSF（头部元数据指针）、
    // APE（APEv2 页脚）以及把 ID3 块置于音频之后的 AIFF/DSDIFF。
    // 尾窗覆盖不到整个标签时（超大封面）由 readAt 按标签起始绝对偏移定点读取
    private fun extractLyricsFromTail(context: Context, track: MusicTrack, header: ByteArray): String? {
        // 标签在头部的容器（FLAC/M4A/Ogg）不读尾窗：头窗已解析不出歌词，再读尾窗也是空
        if (!LosslessContainerTags.usesTrailingTag(header)) return null
        val (tail, tailOffset) = LocalAudioSource.tail(context, track.path, track.audioUri, TAIL_CAP)
            ?: return null
        return LosslessContainerTags.readLyrics(header, tail, tailOffset) { offset, count ->
            LocalAudioSource.read(context, track.path, track.audioUri, offset, count)
        }
    }

    private fun isFlac(bytes: ByteArray) = bytes.startsWith("fLaC")
    private fun isOgg(bytes: ByteArray) = bytes.startsWith("OggS")
    private fun isMp4(bytes: ByteArray) = bytes.startsWith("ftyp", 4)

    // FLAC：遍历元数据块，取 VORBIS_COMMENT(4) 块中的 LYRICS 字段
    private fun extractFlacLyrics(bytes: ByteArray): String? {
        var p = 4
        while (p + 4 <= bytes.size) {
            val header = bytes[p].toInt() and 0xff
            val type = header and 0x7f
            val length = (bytes[p + 1].toInt() and 0xff shl 16) or
                (bytes[p + 2].toInt() and 0xff shl 8) or (bytes[p + 3].toInt() and 0xff)
            p += 4
            if (p + length > bytes.size) return null
            if (type == 4) return parseVorbisLyrics(bytes, p, length)
            p += length
            if (header and 0x80 != 0) break
        }
        return null
    }

    // OGG(Opus/Vorbis)：定位注释包标记后解析其后的 Vorbis 注释结构
    private fun extractOggLyrics(bytes: ByteArray): String? {
        val opus = bytes.indexOfAscii("OpusTags")
        val vorbis = bytes.indexOfAscii("vorbis_comment")
        val (start, headerLength) = when {
            opus >= 0 -> opus to "OpusTags".length
            vorbis >= 0 -> vorbis to "vorbis_comment".length
            else -> return null
        }
        return parseVorbisLyrics(bytes, start + headerLength, bytes.size - start - headerLength)
    }

    // Vorbis 注释：LE 长度的 vendor + 字段数 + "KEY=value" 字段；优先 LYRICS，其次 SYNCED/UNSYNCEDLYRICS
    private fun parseVorbisLyrics(data: ByteArray, offset: Int, length: Int): String? {
        var p = offset
        val end = (offset + length).coerceAtMost(data.size)
        if (p + 4 > end) return null
        val vendorLength = intLE(data, p)
        if (vendorLength < 0 || p + 4 + vendorLength > end) return null
        p += 4 + vendorLength
        if (p + 4 > end) return null
        val count = intLE(data, p)
        p += 4
        var fallback: String? = null
        repeat(count.coerceAtLeast(0)) {
            if (p + 4 > end) return@repeat
            val valueLength = intLE(data, p)
            p += 4
            if (valueLength < 0 || p + valueLength > end) return@repeat
            val entry = String(data, p, valueLength, StandardCharsets.UTF_8)
            p += valueLength
            val key = entry.substringBefore('=').uppercase()
            val value = entry.substringAfter('=', "").takeIf { it.isNotBlank() } ?: return@repeat
            when (key) {
                "LYRICS" -> return value
                "SYNCEDLYRICS", "UNSYNCEDLYRICS" -> if (fallback == null) fallback = value
            }
        }
        return fallback
    }

    // M4A：递归遍历 moov/udta/meta/ilst，取 ©lyr(或 lyr) 与 ----:LYRICS 自定义原子
    private fun extractMp4Lyrics(bytes: ByteArray): String? =
        extractMp4LyricsAt(bytes, 0, bytes.size, isMeta = false)

    private fun extractMp4LyricsAt(bytes: ByteArray, start: Int, end: Int, isMeta: Boolean): String? {
        // meta 原子在子原子前有 4 字节版本/标志
        var p = start + if (isMeta) 4 else 0
        while (p + 8 <= end) {
            val size = int32BE(bytes, p)
            if (size < 8 || p + size > end) return null
            val type = String(bytes, p + 4, 4, StandardCharsets.ISO_8859_1)
            when (type) {
                "©lyr", "lyr" -> decodeDataAtom(bytes, p + 8, p + size)?.let { return it }
                "----" -> decodeFreeformLyrics(bytes, p + 8, p + size)?.let { return it }
                "moov", "udta", "meta", "ilst" ->
                    extractMp4LyricsAt(bytes, p + 8, p + size, isMeta = type == "meta")?.let { return it }
            }
            p += size
        }
        return null
    }

    // 定位 data 子原子：跳过 type(4) + locale(4) 后为歌词文本
    private fun decodeDataAtom(bytes: ByteArray, start: Int, end: Int): String? {
        var p = start
        while (p + 8 <= end) {
            val size = int32BE(bytes, p)
            if (size < 8 || p + size > end) return null
            if (String(bytes, p + 4, 4, StandardCharsets.ISO_8859_1) == "data") {
                return String(bytes, p + 16, p + size - 16, StandardCharsets.UTF_8)
                    .takeIf { it.isNotBlank() }
            }
            p += size
        }
        return null
    }

    // ---- 自定义原子：mean(4 字节) + UTF-8 空结尾名称 + data 子原子
    private fun decodeFreeformLyrics(bytes: ByteArray, start: Int, end: Int): String? {
        var p = start + 4
        val nameStart = p
        while (p < end && bytes[p] != 0.toByte()) p++
        if (p >= end) return null
        val name = String(bytes, nameStart, p - nameStart, StandardCharsets.UTF_8)
        p++
        if (!name.equals("lyrics", ignoreCase = true)) return null
        return decodeDataAtom(bytes, p, end)
    }

    private fun int32BE(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0xff shl 24) or (bytes[p + 1].toInt() and 0xff shl 16) or
            (bytes[p + 2].toInt() and 0xff shl 8) or (bytes[p + 3].toInt() and 0xff)

    private fun intLE(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0xff) or (bytes[p + 1].toInt() and 0xff shl 8) or
            (bytes[p + 2].toInt() and 0xff shl 16) or (bytes[p + 3].toInt() and 0xff shl 24)

    private fun ByteArray.startsWith(value: String): Boolean =
        size >= value.length && String(this, 0, value.length, StandardCharsets.US_ASCII) == value

    private fun ByteArray.startsWith(value: String, offset: Int): Boolean =
        size >= offset + value.length && String(this, offset, value.length, StandardCharsets.US_ASCII) == value

    private fun ByteArray.indexOfAscii(value: String): Int =
        (0..(size - value.length).coerceAtLeast(0)).firstOrNull { startsWith(value, it) } ?: -1
}
