package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 测试用的最小元数据读取器：独立于被测写入实现，按各容器规范解析出标题/艺术家/专辑/封面/歌词，
 * 并提供「音频区」字节用于核对元数据写入未伤及音频。
 * ID3 帧解析同时覆盖 v2.3（32 位大端长度）与 v2.4（同步安全长度）及四种文本编码。
 */
internal object AudioMetaProbe {

    class Meta(
        val title: String?,
        val artist: String?,
        val album: String?,
        val cover: ByteArray?,
        val lyrics: String?,
    )

    fun read(bytes: ByteArray): Meta = when {
        bytes.matches(0, "ID3") -> Builder().apply { id3(bytes) }.build() // MP3
        bytes.matches(0, "fLaC") -> flac(bytes)
        bytes.matches(0, "OggS") -> ogg(bytes)
        bytes.matches(0, "RIFF") -> riff(bytes)
        bytes.matches(0, "FORM") -> iff(bytes, 4)
        bytes.matches(0, "FRM8") -> iff(bytes, 8)
        bytes.matches(0, "DSD ") -> dsf(bytes)
        bytes.matches(0, "MAC ") -> ape(bytes)
        bytes.matches(4, "ftyp") -> mp4(bytes)
        else -> error("未支持的容器: " + String(bytes, 0, minOf(12, bytes.size), StandardCharsets.ISO_8859_1))
    }

    /** 音频区：剥离元数据后的音频字节，用于核对写入未改动音频数据 */
    fun audioRegion(bytes: ByteArray): ByteArray = when {
        bytes.matches(0, "ID3") -> bytes.copyOfRange(id3End(bytes, 0), mp3End(bytes))
        bytes.matches(0, "fLaC") -> bytes.copyOfRange(flacAudioStart(bytes), bytes.size)
        bytes.matches(0, "OggS") -> oggPackets(bytes)
            .filterNot { it.matches(0, "OpusTags") || it.matches(0, "\u0001vorbis") || it.matches(0, "\u0003vorbis") }
            .joinByteArrays()
        bytes.matches(0, "RIFF") -> riffChunks(bytes, 12, 4).first { it.id == "data" }.payload(bytes)
        bytes.matches(0, "FORM") -> iffChunks(bytes, 12, 4).first { it.id == "SSND" }.payload(bytes)
        bytes.matches(0, "FRM8") -> iffChunks(bytes, 16, 8).first { it.id == "DSD " }.payload(bytes)
        bytes.matches(0, "DSD ") -> dsfAudio(bytes)
        bytes.matches(0, "MAC ") -> bytes.copyOfRange(0, apeTagStart(bytes))
        bytes.matches(4, "ftyp") -> mp4Audio(bytes)
        else -> error("未支持的容器")
    }

    class OggPageFact(
        val headerType: Int,
        val granule: Long,
        val sequence: Int,
        val segments: Int,
        val crcValid: Boolean,
    )

    /** Ogg 页序列：页头类型、granule 位置、页序号与段数，并核对每页 CRC，用于验证改写后的页面结构 */
    fun oggPages(bytes: ByteArray): List<OggPageFact> {
        val facts = mutableListOf<OggPageFact>()
        var p = 0
        while (p + 27 <= bytes.size && bytes.matches(p, "OggS")) {
            val segments = bytes[p + 26].toInt() and 0xFF
            if (p + 27 + segments > bytes.size) break
            val bodyLength = (0 until segments).sumOf { bytes[p + 27 + it].toInt() and 0xFF }
            val end = p + 27 + segments + bodyLength
            if (end > bytes.size) break
            val page = bytes.copyOfRange(p, end)
            val stored = leInt(page, 22).toLong() and 0xFFFFFFFFL
            facts += OggPageFact(
                headerType = bytes[p + 5].toInt() and 0xFF,
                granule = leLong(bytes, p + 6),
                sequence = leInt(bytes, p + 18),
                segments = segments,
                crcValid = (oggCrc(page).toLong() and 0xFFFFFFFFL) == stored,
            )
            p = end
        }
        return facts
    }

