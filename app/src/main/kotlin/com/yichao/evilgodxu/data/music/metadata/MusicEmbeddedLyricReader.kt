package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 各容器内嵌歌词，按增强 LRC 解析；无歌词返回空
internal object MusicEmbeddedLyricReader {

    // 头部读取上限；M4A 改走盒子头定点下钻
    private const val HEADER_CAP = 512 * 1024
    // 尾窗上限，WAV 标签紧贴文件末尾
    private const val TAIL_CAP = 2 * 1024 * 1024
    // ID3v2 标签上限，含封面大帧
    private const val MAX_MP3_TAG = 4 * 1024 * 1024
    // ID3v2 头长度，先取小段判定
    private const val ID3_HEADER_BYTES = 10
    // data 头：类型4 + 区域4 + 版本8
    private const val DATA_ATOM_HEADER_BYTES = 16
    // Ogg：OpusTags 或类型3 vorbis
    private const val OPUS_COMMENT_MARKER = "OpusTags"
    private const val VORBIS_COMMENT_MARKER = "\u0003vorbis"
    // M4A 歌词原子：©lyr 与 lyr
    private const val LYRICS_ATOM = "©lyr"
    private const val BARE_LYRICS_ATOM = "lyr"

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

    // 取内嵌歌词；无可读文件返回 null
    private fun readTagText(context: Context, track: MusicTrack): String? {
        val prefix = LocalAudioSource.read(context, track.path, track.audioUri, 0L, ID3_HEADER_BYTES)
            ?: return null
        // 带 ID3 头时按声明尺寸精确读取
        if (prefix.size >= ID3_HEADER_BYTES && prefix.startsWith("ID3")) {
            val tagSize = Id3v2Tag.syncsafe(prefix, 6)
            if (tagSize <= 0) return null
            val tag = LocalAudioSource.read(
                context, track.path, track.audioUri, 0L,
                (tagSize + ID3_HEADER_BYTES).coerceAtMost(MAX_MP3_TAG),
            ) ?: return null
            return Id3v2Tag.readUslt(tag, 0)
        }
        // M4A 歌词可能在封面之后，按盒子头定点下钻
        if (prefix.startsWith("ftyp", 4)) {
            return readMp4Lyrics(LocalAudioSource.size(context, track.path, track.audioUri)) { offset, count ->
                LocalAudioSource.read(context, track.path, track.audioUri, offset, count)
            }
        }
        val header = LocalAudioSource.read(context, track.path, track.audioUri, 0L, HEADER_CAP) ?: prefix
        return extractLyrics(header) ?: extractLyricsFromTail(context, track, header)
    }

    // 按容器提取歌词，未找到返回 null
    internal fun extractLyrics(bytes: ByteArray): String? = when {
        isMp4(bytes) -> readMp4Lyrics(bytes.size.toLong()) { offset, count -> sliceOf(bytes, offset, count) }
        isFlac(bytes) -> extractFlacLyrics(bytes)
        isOgg(bytes) -> extractOggLyrics(bytes)
        else -> LosslessContainerTags.readLyrics(bytes, null, 0L, null)
    }

    /** M4A 歌词：©lyr/lyr 与 ---- 自由格式 */
    internal fun readMp4Lyrics(fileSize: Long?, readAt: (Long, Int) -> ByteArray?): String? {
        var found: String? = null
        Mp4TagReader.forEachAtom(fileSize, readAt) { atom ->
            val lyrics = when (atom.type) {
                LYRICS_ATOM, BARE_LYRICS_ATOM -> decodeDataAtom(atom.payload, 0, atom.payload.size)
                Mp4TagSchema.FREE_FORM_ATOM ->
                    decodeFreeformLyrics(atom.payload, 0, atom.payload.size)
                else -> null
            }
            if (lyrics == null) {
                true
            } else {
                found = lyrics
                false
            }
        }
        return found
    }

    // 越界或读不满返回 null
    private fun sliceOf(bytes: ByteArray, offset: Long, count: Int): ByteArray? {
        if (offset < 0L || count <= 0 || offset + count > bytes.size) return null
        val from = offset.toInt()
        return bytes.copyOfRange(from, from + count)
    }

    // WAV 标签在音频后，尾窗不足时按偏移读
    private fun extractLyricsFromTail(context: Context, track: MusicTrack, header: ByteArray): String? {
        // 标签在头部的容器不读尾窗
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

    // FLAC 注释块 type4 取 LYRICS
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

    // Ogg：定位 OpusTags 或类型3+vorbis
    private fun extractOggLyrics(bytes: ByteArray): String? {
        val opus = bytes.indexOfAscii(OPUS_COMMENT_MARKER)
        val vorbis = bytes.indexOfAscii(VORBIS_COMMENT_MARKER)
        val (start, headerLength) = when {
            opus >= 0 -> opus to OPUS_COMMENT_MARKER.length
            vorbis >= 0 -> vorbis to VORBIS_COMMENT_MARKER.length
            else -> return null
        }
        return parseVorbisLyrics(bytes, start + headerLength, bytes.size - start - headerLength)
    }

    // Vorbis：优先 LYRICS，其次 SYNCED
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

    // data 载荷自 +16 起，版本标志占 8 字节
    private fun decodeDataAtom(bytes: ByteArray, start: Int, end: Int): String? {
        var p = start
        while (p + 8 <= end) {
            val size = int32BE(bytes, p)
            if (size < 8 || p + size > end) return null
            if (String(bytes, p + 4, 4, StandardCharsets.ISO_8859_1) == "data") {
                if (size <= DATA_ATOM_HEADER_BYTES) return null
                return String(bytes, p + DATA_ATOM_HEADER_BYTES, size - DATA_ATOM_HEADER_BYTES, StandardCharsets.UTF_8)
                    .takeIf { it.isNotBlank() }
            }
            p += size
        }
        return null
    }

    // 自定义原子：mean4 + 名称 + data
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
