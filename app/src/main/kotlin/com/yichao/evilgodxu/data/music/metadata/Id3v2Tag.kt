package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

// ID3v2 帧构造；写法 v2.4，按原版本读
internal object Id3v2Tag {

    // 新建标签版本
    const val TAG_VERSION = 4

    // 文本帧：字段到固定帧标识，读写共用
    private val TEXT_FRAME_IDS: Map<TagField, String> = mapOf(
        TagField.TITLE to "TIT2",
        TagField.ARTIST to "TPE1",
        TagField.ALBUM_ARTIST to "TPE2",
        TagField.ALBUM to "TALB",
        TagField.GENRE to "TCON",
        TagField.TRACK_NUMBER to "TRCK",
        TagField.DISC_NUMBER to "TPOS",
        TagField.LYRICIST to "TEXT",
        TagField.COMPOSER to "TCOM",
        TagField.COPYRIGHT to "TCOP",
        TagField.LANGUAGE to "TLAN",
    )

    // 日期帧：v2.4 用 TDRC，v2.3 用 TYER
    private const val DATE_FRAME_V4 = "TDRC"
    private const val DATE_FRAME_V3 = "TYER"

    // ReplayGain 写 TXXX 并带描述符
    private val TXXX_DESCRIPTORS: Map<TagField, String> = mapOf(
        TagField.REPLAY_GAIN_TRACK_GAIN to "REPLAYGAIN_TRACK_GAIN",
        TagField.REPLAY_GAIN_TRACK_PEAK to "REPLAYGAIN_TRACK_PEAK",
        TagField.REPLAY_GAIN_ALBUM_GAIN to "REPLAYGAIN_ALBUM_GAIN",
        TagField.REPLAY_GAIN_ALBUM_PEAK to "REPLAYGAIN_ALBUM_PEAK",
        TagField.REPLAY_GAIN_REFERENCE_LOUDNESS to "REPLAYGAIN_REFERENCE_LOUDNESS",
    )

    private const val COMMENT_FRAME_ID = "COMM"
    private const val TXXX_FRAME_ID = "TXXX"
    private const val RATING_FRAME_ID = "POPM"
    private const val LYRICS_FRAME_ID = "USLT"
    private const val COVER_FRAME_ID = "APIC"

    // 语言未定义时用 "XXX"
    private val UNDEFINED_LANGUAGE = "XXX".toByteArray(StandardCharsets.ISO_8859_1)

    // 评分满格 255，星级 1..5 线性映射
    private const val RATING_MAX = 255
    private const val RATING_STARS = 5

    // 非 2.3/2.4 用新建，帧按原版本重建
    fun versionOf(tag: ByteArray?): Int =
        if (tag != null && tag.size >= 10 && tag.startsWithAscii("ID3", 0)) {
            (tag[3].toInt() and 0xFF).takeIf { it in 3..4 } ?: TAG_VERSION
        } else TAG_VERSION

    /** 兼容入口：散字段覆盖，null 保留原值 */
    fun replaceFrames(
        existing: ByteArray?,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
        version: Int,
    ): ByteArray? = replaceFrames(
        existing = existing,
        fields = TrackTagFields.build {
            put(TagField.TITLE, title)
            put(TagField.ARTIST, artist)
            put(TagField.ALBUM, album)
            put(TagField.LYRICS, lyrics)
        },
        cover = cover,
        removeCover = false,
        version = version,
    )

