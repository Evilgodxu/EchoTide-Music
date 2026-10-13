package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import com.yichao.evilgodxu.data.music.model.LyricLine
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 本地音频内嵌歌词读取：解析 MP3(ID3v2 USLT)、FLAC/OGG(Vorbis 注释 LYRICS)、M4A(©lyr)、
// WAV(容器内 "ID3 " 块)、AIFF/AIFC 与 DSDIFF("ID3 " 块)、DSF(尾部 ID3)、APE(APEv2 条目) 中的歌词文本，
// 统一按增强 LRC 解析为时间轴歌词；非本地音频源或无内嵌歌词时返回空列表。
// 标签位于音频之后的容器（WAV/DSF/APE 及标签后置的 AIFF/DSDIFF）需要尾窗定位，
// 尾窗之外的标签再按绝对偏移定点读取
internal object MusicEmbeddedLyricReader {

    // 非 MP3 容器读取的头部字节上限：FLAC/OGG 的注释块位于文件头部附近。
    // M4A 不走头窗——其歌词原子可能被大封面顶出窗口，改按盒子头定点下钻（见 readMp4Lyrics）
    private const val HEADER_CAP = 512 * 1024
    // 尾部窗口上限：WAV/DSF/APE 的标签紧贴文件末尾，尾窗只需覆盖标签自身
    private const val TAIL_CAP = 2 * 1024 * 1024
    // MP3 ID3v2 标签大小上限（含封面等大帧，歌词 USLT 帧通常位于标签前部）
    private const val MAX_MP3_TAG = 4 * 1024 * 1024
    // ID3v2 标签头长度，用于先取小段前缀判定是否为 ID3 容器
    private const val ID3_HEADER_BYTES = 10
    // M4A data 原子头长度：类型(4) + 区域设置(4) + 版本与标志(8)
    private const val DATA_ATOM_HEADER_BYTES = 16
    // Ogg 注释包标记：Opus 为包标识 "OpusTags"，Vorbis 为包类型 3 + "vorbis"
    private const val OPUS_COMMENT_MARKER = "OpusTags"
    private const val VORBIS_COMMENT_MARKER = "\u0003vorbis"
    // M4A 歌词原子的两种写法：iTunes 风格与非 © 前缀写法
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
        // M4A 的歌词原子与文本标签同在 ilst 下，可能排在大封面之后，头窗取不到，故按盒子头定点下钻
        if (prefix.startsWith("ftyp", 4)) {
            return readMp4Lyrics(LocalAudioSource.size(context, track.path, track.audioUri)) { offset, count ->
                LocalAudioSource.read(context, track.path, track.audioUri, offset, count)
            }
        }
        val header = LocalAudioSource.read(context, track.path, track.audioUri, 0L, HEADER_CAP) ?: prefix
        return extractLyrics(header) ?: extractLyricsFromTail(context, track, header)
    }

    // 按容器格式提取内嵌歌词文本，未找到返回 null。
    // 无独立解析分支的容器交给容器标签层处理（AIFF/DSDIFF 的 ID3 块置于音频之前，头窗即可覆盖）
    internal fun extractLyrics(bytes: ByteArray): String? = when {
        isMp4(bytes) -> readMp4Lyrics(bytes.size.toLong()) { offset, count -> sliceOf(bytes, offset, count) }
        isFlac(bytes) -> extractFlacLyrics(bytes)
        isOgg(bytes) -> extractOggLyrics(bytes)
        else -> LosslessContainerTags.readLyrics(bytes, null, 0L, null)
    }

    /**
     * M4A 的内嵌歌词：©lyr / lyr 普通原子与 ---- 自由格式原子（键名 lyrics）。
     *
     * 不按固定头窗解析：ilst 下的封面原子（covr，可达数 MB）常排在歌词原子之前，头窗遇到超出窗口的
     * 盒子会判结构不完整，于是整份标签一个字段都取不到。改按盒子头逐层定点下钻，封面只读其盒子头。
     */
    internal fun readMp4Lyrics(fileSize: Long?, readAt: (Long, Int) -> ByteArray?): String? {
        var found: String? = null
        Mp4TagReader.forEachAtom(fileSize, readAt) { atom ->
            val lyrics = when (atom.type) {
                LYRICS_ATOM, BARE_LYRICS_ATOM -> decodeDataAtom(atom.payload, 0, atom.payload.size)
                MusicMetadataWriter.FREE_FORM_ATOM ->
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

    // 内存字节的定点读取：越界或读不满即返回 null
    private fun sliceOf(bytes: ByteArray, offset: Long, count: Int): ByteArray? {
        if (offset < 0L || count <= 0 || offset + count > bytes.size) return null
        val from = offset.toInt()
        return bytes.copyOfRange(from, from + count)
    }

    // 标签位于音频之后的容器：WAV（容器内 "ID3 " 块，旧布局则在文件末尾的带 footer 标签）、
    // DSF（头部元数据指针）、APE（APEv2 页脚）以及把 ID3 块置于音频之后的 AIFF/DSDIFF。
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

    // OGG(Opus/Vorbis)：Opus 的注释包以 "OpusTags" 起头，Vorbis 的注释头包为
    // 「包类型 3 + "vorbis"」，定位到标记后解析其后的 Vorbis 注释结构
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

    // 定位 data 子原子：原子长度为「4 字节类型 + 4 字节区域设置 + 载荷」，
    // 载荷自 p+16 起、长度为 size-16；头部的「版本 + 标志」8 字节已计入原子长度
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
