package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

// ID3v2 标签的帧与标签构造：MP3、WAV 以及 AIFF/AIFC、DSDIFF、DSF 内嵌的 ID3 块共用同一套帧编码。
// 新建标签统一取 v2.4（文本 UTF-8、内容描述符以单 0 结尾、长度字段为同步安全整数），
// 读取既有标签时按标签自身的版本解析，避免把 v2.3 的帧当 v2.4 读。
// 写入端与内嵌歌词读取器的编码分支一一对应：v2.4 用 UTF-8，v2.3 用带 BOM 的 UTF-16
internal object Id3v2Tag {

    // 新建标签版本
    const val TAG_VERSION = 4

    // 既有标签的版本；非 ID3v2.3/2.4 标签或标签缺失时取新建版本。
    // v2.3 的帧长度字段为 32 位大端、文本为 UTF-16，与 v2.4 的同步安全长度、UTF-8 互不兼容，
    // 容器改写保留帧时必须以原版本重建，否则保留下来的帧会被按错版本读成乱码
    fun versionOf(tag: ByteArray?): Int =
        if (tag != null && tag.size >= 10 && tag.startsWithAscii("ID3", 0)) {
            (tag[3].toInt() and 0xFF).takeIf { it in 3..4 } ?: TAG_VERSION
        } else TAG_VERSION

    // USLT 帧的语言字段：ID3v2 规范用 "XXX" 表示语言未定义
    private val LYRICS_LANGUAGE = "XXX".toByteArray(StandardCharsets.ISO_8859_1)

    // 以既有标签的帧区为底，覆盖 title/artist/album/cover/lyrics 对应的帧，保留其余帧。
    // 字段为 null 表示保留原值；无既有标签且无字段可写时返回 null（无内容可写）。
    // 返回值为帧区字节，不含标签头与 footer，由调用方按容器选择是否加 footer 组装
    fun replaceFrames(
        existing: ByteArray?,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
        version: Int,
    ): ByteArray? {
        val frames = ByteArrayOutputStream()
        var titleWritten = title == null
        var artistWritten = artist == null
        var albumWritten = album == null
        var coverWritten = cover == null
        var lyricsWritten = lyrics == null
        if (existing != null) {
            forEachFrame(existing, 0) { frameStart, id, bodyStart, length ->
                val raw = existing.copyOfRange(frameStart, bodyStart + length)
                when (id) {
                    "TIT2" -> if (!titleWritten) {
                        textFrame(frames, "TIT2", title!!, version); titleWritten = true
                    } else {
                        frames.write(raw)
                    }
                    "TPE1" -> if (!artistWritten) {
                        textFrame(frames, "TPE1", artist!!, version); artistWritten = true
                    } else {
                        frames.write(raw)
                    }
                    "TALB" -> if (!albumWritten) {
                        textFrame(frames, "TALB", album!!, version); albumWritten = true
                    } else {
                        frames.write(raw)
                    }
                    "APIC" -> if (cover != null && !coverWritten) {
                        apicFrame(frames, cover, version); coverWritten = true
                    } else {
                        frames.write(raw)
                    }
                    "USLT" -> if (!lyricsWritten) {
                        usltFrame(frames, lyrics!!, version); lyricsWritten = true
                    } else {
                        frames.write(raw)
                    }
                    else -> frames.write(raw)
                }
                false
            }
        }
        if (!titleWritten && title != null) textFrame(frames, "TIT2", title, version)
        if (!artistWritten && artist != null) textFrame(frames, "TPE1", artist, version)
        if (!albumWritten && album != null) textFrame(frames, "TALB", album, version)
        if (!coverWritten && cover != null) apicFrame(frames, cover, version)
        if (!lyricsWritten && lyrics != null) usltFrame(frames, lyrics, version)
        return frames.toByteArray().takeIf { it.isNotEmpty() }
    }