    /** 以既有帧区为底覆盖字段与封面，其余帧原样保留 */
    fun replaceFrames(
        existing: ByteArray?,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean,
        version: Int,
    ): ByteArray? {
        // 待写入帧按标识归并，首次出现处替换
        val pending = LinkedHashMap<String, ByteArray>()
        val pendingTxxx = LinkedHashMap<String, ByteArray>()
        fields.entries().forEach { (field, value) ->
            val descriptor = TXXX_DESCRIPTORS[field]
            when {
                descriptor != null -> pendingTxxx[descriptor] = txxxFrameBytes(descriptor, value, version)
                field == TagField.LYRICS -> pending[LYRICS_FRAME_ID] = usltFrameBytes(value, version)
                field == TagField.COMMENT -> pending[COMMENT_FRAME_ID] = commentFrameBytes(value, version)
                field == TagField.RATING -> pending[RATING_FRAME_ID] = popmFrameBytes(value)
                field == TagField.DATE -> pending[dateFrameId(version)] = textFrameBytes(dateFrameId(version), value, version)
                else -> TEXT_FRAME_IDS[field]?.let { pending[it] = textFrameBytes(it, value, version) }
            }
        }
        // 封面状态决定既有 APIC 替换还是保留
        val newCoverFrame = cover?.let { apicFrameBytes(it, version) }
        val coverChanged = newCoverFrame != null || removeCover

        val frames = ByteArrayOutputStream()
        // 已顶替的帧标识，同名帧其后原样保留
        val replaced = mutableSetOf<String>()
        if (existing != null) {
            forEachFrame(existing, 0) { source, frameStart, id, bodyStart, length ->
                val raw = source.copyOfRange(frameStart, bodyStart + length)
                when {
                    // 删除时丢弃首个 APIC，其余重复帧按同一状态
                    id == COVER_FRAME_ID -> {
                        if (!coverChanged) {
                            frames.write(raw)
                        } else if (replaced.add(COVER_FRAME_ID) && newCoverFrame != null) {
                            frames.write(newCoverFrame)
                        }
                    }
                    id == TXXX_FRAME_ID -> {
                        // TXXX 按描述符命中才替换
                        val descriptor = decodeTxxxDescription(source, bodyStart, length)
                        val replacement = descriptor?.let { pendingTxxx[it] }
                        if (replacement != null && replaced.add("$TXXX_FRAME_ID:$descriptor")) {
                            frames.write(replacement)
                        } else {
                            frames.write(raw)
                        }
                    }
                    pending.containsKey(id) -> {
                        if (replaced.add(id)) frames.write(pending.getValue(id)) else frames.write(raw)
                    }
                    else -> frames.write(raw)
                }
                false
            }
        }
        pending.forEach { (id, bytes) -> if (replaced.add(id)) frames.write(bytes) }
        pendingTxxx.forEach { (descriptor, bytes) -> if (replaced.add("$TXXX_FRAME_ID:$descriptor")) frames.write(bytes) }
        if (newCoverFrame != null && replaced.add(COVER_FRAME_ID)) frames.write(newCoverFrame)
        return frames.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** 读标签全量字段；未写入的字段不出现在结果中 */
    fun readFields(bytes: ByteArray, at: Int): TrackTagFields = TrackTagFields.build {
        TEXT_FRAME_IDS.forEach { (field, frameId) -> put(field, readTextFrame(bytes, at, frameId)) }
        put(TagField.DATE, readTextFrame(bytes, at, DATE_FRAME_V4) ?: readTextFrame(bytes, at, DATE_FRAME_V3))
        put(TagField.COMMENT, readComment(bytes, at))
        put(TagField.RATING, readRating(bytes, at))
        TXXX_DESCRIPTORS.forEach { (field, descriptor) -> put(field, readTxxx(bytes, at, descriptor)) }
        put(TagField.LYRICS, readUslt(bytes, at)?.takeIf { it.isNotBlank() })
    }

    // 组装标签：头 + 帧区；footer 不计入长度
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

    // APIC：编码、MIME、类型、描述、数据
    fun readApic(bytes: ByteArray, at: Int): ByteArray? {
        var result: ByteArray? = null
        forEachFrame(bytes, at) { source, _, id, bodyStart, length ->
            if (id == COVER_FRAME_ID) {
                result = decodeApic(source, bodyStart, length)
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
        p = skipTerminated(bytes, p, end, encoding)
        if (p > end) return null
        return bytes.copyOfRange(p, end).takeIf { it.isNotEmpty() }
    }

    // 取首个指定文本帧，供单帧字段读取
    fun readTextFrame(bytes: ByteArray, at: Int, id: String): String? {
        var result: String? = null
        forEachFrame(bytes, at) { source, _, frameId, bodyStart, length ->
            if (frameId == id) {
                result = decodeTextFrame(source, bodyStart, length)
                return@forEachFrame true
            }
            false
        }
        return result
    }

    // 文本帧：编码 + 文本，去掉结尾 NUL
    private fun decodeTextFrame(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 1 || offset + length > data.size) return null
        val encoding = data[offset].toInt() and 0xFF
        val start = offset + 1
        val end = offset + length
        return decodeText(data.copyOfRange(start, end), encoding, utf16LittleEndian(data, start, end, encoding))
            .trimEnd('\u0000')
            .takeIf { it.isNotBlank() }
    }

    // 取首个 USLT 歌词文本
    fun readUslt(bytes: ByteArray, at: Int): String? {
        var result: String? = null
        forEachFrame(bytes, at) { source, _, id, bodyStart, length ->
            if (id == LYRICS_FRAME_ID) {
                result = decodeUslt(source, bodyStart, length)
                return@forEachFrame true
            }
            false
        }
        return result
    }

    // USLT：编码 + 语言(3) + 空描述 + 歌词
    private fun decodeUslt(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 4 || offset + length > data.size) return null
        val encoding = data[offset].toInt() and 0xFF
        val end = offset + length
        val p = skipTerminated(data, offset + 4, end, encoding)
        if (p > end) return null
        return decodeText(data.copyOfRange(p, end), encoding, utf16LittleEndian(data, p, end, encoding))
    }

    // COMM：只认空描述符的整曲注释
    fun readComment(bytes: ByteArray, at: Int): String? {
        var result: String? = null
        forEachFrame(bytes, at) { source, _, id, bodyStart, length ->
            if (id == COMMENT_FRAME_ID) {
                val decoded = decodeComment(source, bodyStart, length)
                if (decoded != null) {
                    result = decoded
                    return@forEachFrame true
                }
            }
            false
        }
        return result
    }

    // COMM：编码 + 语言(3) + 空描述 + 文本
    private fun decodeComment(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 4 || offset + length > data.size) return null
        val encoding = data[offset].toInt() and 0xFF
        val end = offset + length
        val descriptionStart = offset + 4
        val textStart = skipTerminated(data, descriptionStart, end, encoding)
        if (textStart > end) return null
        // 描述符非空：非整曲注释，跳过
        if (textStart != descriptionStart + terminatorWidth(encoding)) return null
        return decodeText(data.copyOfRange(textStart, end), encoding, utf16LittleEndian(data, textStart, end, encoding))
            .trimEnd('\u0000')
    }

