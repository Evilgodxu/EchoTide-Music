package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

// ID3v2 标签的帧与标签构造：MP3、WAV 内嵌的 ID3 块共用同一套帧编码。
// 新建标签统一取 v2.4（文本 UTF-8、内容描述符以单 0 结尾、长度字段为同步安全整数），
// 读取既有标签时按标签自身的版本解析，避免把 v2.3 的帧当 v2.4 读。
// 写入端与内嵌歌词读取器的编码分支一一对应：v2.4 用 UTF-8，v2.3 用带 BOM 的 UTF-16
internal object Id3v2Tag {

    // 新建标签版本
    const val TAG_VERSION = 4

    // 普通文本帧：一个字段对应一个固定帧标识，读写共用同一张表
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

    // 日期帧：v2.4 用 TDRC（完整时间戳），v2.3 用 TYER（四位年份）。
    // 两者不通用，按标签版本择一写出，读取时两者都认，兼容第三方工具写入的任一形式
    private const val DATE_FRAME_V4 = "TDRC"
    private const val DATE_FRAME_V3 = "TYER"

    // 自定义文本帧（TXXX）承载的字段：ReplayGain 没有专用帧标识，
    // 按业界惯例写入 TXXX，以描述符区分具体项
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

    // USLT 与 COMM 帧的语言字段：ID3v2 规范用 "XXX" 表示语言未定义
    private val UNDEFINED_LANGUAGE = "XXX".toByteArray(StandardCharsets.ISO_8859_1)

    // POPM 评分的满分刻度：星级 1..5 线性映射到 1..255，读回时按同一刻度反算
    private const val RATING_MAX = 255
    private const val RATING_STARS = 5

    // 既有标签的版本；非 ID3v2.3/2.4 标签或标签缺失时取新建版本。
    // v2.3 的帧长度字段为 32 位大端、文本为 UTF-16，与 v2.4 的同步安全长度、UTF-8 互不兼容，
    // 容器改写保留帧时必须以原版本重建，否则保留下来的帧会被按错版本读成乱码
    fun versionOf(tag: ByteArray?): Int =
        if (tag != null && tag.size >= 10 && tag.startsWithAscii("ID3", 0)) {
            (tag[3].toInt() and 0xFF).takeIf { it in 3..4 } ?: TAG_VERSION
        } else TAG_VERSION

    /**
     * 兼容入口：以既有标签的帧区为底，覆盖标题/艺术家/专辑/封面/歌词，保留其余帧。
     * 字段为 null 表示保留原值；无既有标签且无字段可写时返回 null（无内容可写）
     */
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