    // 组装完整标签：头部 + 帧区，footer 为 WAV 尾部标签所需的 ID3v2 footer（以 "3DI" 开头）。
    // footer 与头部除标识外字段完全一致，且不计入标签头声明的长度
    fun buildTag(frames: ByteArray, version: Int, flags: Int = 0, footer: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(StandardCharsets.US_ASCII))
        out.write(byteArrayOf(version.toByte(), 0, flags.toByte()))
        out.write(syncsafeBytes(frames.size))
        out.write(frames)
        if (footer) {
            out.write("3DI".toByteArray(StandardCharsets.US_ASCII))
            out.write(byteArrayOf(version.toByte(), 0, flags.toByte()))
            out.write(syncsafeBytes(frames.size))
        }
        return out.toByteArray()
    }

    // 取标签内首个 APIC（附图）帧的图片数据：编码字节 + MIME(空结尾) + 图片类型 + 描述 + 图片数据。
    // 描述字段的结尾宽度随编码变化（UTF-16 为双 0），MIME 恒为单字节编码
    fun readApic(bytes: ByteArray, at: Int): ByteArray? {
        var result: ByteArray? = null
        forEachFrame(bytes, at) { _, id, bodyStart, length ->
            if (id == "APIC") {
                result = decodeApic(bytes, bodyStart, length)
                return@forEachFrame true
            }
            false
        }
        return result
    }

    private fun decodeApic(bytes: ByteArray, offset: Int, length: Int): ByteArray? {
        val end = (offset + length).coerceAtMost(bytes.size)
        if (offset + 4 > end) return null
        val encoding = bytes[offset].toInt() and 0xFF
        var p = offset + 1
        while (p < end && bytes[p] != 0.toByte()) p++
        p++
        // 图片类型
        p++
        // 描述字段：UTF-16 以双 0 结尾
        val terminator = if (encoding == 1 || encoding == 2) 2 else 1
        while (p + terminator <= end) {
            if (bytes[p] == 0.toByte() && (terminator == 1 || bytes[p + 1] == 0.toByte())) break
            p++
        }
        p += terminator
        if (p > end) return null
        return bytes.copyOfRange(p, end).takeIf { it.isNotEmpty() }
    }

    // 取标签内指定文本帧的文本，供标题(TIT2)/艺术家(TPE1)/专辑(TALB)读取
    fun readTextFrame(bytes: ByteArray, at: Int, id: String): String? {
        var result: String? = null
        forEachFrame(bytes, at) { _, frameId, bodyStart, length ->
            if (frameId == id) {
                result = decodeTextFrame(bytes, bodyStart, length)
                return@forEachFrame true
            }
            false
        }
        return result
    }

    // 文本帧数据：编码字节 + 文本。结尾的 NUL 只是终止符，解码后一并去掉
    private fun decodeTextFrame(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 1 || offset + length > data.size) return null
        val encoding = data[offset].toInt() and 0xFF
        val start = offset + 1
        val end = offset + length
        val littleEndian = if (encoding == 1 && start + 2 <= end) {
            when {
                data[start] == 0xff.toByte() && data[start + 1] == 0xfe.toByte() -> true
                data[start] == 0xfe.toByte() && data[start + 1] == 0xff.toByte() -> false
                else -> null
            }
        } else null
        return decodeText(data.copyOfRange(start, end), encoding, littleEndian)
            .trimEnd('\u0000')
            .takeIf { it.isNotBlank() }
    }

    // 取标签内首个 USLT（非同步歌词）帧的歌词文本
    fun readUslt(bytes: ByteArray, at: Int): String? {
        var result: String? = null
        forEachFrame(bytes, at) { _, id, bodyStart, length ->
            if (id == "USLT") {
                result = decodeUslt(bytes, bodyStart, length)
                return@forEachFrame true
            }
            false
        }
        return result
    }

    // USLT 帧数据：编码字节 + 语言(3) + 空结尾内容描述符 + 歌词文本
    private fun decodeUslt(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 4 || offset + length > data.size) return null
        val encoding = data[offset].toInt() and 0xFF
        val end = offset + length
        val terminator = if (encoding == 1 || encoding == 2) 2 else 1
        var p = offset + 4
        while (p + terminator <= end) {
            if (data[p] == 0.toByte() && (terminator == 1 || data[p + 1] == 0.toByte())) break
            p++
        }
        p += terminator
        if (p > end) return null
        // UTF-16 文本起始处的 BOM 决定字节序（ID3v2 规范要求 UTF-16 字符串以 BOM 开头）
        val littleEndian = if (encoding == 1 && p + 2 <= end) {
            when {
                data[p] == 0xff.toByte() && data[p + 1] == 0xfe.toByte() -> true
                data[p] == 0xfe.toByte() && data[p + 1] == 0xff.toByte() -> false
                else -> null
            }
        } else null
        return decodeText(data.copyOfRange(p, end), encoding, littleEndian)
    }

    // ID3v2 文本编码：0=ISO-8859-1、1=UTF-16(带 BOM)、2=UTF-16BE、3=UTF-8
    private fun decodeText(bytes: ByteArray, encoding: Int, littleEndian: Boolean?): String = when (encoding) {
        0 -> String(bytes, StandardCharsets.ISO_8859_1)
        1 -> decodeUtf16(bytes, littleEndian)
        2 -> String(bytes, StandardCharsets.UTF_16BE)
        else -> String(bytes, StandardCharsets.UTF_8)
    }

    // UTF-16 解码：无 BOM 时按零字节奇偶分布推断字节序，并剥离解码产生的 BOM 字符
    private fun decodeUtf16(bytes: ByteArray, littleEndian: Boolean?): String {
        val little = littleEndian ?: inferUtf16Endianness(bytes)
        val text = if (little) String(bytes, StandardCharsets.UTF_16LE) else String(bytes, StandardCharsets.UTF_16BE)
        return text.removePrefix("\uFEFF")
    }

    // 推断 UTF-16 字节序：ASCII 字符的高位字节恒为零，统计偶数位（BE）与奇数位（LE）的零字节数
    private fun inferUtf16Endianness(bytes: ByteArray): Boolean {
        var littleScore = 0
        var bigScore = 0
        var index = 0
        while (index + 1 < bytes.size) {
            if (bytes[index] == 0.toByte()) bigScore++
            if (bytes[index + 1] == 0.toByte()) littleScore++
            index += 2
        }
        return littleScore > bigScore
    }

    fun textFrame(out: ByteArrayOutputStream, id: String, value: String, version: Int) {
        val data = if (version >= 4) {
            byteArrayOf(3) + value.toByteArray(StandardCharsets.UTF_8) + 0
        } else {
            byteArrayOf(1) + byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
                value.toByteArray(StandardCharsets.UTF_16LE) + byteArrayOf(0, 0)
        }
        frame(out, id, data, version)
    }

    // USLT 帧数据：编码字节 + 语言(3) + 空内容描述符 + 歌词文本。描述符结尾宽度随编码变化
    fun usltFrame(out: ByteArrayOutputStream, lyrics: String, version: Int) {
        val text = if (version >= 4) {
            byteArrayOf(3) + LYRICS_LANGUAGE + byteArrayOf(0) + lyrics.toByteArray(StandardCharsets.UTF_8)
        } else {
            byteArrayOf(1) + LYRICS_LANGUAGE + byteArrayOf(0, 0) +
                byteArrayOf(0xff.toByte(), 0xfe.toByte()) + lyrics.toByteArray(StandardCharsets.UTF_16LE)
        }
        frame(out, "USLT", text, version)
    }

    fun apicFrame(out: ByteArrayOutputStream, cover: ByteArray, version: Int) {
        val mime = MusicMetadataWriter.sniffMimeType(cover).toByteArray(StandardCharsets.ISO_8859_1)
        val data = if (version >= 4) {
            byteArrayOf(3) + mime + byteArrayOf(0, 3, 0) + cover
        } else {
            byteArrayOf(1) + mime + byteArrayOf(0) + byteArrayOf(3) + byteArrayOf(0, 0) + cover
        }
        frame(out, "APIC", data, version)
    }

    fun frame(out: ByteArrayOutputStream, id: String, data: ByteArray, version: Int) {
        out.write(id.toByteArray(StandardCharsets.US_ASCII))
        out.write(if (version >= 4) syncsafeBytes(data.size) else beIntBytes(data.size))
        out.write(byteArrayOf(0, 0)); out.write(data)
    }

    // ID3v2 长度字段为 28 位同步安全整数：每字节仅低 7 位有效
    fun syncsafe(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0x7f shl 21) or (bytes[p + 1].toInt() and 0x7f shl 14) or
            (bytes[p + 2].toInt() and 0x7f shl 7) or (bytes[p + 3].toInt() and 0x7f)

    fun syncsafeBytes(v: Int): ByteArray =
        byteArrayOf((v shr 21 and 0x7f).toByte(), (v shr 14 and 0x7f).toByte(), (v shr 7 and 0x7f).toByte(), (v and 0x7f).toByte())

    // 遍历标签内的帧；标签头不合法或帧结构不成立即终止。
    // block 返回 true 表示提前结束遍历；frameStart 为帧头起点，bodyStart/length 为帧体区间
    private inline fun forEachFrame(
        bytes: ByteArray,
        at: Int,
        block: (frameStart: Int, id: String, bodyStart: Int, length: Int) -> Boolean,
    ) {
        if (at + 10 > bytes.size || !bytes.startsWithAscii("ID3", at)) return
        val version = bytes[at + 3].toInt() and 0xFF
        if (version !in 3..4) return
        // footer 不计入头部声明的长度，故声明长度即帧区上界
        val tagEnd = (at + 10 + syncsafe(bytes, at + 6)).coerceAtMost(bytes.size)
        val flags = bytes[at + 5].toInt() and 0xFF
        var p = at + 10
        if (flags and 0x40 != 0 && p + 4 <= tagEnd) {
            val extSize = if (version >= 4) syncsafe(bytes, p) else beInt(bytes, p)
            p += 4 + extSize
        }
        while (p + 10 <= tagEnd) {
            val id = String(bytes, p, 4, StandardCharsets.ISO_8859_1)
            if (!isFrameId(id)) break
            val length = if (version >= 4) syncsafe(bytes, p + 4) else beInt(bytes, p + 4)
            if (length < 0 || p + 10 + length > tagEnd) break
            if (block(p, id, p + 10, length)) return
            p += 10 + length
        }
    }

    private fun isFrameId(id: String): Boolean =
        id.length == 4 && id.all { it in 'A'..'Z' || it in '0'..'9' }

    private fun ByteArray.startsWithAscii(value: String, at: Int): Boolean {
        if (at < 0 || at + value.length > size) return false
        for (i in value.indices) {
            if (this[at + i].toInt() and 0xFF != value[i].code) return false
        }
        return true
    }

    private fun beInt(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0xFF shl 24) or (bytes[p + 1].toInt() and 0xFF shl 16) or
            (bytes[p + 2].toInt() and 0xFF shl 8) or (bytes[p + 3].toInt() and 0xFF)

    private fun beIntBytes(v: Int): ByteArray =
        byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
}