    // Ogg CRC：多项式 0x04C11DB7，起始值 0、不反转、不异或；计算时 CRC 字段按零参与，
    // 跳过该字段会少做 32 次移位而算出无效校验和
    private fun oggCrc(page: ByteArray): Int {
        val table = IntArray(256) { byte ->
            var value = byte shl 24
            repeat(8) {
                value = if (value and 0x80000000.toInt() != 0) (value shl 1) xor 0x04C11DB7 else value shl 1
            }
            value
        }
        var crc = 0
        page.forEachIndexed { index, value ->
            val octet = if (index in 22 until 26) 0 else value.toInt() and 0xFF
            crc = (crc shl 8) xor table[(crc ushr 24) xor octet]
        }
        return crc
    }

    // ---- 容器解析 ----

    private class Builder {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var cover: ByteArray? = null
        var lyrics: String? = null

        fun build() = Meta(title, artist, album, cover, lyrics)

        // ID3v2 帧区：文本帧取 TIT2/TPE1/TALB，附图帧取 APIC，歌词帧取 USLT
        fun id3(tag: ByteArray) {
            if (tag.size < 10 || !tag.matches(0, "ID3")) return
            val version = tag[3].toInt() and 0xFF
            val end = minOf(10 + syncSafe(tag, 6), tag.size)
            var p = 10
            while (p + 10 <= end) {
                val id = String(tag, p, 4, StandardCharsets.ISO_8859_1)
                if (id[0] == '\u0000') break
                val size = if (version >= 4) syncSafe(tag, p + 4) else beInt(tag, p + 4)
                if (size <= 0 || p + 10 + size > end) break
                val body = tag.copyOfRange(p + 10, p + 10 + size)
                when (id) {
                    "TIT2" -> title = id3Text(body)
                    "TPE1" -> artist = id3Text(body)
                    "TALB" -> album = id3Text(body)
                    "APIC" -> cover = apic(body)
                    "USLT" -> lyrics = uslt(body)
                }
                p += 10 + size
            }
        }

        // Vorbis 注释：小端 vendor 长度 + vendor + 条目数 + 逐条「小端长度 + KEY=value」
        fun vorbisComment(data: ByteArray, from: Int) {
            var p = from
            if (p + 4 > data.size) return
            val vendorLength = leInt(data, p)
            p += 4
            if (vendorLength < 0 || p + vendorLength > data.size) return
            p += vendorLength
            if (p + 4 > data.size) return
            val count = leInt(data, p)
            p += 4
            repeat(maxOf(0, minOf(count, MAX_COMMENT_ENTRIES))) {
                if (p + 4 > data.size) return
                val length = leInt(data, p)
                p += 4
                if (length < 0 || p + length > data.size) return
                val entry = String(data, p, length, StandardCharsets.UTF_8)
                p += length
                put(entry.substringBefore('='), entry.substringAfter('=', ""))
            }
        }

        fun put(key: String, value: String) {
            if (value.isEmpty()) return
            when (key.uppercase()) {
                "TITLE" -> title = value
                "ARTIST" -> artist = value
                "ALBUM" -> album = value
                "LYRICS" -> lyrics = value
                "METADATA_BLOCK_PICTURE" -> cover = picturePayload(Base64.getDecoder().decode(value))
            }
        }
    }

    private fun flac(bytes: ByteArray): Meta {
        val builder = Builder()
        var p = 4
        while (p + 4 <= bytes.size) {
            val header = bytes[p].toInt() and 0xFF
            val type = header and 0x7F
            val size = (bytes[p + 1].toInt() and 0xFF shl 16) or
                (bytes[p + 2].toInt() and 0xFF shl 8) or (bytes[p + 3].toInt() and 0xFF)
            p += 4
            if (p + size > bytes.size) break
            when (type) {
                // 4=VORBIS_COMMENT、6=PICTURE
                4 -> builder.vorbisComment(bytes.copyOfRange(p, p + size), 0)
                6 -> builder.cover = picturePayload(bytes.copyOfRange(p, p + size))
            }
            p += size
            if (header and 0x80 != 0) break
        }
        return builder.build()
    }