    // POPM：邮箱、评分、计数；0 分返回 null
    fun readRating(bytes: ByteArray, at: Int): String? {
        var result: String? = null
        forEachFrame(bytes, at) { source, _, id, bodyStart, length ->
            if (id == RATING_FRAME_ID && length >= 1) {
                val end = (bodyStart + length).coerceAtMost(source.size)
                var p = bodyStart
                while (p < end && source[p] != 0.toByte()) p++
                p++
                if (p < end) {
                    val raw = source[p].toInt() and 0xFF
                    if (raw > 0) {
                        // 0..255 线性刻度反算星级
                        val stars = (raw * RATING_STARS + RATING_MAX / 2) / RATING_MAX
                        result = stars.coerceIn(1, RATING_STARS).toString()
                        return@forEachFrame true
                    }
                }
            }
            false
        }
        return result
    }

    // 取指定描述符的 TXXX，描述符不区分大小写
    fun readTxxx(bytes: ByteArray, at: Int, descriptor: String): String? {
        var result: String? = null
        forEachFrame(bytes, at) { source, _, id, bodyStart, length ->
            if (id == TXXX_FRAME_ID) {
                val description = decodeTxxxDescription(source, bodyStart, length)
                if (description != null && description.equals(descriptor, ignoreCase = true)) {
                    result = decodeTxxxValue(source, bodyStart, length)
                    return@forEachFrame true
                }
            }
            false
        }
        return result
    }