    /**
     * 以既有标签的帧区为底，覆盖 [fields] 声明的字段与封面，保留其余帧。
     *
     * 同一帧标识的重复帧只在首次出现处替换，其后同名帧原样保留 —— 保留既有帧的语义优先于去重，
     * 这既避免改写丢失第三方工具写入的附加信息，也让「重复帧是否该合并」的判断留给上层。
     *
     * @param cover 新的封面图；为 null 且 [removeCover] 为真时删除既有封面
     * @param removeCover 本次要删除内嵌封面。与「cover 为 null」区分：后者只是没有新图，封面须原样保留
     * @return 帧区字节（不含标签头与 footer）；无既有标签且无字段可写时返回 null
     */
    fun replaceFrames(
        existing: ByteArray?,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean,
        version: Int,
    ): ByteArray? {
        // 待写入帧按帧标识归并：一个标识一个结果，首次出现处替换
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
        // 封面状态：读取端也据此决定既有 APIC 帧是替换还是原样保留
        val newCoverFrame = cover?.let { apicFrameBytes(it, version) }
        val coverChanged = newCoverFrame != null || removeCover

        val frames = ByteArrayOutputStream()
        // 已被新值顶替的帧标识：同名帧只在首次出现处写新值，其后原样保留
        val replaced = mutableSetOf<String>()
        if (existing != null) {
            forEachFrame(existing, 0) { frameStart, id, bodyStart, length ->
                val raw = existing.copyOfRange(frameStart, bodyStart + length)
                when {
                    // 封面独立处理：删除时首个 APIC 帧整帧丢弃（不写新帧即等于删掉），其余重复帧按同一状态处理
                    id == COVER_FRAME_ID -> {
                        if (!coverChanged) {
                            frames.write(raw)
                        } else if (replaced.add(COVER_FRAME_ID) && newCoverFrame != null) {
                            frames.write(newCoverFrame)
                        }
                    }
                    id == TXXX_FRAME_ID -> {
                        // TXXX 以描述符区分具体项：只有描述符命中待写入项时才替换
                        val descriptor = decodeTxxxDescription(existing, bodyStart, length)
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

    /**
     * 读取标签内的全量字段；未写入的字段不出现在结果中（与「写入了空串」区分）。
     * 日期同时接受 v2.4 的 TDRC 与 v2.3 的 TYER
     */
    fun readFields(bytes: ByteArray, at: Int): TrackTagFields = TrackTagFields.build {
        TEXT_FRAME_IDS.forEach { (field, frameId) -> put(field, readTextFrame(bytes, at, frameId)) }
        put(TagField.DATE, readTextFrame(bytes, at, DATE_FRAME_V4) ?: readTextFrame(bytes, at, DATE_FRAME_V3))
        put(TagField.COMMENT, readComment(bytes, at))
        put(TagField.RATING, readRating(bytes, at))
        TXXX_DESCRIPTORS.forEach { (field, descriptor) -> put(field, readTxxx(bytes, at, descriptor)) }
        put(TagField.LYRICS, readUslt(bytes, at)?.takeIf { it.isNotBlank() })
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
            if (id == COVER_FRAME_ID) {
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
        p = skipTerminated(bytes, p, end, encoding)
        if (p > end) return null
        return bytes.copyOfRange(p, end).takeIf { it.isNotEmpty() }
    }

    // 取标签内指定文本帧的文本，供标题(TIT2)/艺术家(TPE1)/专辑(TALB)等单帧字段读取
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
        return decodeText(data.copyOfRange(start, end), encoding, utf16LittleEndian(data, start, end, encoding))
            .trimEnd('\u0000')
            .takeIf { it.isNotBlank() }
    }

    // 取标签内首个 USLT（非同步歌词）帧的歌词文本
    fun readUslt(bytes: ByteArray, at: Int): String? {
        var result: String? = null
        forEachFrame(bytes, at) { _, id, bodyStart, length ->
            if (id == LYRICS_FRAME_ID) {
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
        val p = skipTerminated(data, offset + 4, end, encoding)
        if (p > end) return null
        return decodeText(data.copyOfRange(p, end), encoding, utf16LittleEndian(data, p, end, encoding))
    }

    // 取标签内指定 COMM（注释）帧的文本：只认描述符为空的「整曲注释」，
    // 带描述符的 COMM 属第三方工具的附加信息，不属于本字段
    fun readComment(bytes: ByteArray, at: Int): String? {
        var result: String? = null
        forEachFrame(bytes, at) { _, id, bodyStart, length ->
            if (id == COMMENT_FRAME_ID) {
                val decoded = decodeComment(bytes, bodyStart, length)
                if (decoded != null) {
                    result = decoded
                    return@forEachFrame true
                }
            }
            false
        }
        return result
    }

    // COMM 帧数据：编码字节 + 语言(3) + 空结尾描述符 + 文本
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

    // 取 POPM（评分）帧的星级：帧体为「邮箱(空结尾) + 评分(1) + 计数(可选)」。
    // 无 POPM 或评分为 0（未打分）时返回 null
    fun readRating(bytes: ByteArray, at: Int): String? {
        var result: String? = null
        forEachFrame(bytes, at) { _, id, bodyStart, length ->
            if (id == RATING_FRAME_ID && length >= 1) {
                val end = (bodyStart + length).coerceAtMost(bytes.size)
                var p = bodyStart
                while (p < end && bytes[p] != 0.toByte()) p++
                p++
                if (p < end) {
                    val raw = bytes[p].toInt() and 0xFF
                    if (raw > 0) {
                        // 反算星级：0..255 线性刻度回到 1..5
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

    // 取指定描述符的 TXXX（自定义文本）帧的值；描述符比较不区分大小写
    fun readTxxx(bytes: ByteArray, at: Int, descriptor: String): String? {
        var result: String? = null
        forEachFrame(bytes, at) { _, id, bodyStart, length ->
            if (id == TXXX_FRAME_ID) {
                val description = decodeTxxxDescription(bytes, bodyStart, length)
                if (description != null && description.equals(descriptor, ignoreCase = true)) {
                    result = decodeTxxxValue(bytes, bodyStart, length)
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

    // UTF-16 文本起始处的 BOM 决定字节序（ID3v2 规范要求 UTF-16 字符串以 BOM 开头）
    private fun utf16LittleEndian(data: ByteArray, start: Int, end: Int, encoding: Int): Boolean? {
        if (encoding != 1 || start + 2 > end) return null
        return when {
            data[start] == 0xff.toByte() && data[start + 1] == 0xfe.toByte() -> true
            data[start] == 0xfe.toByte() && data[start + 1] == 0xff.toByte() -> false
            else -> null
        }
    }

    // 跳过以 0（UTF-16 为双 0）结尾的字段，返回其后第一个字节的位置
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

    // USLT 帧数据：编码字节 + 语言(3) + 空内容描述符 + 歌词文本。描述符结尾宽度随编码变化
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

    // COMM 帧数据：编码字节 + 语言(3) + 空描述符 + 注释文本
    private fun commentFrameBytes(comment: String, version: Int): ByteArray {
        val text = if (version >= 4) {
            byteArrayOf(3) + UNDEFINED_LANGUAGE + byteArrayOf(0) + comment.toByteArray(StandardCharsets.UTF_8)
        } else {
            byteArrayOf(1) + UNDEFINED_LANGUAGE + byteArrayOf(0, 0) +
                byteArrayOf(0xff.toByte(), 0xfe.toByte()) + comment.toByteArray(StandardCharsets.UTF_16LE)
        }
        return frameBytes(COMMENT_FRAME_ID, text, version)
    }

    // POPM 帧数据：空邮箱 + 评分(1) + 计数(4)。星级 1..5 线性映射到 1..255
    private fun popmFrameBytes(rating: String): ByteArray {
        val stars = rating.trim().toIntOrNull()?.coerceIn(0, RATING_STARS) ?: 0
        val raw = if (stars <= 0) 0 else (stars * RATING_MAX + RATING_STARS / 2) / RATING_STARS
        val data = ByteArrayOutputStream()
        data.write(0)
        data.write(raw)
        data.write(ByteArray(4))
        return frameBytes(RATING_FRAME_ID, data.toByteArray(), 4)
    }

    // TXXX 帧数据：编码字节 + 描述符(空结尾) + 值
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

    // 单帧字节：标识(4) + 长度 + 标志(2) + 数据。v2.4 长度为同步安全整数，v2.3 为 32 位大端
    private fun frameBytes(id: String, data: ByteArray, version: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(StandardCharsets.US_ASCII))
        out.write(if (version >= 4) syncsafeBytes(data.size) else beIntBytes(data.size))
        out.write(byteArrayOf(0, 0))
        out.write(data)
        return out.toByteArray()
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