    private fun flacAudioStart(bytes: ByteArray): Int {
        var p = 4
        while (p + 4 <= bytes.size) {
            val header = bytes[p].toInt() and 0xFF
            val size = (bytes[p + 1].toInt() and 0xFF shl 16) or
                (bytes[p + 2].toInt() and 0xFF shl 8) or (bytes[p + 3].toInt() and 0xFF)
            p += 4 + size
            if (header and 0x80 != 0) break
        }
        return p
    }

    private fun ogg(bytes: ByteArray): Meta {
        val builder = Builder()
        val comment = oggPackets(bytes).firstOrNull { packet ->
            packet.matches(0, "OpusTags") || packet.matches(0, VORBIS_COMMENT_MARKER)
        } ?: return builder.build()
        val marker = if (comment.matches(0, "OpusTags")) OPUS_TAGS.length else VORBIS_COMMENT_MARKER.length
        builder.vorbisComment(comment, marker)
        return builder.build()
    }

    // Ogg 页序列还原为包序列：每页 27 字节页头 + 段表，段表值为 255 表示包未结束
    private fun oggPackets(bytes: ByteArray): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        var current = ByteArrayOutputStream()
        var p = 0
        while (p + 27 <= bytes.size && bytes.matches(p, "OggS")) {
            val segments = bytes[p + 26].toInt() and 0xFF
            if (p + 27 + segments > bytes.size) break
            var body = p + 27 + segments
            for (i in 0 until segments) {
                val length = bytes[p + 27 + i].toInt() and 0xFF
                if (body + length > bytes.size) return packets
                current.write(bytes, body, length)
                body += length
                if (length < 255) {
                    packets += current.toByteArray()
                    current = ByteArrayOutputStream()
                }
            }
            p = body
        }
        if (current.size() > 0) packets += current.toByteArray()
        return packets
    }

    private fun riff(bytes: ByteArray): Meta {
        val builder = Builder()
        riffChunks(bytes, 12, 4).forEach { chunk ->
            when {
                chunk.id == "ID3 " || chunk.id == "id3 " -> builder.id3(chunk.payload(bytes))
                chunk.id == "LIST" -> {
                    val info = chunk.payload(bytes)
                    if (info.size >= 4 && info.matches(0, "INFO")) {
                        riffChunks(info, 4, 4).forEach { item ->
                            val value = String(item.payload(info), StandardCharsets.UTF_8)
                            when (item.id) {
                                "INAM" -> builder.title = value
                                "IART" -> builder.artist = value
                                "IPRD" -> builder.album = value
                            }
                        }
                    }
                }
            }
        }
        return builder.build()
    }

    // IFF 分块容器（AIFF/AIFC 与 DSDIFF）：块头为「标识 + 大端长度」，仅长度字段位宽不同
    private fun iff(bytes: ByteArray, sizeBytes: Int): Meta {
        val builder = Builder()
        iffChunks(bytes, 4 + sizeBytes + 4, sizeBytes).forEach { chunk ->
            if (chunk.id == "ID3 " || chunk.id == "id3 ") builder.id3(chunk.payload(bytes))
        }
        return builder.build()
    }

    private fun dsf(bytes: ByteArray): Meta {
        val builder = Builder()
        val pointer = leLong(bytes, 20).toInt()
        if (pointer in 10 until bytes.size) builder.id3(bytes.copyOfRange(pointer, bytes.size))
        return builder.build()
    }

    private fun dsfAudio(bytes: ByteArray): ByteArray {
        var p = 28
        while (p + 12 <= bytes.size) {
            val id = String(bytes, p, 4, StandardCharsets.ISO_8859_1)
            val size = leLong(bytes, p + 4).toInt()
            if (size < 12 || p + size > bytes.size) break
            if (id == "data") return bytes.copyOfRange(p + 12, p + size)
            p += size
        }
        return ByteArray(0)
    }

    private fun ape(bytes: ByteArray): Meta {
        val builder = Builder()
        val end = if (bytes.matches(bytes.size - 128, "TAG")) bytes.size - 128 else bytes.size
        val footer = end - APE_FOOTER_BYTES
        if (footer < 0 || !bytes.matches(footer, "APETAGEX")) return builder.build()
        val tagSize = leInt(bytes, footer + 12)
        val count = leInt(bytes, footer + 16)
        var p = footer + APE_FOOTER_BYTES - tagSize
        repeat(maxOf(0, minOf(count, MAX_COMMENT_ENTRIES))) {
            if (p + 8 > bytes.size) return builder.build()
            val valueSize = leInt(bytes, p)
            val flags = leInt(bytes, p + 4)
            p += 8
            val keyEnd = (p until bytes.size).firstOrNull { bytes[it] == 0.toByte() } ?: return builder.build()
            val key = String(bytes, p, keyEnd - p, StandardCharsets.ISO_8859_1)
            p = keyEnd + 1
            if (valueSize < 0 || p + valueSize > bytes.size) return builder.build()
            val value = bytes.copyOfRange(p, p + valueSize)
            p += valueSize
            when (key.lowercase()) {
                "title" -> builder.title = String(value, StandardCharsets.UTF_8)
                "artist" -> builder.artist = String(value, StandardCharsets.UTF_8)
                "album" -> builder.album = String(value, StandardCharsets.UTF_8)
                "lyrics" -> builder.lyrics = String(value, StandardCharsets.UTF_8)
                "cover art (front)" -> {
                    // 二进制条目：值 = 文件名 + \0 + 图片数据
                    val separator = value.indexOf(0)
                    if (separator >= 0 && flags and 0x2 != 0) {
                        builder.cover = value.copyOfRange(separator + 1, value.size)
                    }
                }
            }
        }
        return builder.build()
    }

    private fun apeTagStart(bytes: ByteArray): Int {
        val end = if (bytes.matches(bytes.size - 128, "TAG")) bytes.size - 128 else bytes.size
        val footer = end - APE_FOOTER_BYTES
        if (footer < 0 || !bytes.matches(footer, "APETAGEX")) return end
        val tagSize = leInt(bytes, footer + 12)
        val hasHeader = leInt(bytes, footer + 20) and 0x80000000.toInt() != 0
        return footer + APE_FOOTER_BYTES - tagSize - if (hasHeader) APE_FOOTER_BYTES else 0
    }

    private fun mp4(bytes: ByteArray): Meta {
        val builder = Builder()
        walkMp4(bytes, 0, bytes.size, builder, 0)
        return builder.build()
    }

    private fun walkMp4(bytes: ByteArray, from: Int, to: Int, builder: Builder, depth: Int) {
        if (depth > 8) return
        var p = from
        while (p + 8 <= to) {
            val size = beInt(bytes, p)
            val type = String(bytes, p + 4, 4, StandardCharsets.ISO_8859_1)
            val headerLength = if (size == 1) 16 else 8
            val boxEnd = if (size <= 0) to else minOf(p + size, to)
            // 空载荷盒子（如 "free"）合法，其终点等于头部终点，故只在终点短于头部时终止
            if (boxEnd < p + headerLength) break
            when (type) {
                "moov", "udta", "trak", "mdia", "minf", "stbl" ->
                    walkMp4(bytes, p + headerLength, boxEnd, builder, depth + 1)
                // meta 盒子前置 4 字节版本与标志
                "meta" -> walkMp4(bytes, p + headerLength + 4, boxEnd, builder, depth + 1)
                "ilst" -> mp4Items(bytes, p + headerLength, boxEnd, builder)
            }
            p = boxEnd
        }
    }

    private fun mp4Items(bytes: ByteArray, from: Int, to: Int, builder: Builder) {
        var p = from
        while (p + 8 <= to) {
            val size = beInt(bytes, p)
            val name = String(bytes, p + 4, 4, StandardCharsets.ISO_8859_1)
                .map { if (it.code == 0xA9) 'c' else it }.joinToString("")
            val boxEnd = if (size <= 0) to else minOf(p + size, to)
            if (boxEnd < p + 8) break
            var q = p + 8
            while (q + 8 <= boxEnd) {
                val childSize = beInt(bytes, q)
                val childType = String(bytes, q + 4, 4, StandardCharsets.ISO_8859_1)
                val childEnd = if (childSize <= 0) boxEnd else minOf(q + childSize, boxEnd)
                if (childEnd < q + 8) break
                // data 原子：8 字节头 + 4 字节类型 + 4 字节区域设置，其后为载荷
                if (childType == "data" && childEnd > q + 16) {
                    val payload = bytes.copyOfRange(q + 16, childEnd)
                    when (name) {
                        "cnam" -> builder.title = String(payload, StandardCharsets.UTF_8)
                        "cART" -> builder.artist = String(payload, StandardCharsets.UTF_8)
                        "calb" -> builder.album = String(payload, StandardCharsets.UTF_8)
                        "clyr" -> builder.lyrics = String(payload, StandardCharsets.UTF_8)
                        "covr" -> builder.cover = payload
                    }
                }
                q = childEnd
            }
            p = boxEnd
        }
    }

    private fun mp4Audio(bytes: ByteArray): ByteArray {
        var p = 0
        while (p + 8 <= bytes.size) {
            val size = beInt(bytes, p)
            val type = String(bytes, p + 4, 4, StandardCharsets.ISO_8859_1)
            val headerLength = if (size == 1) 16 else 8
            val boxEnd = if (size <= 0) bytes.size else minOf(p + size, bytes.size)
            if (type == "mdat") return bytes.copyOfRange(p + headerLength, boxEnd)
            if (boxEnd < p + headerLength) break
            p = boxEnd
        }
        return ByteArray(0)
    }

    // ---- 分块与帧工具 ----

    private class Chunk(val id: String, val start: Int, val size: Int) {
        fun payload(source: ByteArray) = source.copyOfRange(start, start + size)
    }

    private fun riffChunks(bytes: ByteArray, from: Int, sizeBytes: Int): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var p = from
        while (p + 4 + sizeBytes <= bytes.size) {
            val size = leInt(bytes, p + 4)
            val start = p + 4 + sizeBytes
            if (size < 0 || start + size > bytes.size) break
            chunks += Chunk(String(bytes, p, 4, StandardCharsets.ISO_8859_1), start, size)
            p = start + size + (size and 1)
        }
        return chunks
    }

    private fun iffChunks(bytes: ByteArray, from: Int, sizeBytes: Int): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var p = from
        while (p + 4 + sizeBytes <= bytes.size) {
            val size = if (sizeBytes == 4) beInt(bytes, p + 4) else beLong(bytes, p + 4).toInt()
            val start = p + 4 + sizeBytes
            if (size < 0 || start + size > bytes.size) break
            chunks += Chunk(String(bytes, p, 4, StandardCharsets.ISO_8859_1), start, size)
            p = start + size + (size and 1)
        }
        return chunks
    }

    // ID3 文本帧：首字节为编码标志；帧内文本可能带结尾空字节，一并剥离
    private fun id3Text(body: ByteArray): String {
        if (body.size <= 1) return ""
        return decodeId3(body.copyOfRange(1, body.size), body[0].toInt() and 0xFF).trimEnd('\u0000')
    }

    // USLT：编码字节 + 语言(3) + 空结尾描述 + 歌词正文
    private fun uslt(body: ByteArray): String? {
        if (body.size <= 4) return null
        val encoding = body[0].toInt() and 0xFF
        var p = 4
        val terminator = if (encoding == 1 || encoding == 2) 2 else 1
        while (p + terminator <= body.size) {
            if (body[p] == 0.toByte() && (terminator == 1 || body[p + 1] == 0.toByte())) break
            p++
        }
        p += terminator
        return if (p > body.size) null else decodeId3(body.copyOfRange(p, body.size), encoding)
    }

    // APIC：编码字节 + MIME(空结尾) + 图片类型 + 空结尾描述 + 图片数据
    private fun apic(body: ByteArray): ByteArray? {
        if (body.size < 5) return null
        val encoding = body[0].toInt() and 0xFF
        var p = 1
        while (p < body.size && body[p] != 0.toByte()) p++
        p += 2 // MIME 结尾 + 图片类型
        val terminator = if (encoding == 1 || encoding == 2) 2 else 1
        while (p + terminator <= body.size) {
            if (body[p] == 0.toByte() && (terminator == 1 || body[p + 1] == 0.toByte())) break
            p++
        }
        p += terminator
        return if (p >= body.size) null else body.copyOfRange(p, body.size)
    }

    // PICTURE 块体：类型(4) + MIME(长度+内容) + 描述(长度+内容) + 宽高深色 + 数据长度(4) + 数据
    private fun picturePayload(block: ByteArray): ByteArray? {
        var p = 4
        if (p + 4 > block.size) return null
        p += 4 + beInt(block, p)
        if (p + 4 > block.size) return null
        p += 4 + beInt(block, p)
        p += 16
        if (p + 4 > block.size) return null
        val length = beInt(block, p)
        p += 4
        if (length < 0 || p + length > block.size) return null
        return block.copyOfRange(p, p + length)
    }

    private fun id3End(bytes: ByteArray, at: Int): Int = minOf(at + 10 + syncSafe(bytes, at + 6), bytes.size)

    // MP3 正文终点：末尾的 ID3v1（128 字节）不计入音频区
    private fun mp3End(bytes: ByteArray): Int = if (bytes.matches(bytes.size - 128, "TAG")) bytes.size - 128 else bytes.size

    private fun List<ByteArray>.joinByteArrays(): ByteArray {
        val out = ByteArrayOutputStream()
        forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun decodeId3(bytes: ByteArray, encoding: Int): String = when (encoding) {
        1 -> String(bytes, StandardCharsets.UTF_16)
        2 -> String(bytes, StandardCharsets.UTF_16BE)
        3 -> String(bytes, StandardCharsets.UTF_8)
        else -> String(bytes, StandardCharsets.ISO_8859_1)
    }

    private fun ByteArray.matches(at: Int, value: String): Boolean {
        if (at < 0 || at + value.length > size) return false
        return String(this, at, value.length, StandardCharsets.ISO_8859_1) == value
    }

    private fun syncSafe(bytes: ByteArray, at: Int): Int =
        if (at + 4 > bytes.size) 0 else
            (bytes[at].toInt() and 0x7F shl 21) or (bytes[at + 1].toInt() and 0x7F shl 14) or
                (bytes[at + 2].toInt() and 0x7F shl 7) or (bytes[at + 3].toInt() and 0x7F)

    private fun beInt(bytes: ByteArray, at: Int): Int =
        if (at + 4 > bytes.size) 0 else
            (bytes[at].toInt() and 0xFF shl 24) or (bytes[at + 1].toInt() and 0xFF shl 16) or
                (bytes[at + 2].toInt() and 0xFF shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun beLong(bytes: ByteArray, at: Int): Long =
        if (at + 8 > bytes.size) 0L else (beInt(bytes, at).toLong() shl 32) or (beInt(bytes, at + 4).toLong() and 0xFFFFFFFFL)

    private fun leInt(bytes: ByteArray, at: Int): Int =
        if (at + 4 > bytes.size) 0 else
            (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() and 0xFF shl 8) or
                (bytes[at + 2].toInt() and 0xFF shl 16) or (bytes[at + 3].toInt() and 0xFF shl 24)

    private fun leLong(bytes: ByteArray, at: Int): Long =
        if (at + 8 > bytes.size) 0L else (leInt(bytes, at).toLong() and 0xFFFFFFFFL) or (leInt(bytes, at + 4).toLong() shl 32)

    private const val MAX_COMMENT_ENTRIES = 4096
    private const val APE_FOOTER_BYTES = 32
    private const val OPUS_TAGS = "OpusTags"
    private const val VORBIS_COMMENT_MARKER = "\u0003vorbis"
}