    private fun decodeTxxxDescription(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 1 || offset + length > data.size) return null
        val encoding = data[offset].toInt() and 0xFF
        val end = offset + length
        val textStart = skipTerminated(data, offset + 1, end, encoding)
        val descriptionEnd = textStart - terminatorWidth(encoding)
        if (descriptionEnd < offset + 1) return null
        return decodeText(data.copyOfRange(offset + 1, descriptionEnd), encoding, null)
    }

    private fun decodeTxxxValue(data: ByteArray, offset: Int, length: Int): String? {
        if (length < 1 || offset + length > data.size) return null
        val encoding = data[offset].toInt() and 0xFF
        val end = offset + length
        val textStart = skipTerminated(data, offset + 1, end, encoding)
        if (textStart > end) return null
        return decodeText(data.copyOfRange(textStart, end), encoding, null).trimEnd('\u0000')
    }

    // 编码：Latin1、UTF-16、BE、UTF-8
    private fun decodeText(bytes: ByteArray, encoding: Int, littleEndian: Boolean?): String = when (encoding) {
        0 -> String(bytes, StandardCharsets.ISO_8859_1)
        1 -> decodeUtf16(bytes, littleEndian)
        2 -> String(bytes, StandardCharsets.UTF_16BE)
        else -> String(bytes, StandardCharsets.UTF_8)
    }

    // UTF-16 无 BOM 时按零字节分布推断字节序
    private fun decodeUtf16(bytes: ByteArray, littleEndian: Boolean?): String {
        val little = littleEndian ?: inferUtf16Endianness(bytes)
        val text = if (little) String(bytes, StandardCharsets.UTF_16LE) else String(bytes, StandardCharsets.UTF_16BE)
        return text.removePrefix("\uFEFF")
    }

    // 推断字节序：ASCII 高位字节恒为零
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

    // BOM 决定 UTF-16 字节序
    private fun utf16LittleEndian(data: ByteArray, start: Int, end: Int, encoding: Int): Boolean? {
        if (encoding != 1 || start + 2 > end) return null
        return when {
            data[start] == 0xff.toByte() && data[start + 1] == 0xfe.toByte() -> true
            data[start] == 0xfe.toByte() && data[start + 1] == 0xff.toByte() -> false
            else -> null
        }
    }

    // 跳过以 0 结尾的字段，返回其后位置
    private fun skipTerminated(data: ByteArray, start: Int, end: Int, encoding: Int): Int {
        val width = terminatorWidth(encoding)
        var p = start
        while (p + width <= end) {
            if (data[p] == 0.toByte() && (width == 1 || data[p + 1] == 0.toByte())) return p + width
            p++
        }
        return end
    }

    private fun terminatorWidth(encoding: Int): Int = if (encoding == 1 || encoding == 2) 2 else 1

    private fun dateFrameId(version: Int): String = if (version >= 4) DATE_FRAME_V4 else DATE_FRAME_V3

    fun textFrame(out: ByteArrayOutputStream, id: String, value: String, version: Int) {
        out.write(textFrameBytes(id, value, version))
    }

    private fun textFrameBytes(id: String, value: String, version: Int): ByteArray {
        val data = if (version >= 4) {
            byteArrayOf(3) + value.toByteArray(StandardCharsets.UTF_8) + 0
        } else {
            byteArrayOf(1) + byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
                value.toByteArray(StandardCharsets.UTF_16LE) + byteArrayOf(0, 0)
        }
        return frameBytes(id, data, version)
    }

    // USLT：编码 + 语言(3) + 空描述 + 歌词
    fun usltFrame(out: ByteArrayOutputStream, lyrics: String, version: Int) {
        out.write(usltFrameBytes(lyrics, version))
    }

    private fun usltFrameBytes(lyrics: String, version: Int): ByteArray {
        val text = if (version >= 4) {
            byteArrayOf(3) + UNDEFINED_LANGUAGE + byteArrayOf(0) + lyrics.toByteArray(StandardCharsets.UTF_8)
        } else {
            byteArrayOf(1) + UNDEFINED_LANGUAGE + byteArrayOf(0, 0) +
                byteArrayOf(0xff.toByte(), 0xfe.toByte()) + lyrics.toByteArray(StandardCharsets.UTF_16LE)
        }
        return frameBytes(LYRICS_FRAME_ID, text, version)
    }

    // COMM：编码 + 语言(3) + 空描述 + 注释
    private fun commentFrameBytes(comment: String, version: Int): ByteArray {
        val text = if (version >= 4) {
            byteArrayOf(3) + UNDEFINED_LANGUAGE + byteArrayOf(0) + comment.toByteArray(StandardCharsets.UTF_8)
        } else {
            byteArrayOf(1) + UNDEFINED_LANGUAGE + byteArrayOf(0, 0) +
                byteArrayOf(0xff.toByte(), 0xfe.toByte()) + comment.toByteArray(StandardCharsets.UTF_16LE)
        }
        return frameBytes(COMMENT_FRAME_ID, text, version)
    }

    // POPM：空邮箱 + 评分(1) + 计数(4)
    private fun popmFrameBytes(rating: String): ByteArray {
        val stars = rating.trim().toIntOrNull()?.coerceIn(0, RATING_STARS) ?: 0
        val raw = if (stars <= 0) 0 else (stars * RATING_MAX + RATING_STARS / 2) / RATING_STARS
        val data = ByteArrayOutputStream()
        data.write(0)
        data.write(raw)
        data.write(ByteArray(4))
        return frameBytes(RATING_FRAME_ID, data.toByteArray(), 4)
    }

    // TXXX：编码 + 描述(空结尾) + 值
    private fun txxxFrameBytes(descriptor: String, value: String, version: Int): ByteArray {
        val data = if (version >= 4) {
            byteArrayOf(3) + descriptor.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0) +
                value.toByteArray(StandardCharsets.UTF_8)
        } else {
            byteArrayOf(1) + byteArrayOf(0xff.toByte(), 0xfe.toByte()) +
                descriptor.toByteArray(StandardCharsets.UTF_16LE) + byteArrayOf(0, 0) +
                byteArrayOf(0xff.toByte(), 0xfe.toByte()) + value.toByteArray(StandardCharsets.UTF_16LE)
        }
        return frameBytes(TXXX_FRAME_ID, data, version)
    }

    fun apicFrame(out: ByteArrayOutputStream, cover: ByteArray, version: Int) {
        out.write(apicFrameBytes(cover, version))
    }

    private fun apicFrameBytes(cover: ByteArray, version: Int): ByteArray {
        val mime = MusicMetadataWriter.sniffMimeType(cover).toByteArray(StandardCharsets.ISO_8859_1)
        val data = if (version >= 4) {
            byteArrayOf(3) + mime + byteArrayOf(0, 3, 0) + cover
        } else {
            byteArrayOf(1) + mime + byteArrayOf(0) + byteArrayOf(3) + byteArrayOf(0, 0) + cover
        }
        return frameBytes(COVER_FRAME_ID, data, version)
    }

    fun frame(out: ByteArrayOutputStream, id: String, data: ByteArray, version: Int) {
        out.write(frameBytes(id, data, version))
    }

    // 单帧：标识(4) + 长度 + 标志(2) + 数据
    private fun frameBytes(id: String, data: ByteArray, version: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(StandardCharsets.US_ASCII))
        out.write(if (version >= 4) syncsafeBytes(data.size) else beIntBytes(data.size))
        out.write(byteArrayOf(0, 0))
        out.write(data)
        return out.toByteArray()
    }

    // 28 位同步安全整数，每字节仅低 7 位
    fun syncsafe(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0x7f shl 21) or (bytes[p + 1].toInt() and 0x7f shl 14) or
            (bytes[p + 2].toInt() and 0x7f shl 7) or (bytes[p + 3].toInt() and 0x7f)

    fun syncsafeBytes(v: Int): ByteArray =
        byteArrayOf((v shr 21 and 0x7f).toByte(), (v shr 14 and 0x7f).toByte(), (v shr 7 and 0x7f).toByte(), (v and 0x7f).toByte())

    // 遍历帧；block 返回 true 即停
    private inline fun forEachFrame(
        bytes: ByteArray,
        at: Int,
        block: (source: ByteArray, frameStart: Int, id: String, bodyStart: Int, length: Int) -> Boolean,
    ) {
        val normalized = normalizeUnsynchronisedTag(bytes, at)
        val source = normalized ?: bytes
        val base = if (normalized != null) 0 else at
        if (base + 10 > source.size || !source.startsWithAscii("ID3", base)) return
        val version = source[base + 3].toInt() and 0xFF
        if (version !in 3..4) return
        // footer 不计入声明长度，故声明长度即帧区上界
        val tagEnd = (base + 10 + syncsafe(source, base + 6)).coerceAtMost(source.size)
        val flags = source[base + 5].toInt() and 0xFF
        var p = base + 10
        if (flags and 0x40 != 0 && p + 4 <= tagEnd) {
            // v2.3 扩展头长度不含自身 4 字节，v2.4 已含
            val extSize = if (version >= 4) syncsafe(source, p) else beInt(source, p)
            p += if (version >= 4) extSize else 4 + extSize
            if (p > tagEnd) return
        }
        while (p + 10 <= tagEnd) {
            val id = String(source, p, 4, StandardCharsets.ISO_8859_1)
            if (!isFrameId(id)) break
            val length = if (version >= 4) syncsafe(source, p + 4) else beInt(source, p + 4)
            if (length < 0 || p + 10 + length > tagEnd) break
            if (block(source, p, id, p + 10, length)) return
            p += 10 + length
        }
    }

    /** v2.3 全局去同步逆运算；无需还原返回 null */
    private fun normalizeUnsynchronisedTag(bytes: ByteArray, at: Int): ByteArray? {
        if (at + 10 > bytes.size || !bytes.startsWithAscii("ID3", at)) return null
        if ((bytes[at + 3].toInt() and 0xFF) != 3) return null
        if (bytes[at + 5].toInt() and 0x80 == 0) return null
        val tagEnd = (at + 10 + syncsafe(bytes, at + 6)).coerceAtMost(bytes.size)
        val decoded = ByteArrayOutputStream(tagEnd - at - 10)
        var index = at + 10
        while (index < tagEnd) {
            val value = bytes[index]
            if (value == 0.toByte() && index > at + 10 && bytes[index - 1] == 0xFF.toByte()) {
                index++
                continue
            }
            decoded.write(value.toInt())
            index++
        }
        val frames = decoded.toByteArray()
        val header = bytes.copyOfRange(at, at + 10)
        header[5] = (header[5].toInt() and 0x7F).toByte()
        syncsafeBytes(frames.size).copyInto(header, 6)
        return header + frames
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
