package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

// WAV 标签：ID3 与 LIST/INFO，只读块头
internal object LosslessContainerTags {

    // 头/躯干区间/尾；偏移用 Long
    class TagRewrite(val head: ByteArray, val bodyStart: Long, val bodyEnd: Long, val tail: ByteArray)

    // 容器识别

    fun matches(bytes: ByteArray): Boolean = isWav(bytes)

    fun isWav(bytes: ByteArray): Boolean =
        bytes.startsWithAscii("RIFF", 0) && bytes.startsWithAscii("WAVE", 8)

    // 标签在音频之后、需尾窗的容器
    fun usesTrailingTag(header: ByteArray): Boolean = matches(header)

    // 写

    // 兼容入口：散字段快捷形式
    fun write(
        source: ByteArray,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): TagRewrite? = write(source, TrackTagFields.build {
        put(TagField.TITLE, title)
        put(TagField.ARTIST, artist)
        put(TagField.ALBUM, album)
        put(TagField.LYRICS, lyrics)
    }, cover, removeCover = false)

    // 区间源上的字段快捷入口
    fun write(
        source: TagSource,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): TagRewrite? = write(source, TrackTagFields.build {
        put(TagField.TITLE, title)
        put(TagField.ARTIST, artist)
        put(TagField.ALBUM, album)
        put(TagField.LYRICS, lyrics)
    }, cover, removeCover = false)

    // 与字节入口共用定位逻辑
    fun write(
        source: ByteArray,
        fields: TrackTagFields,
        cover: ByteArray? = null,
        removeCover: Boolean = false,
    ): TagRewrite? = write(ByteArrayTagSource(source), fields, cover, removeCover)

    /** 区间入口：块表定位，音频体只给区间；长度未知返回 null */
    fun write(
        source: TagSource,
        fields: TrackTagFields,
        cover: ByteArray? = null,
        removeCover: Boolean = false,
    ): TagRewrite? {
        if (source.size == TagSource.UNKNOWN_SIZE) return null
        val magic = source.readAt(0, FORM_MAGIC_BYTES) ?: return null
        return when {
            isWav(magic) -> writeWav(source, fields, cover, removeCover)
            else -> null
        }
    }

    // 重建块序：ID3 在 data 后，INFO 在前
    private fun writeWav(
        source: TagSource,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean,
    ): TagRewrite? {
        val fileSize = source.size
        val magic = source.readAt(0, RIFF_HEADER_BYTES) ?: return null
        if (!isWav(magic)) return null
        // 容器外尾部标签起点即重建上限
        val limit = wavTrailingTagStart(source, fileSize)
        val chunks = readWavChunks(source, limit) ?: return null
        val dataIndex = chunks.indexOfFirst { it.id == WAV_DATA_CHUNK_ID }
        if (dataIndex < 0) return null
        val data = chunks[dataIndex]
        // 尾部标签较新，优先作底本
        val trailing = if (limit < fileSize) readChunkBody(source, limit, fileSize - limit) ?: return null else null
        val embedded = chunks.firstOrNull { it.id in WAV_TAG_CHUNK_IDS }
            ?.let { readChunkBody(source, it.payloadStart, it.size.toLong()) ?: return null }
        val existing = trailing ?: embedded
        // INFO 项统一重写到 data 之前
        val infoChunk = chunks.firstOrNull { it.isInfoChunk(source) }
        val info = infoChunk?.let { readChunkBody(source, it.payloadStart, it.size.toLong()) ?: return null }
        val version = Id3v2Tag.versionOf(existing)
        // 无标签且无字段则不落标签块；删除封面需空块
        val frames = Id3v2Tag.replaceFrames(existing, fields, cover, removeCover, version)
            ?: if (removeCover) ByteArray(0) else null
        // 先把块体读完，任一失败即整体放弃
        val keptBefore = chunks.take(dataIndex).filter { it.id !in WAV_TAG_CHUNK_IDS && it !== infoChunk }
        val bodiesBefore = keptBefore.map { readChunkBody(source, it.payloadStart, it.size.toLong()) ?: return null }
        val keptAfter = chunks.drop(dataIndex + 1).filter { it.id !in WAV_TAG_CHUNK_IDS && it !== infoChunk }
        // data 后保留块驻留内存，超上限放弃
        val keptAfterBytes = keptAfter.sumOf { minOf(it.paddedEnd, fileSize) - it.headerStart }
        if (keptAfterBytes > TARGET_RESIDENT_LIMIT_BYTES) return null
        val head = ByteArrayOutputStream()
        head.write(RIFF_MAGIC.toByteArray(StandardCharsets.US_ASCII))
        head.write(ByteArray(4)) // RIFF 尺寸占位，最后回填
        head.write(WAVE_FORM_TYPE.toByteArray(StandardCharsets.US_ASCII))
        var infoWritten = false
        var bodyIndex = 0
        chunks.forEachIndexed { index, chunk ->
            // data 及其后的块不进头部
            if (index >= dataIndex) return@forEachIndexed
            // 标签统一重写到 data 之后，避免多份
            if (chunk.id in WAV_TAG_CHUNK_IDS) return@forEachIndexed
            if (chunk === infoChunk) {
                buildListInfo(info, fields)?.let {
                    writeRiffChunk(head, LIST_CHUNK_ID, it)
                    infoWritten = true
                }
                return@forEachIndexed
            }
            writeRiffChunk(head, chunk.id, bodiesBefore[bodyIndex++])
        }
        if (!infoWritten) {
            buildListInfo(info, fields)?.let { writeRiffChunk(head, LIST_CHUNK_ID, it) }
        }
        head.write(WAV_DATA_CHUNK_ID.toByteArray(StandardCharsets.US_ASCII))
        head.write(intBytesLE(data.size))
        val tail = ByteArrayOutputStream()
        // 块按偶数字节对齐
        if (data.size and 1 != 0) tail.write(0)
        // 其余块原序保留，按区间搬运
        keptAfter.forEach { chunk -> source.copyRange(chunk.headerStart, minOf(chunk.paddedEnd, fileSize), tail) }
        // 标签写入容器内 "ID3 " 块
        frames?.let { writeRiffChunk(tail, TAG_CHUNK_ID, Id3v2Tag.buildTag(it, version)) }
        val headBytes = head.toByteArray()
        // RIFF 尺寸自标识+长度字段之后起算
        val declaredSize = headBytes.size.toLong() + data.size + tail.size() - RIFF_SIZE_BASE_BYTES
        if (declaredSize > Int.MAX_VALUE) return null
        writeIntLE(headBytes, RIFF_SIZE_OFFSET, declaredSize.toInt())
        return TagRewrite(headBytes, data.payloadStart, data.payloadEnd, tail.toByteArray())
    }

    // 读 WAV 块表；结构不成立返回 null
    private fun readWavChunks(source: TagSource, limit: Long): List<WavChunk>? {
        val chunks = mutableListOf<WavChunk>()
        var p = RIFF_HEADER_BYTES.toLong()
        while (p + WAV_CHUNK_HEADER_BYTES <= limit) {
            val header = source.readAt(p, WAV_CHUNK_HEADER_BYTES)
                ?.takeIf { it.size == WAV_CHUNK_HEADER_BYTES } ?: return null
            val size = readU32LE(header, 4)
            if (size < 0 || p + WAV_CHUNK_HEADER_BYTES + size > limit) return null
            chunks += WavChunk(String(header, 0, 4, StandardCharsets.ISO_8859_1), p, size)
            p += WAV_CHUNK_HEADER_BYTES + size + (size and 1)
        }
        return chunks.takeIf { it.isNotEmpty() }
    }

    // 尾部标签起始偏移；无则返回文件末尾
    private fun wavTrailingTagStart(source: TagSource, fileSize: Long): Long {
        val windowSize = minOf(fileSize, TRAILING_WINDOW_BYTES.toLong()).toInt()
        val window = source.readAt(fileSize - windowSize, windowSize) ?: return fileSize
        val start = trailingFooterTagStart(window, fileSize - windowSize)
        if (start < 0) return fileSize
        if (!(source.readAt(start, 3) ?: return fileSize).startsWithAscii("ID3", 0)) return fileSize
        return start
    }

    // 定点读块体；超上限放弃
    private fun readChunkBody(source: TagSource, offset: Long, size: Long): ByteArray? {
        if (size < 0 || size > TAG_BODY_READ_LIMIT_BYTES) return null
        if (size == 0L) return ByteArray(0)
        val bytes = source.readAt(offset, size.toInt()) ?: return null
        return bytes.takeIf { it.size.toLong() == size }
    }

    // INFO 标识有限，缺失字段由 ID3 块承载
    private val INFO_IDS: Map<TagField, String> = mapOf(
        TagField.TITLE to INFO_TITLE_ID,
        TagField.ARTIST to INFO_ARTIST_ID,
        TagField.ALBUM to INFO_ALBUM_ID,
        TagField.DATE to INFO_DATE_ID,
        TagField.GENRE to INFO_GENRE_ID,
        TagField.COMMENT to INFO_COMMENT_ID,
        TagField.COPYRIGHT to INFO_COPYRIGHT_ID,
        TagField.TRACK_NUMBER to INFO_TRACK_ID,
    )

    // 重建 LIST/INFO，覆盖有标识的字段
    private fun buildListInfo(existing: ByteArray?, fields: TrackTagFields): ByteArray? {
        val kept = existing?.takeIf { it.size >= 4 }?.let { parseInfoItems(it, 4) }.orEmpty()
        val replaced = mutableSetOf<String>()
        fields.entries().forEach { (field, _) -> INFO_IDS[field]?.let { replaced += it } }
        // 被覆盖项不进新块，其余原样保留
        val items = kept.filterNot { it.first.uppercase() in replaced }.toMutableList()
        fields.entries().forEach { (field, value) -> INFO_IDS[field]?.let { items += it to infoValue(value) } }
        if (items.isEmpty()) return null
        val out = ByteArrayOutputStream()
        out.write(INFO_FORM_TYPE.toByteArray(StandardCharsets.US_ASCII))
        items.forEach { (id, value) -> writeRiffChunk(out, id, value) }
        return out.toByteArray()
    }

    private fun infoValue(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8) + 0

    private fun writeRiffChunk(out: ByteArrayOutputStream, id: String, body: ByteArray) {
        out.write(id.toByteArray(StandardCharsets.US_ASCII))
        out.write(intBytesLE(body.size))
        out.write(body)
        if (body.size and 1 != 0) out.write(0)
    }

    private class WavChunk(val id: String, val headerStart: Long, val size: Int) {
        val payloadStart get() = headerStart + WAV_CHUNK_HEADER_BYTES
        val payloadEnd get() = payloadStart + size
        val paddedEnd get() = payloadEnd + (size and 1)

        fun isInfoChunk(source: TagSource): Boolean =
            id == LIST_CHUNK_ID && size >= 4 &&
                (source.readAt(payloadStart, INFO_FORM_TYPE.length)?.startsWithAscii(INFO_FORM_TYPE, 0) == true)
    }

    // 读

    // 取内嵌歌词；窗口外标签按绝对偏移读
    fun readLyrics(
        header: ByteArray,
        tail: ByteArray?,
        tailOffset: Long,
        readAt: ((Long, Int) -> ByteArray?)?,
    ): String? {
        for (tag in collectTags(header, tail, tailOffset, readAt)) {
            when (tag) {
                is EmbeddedTag.Id3 -> Id3v2Tag.readUslt(tag.bytes, 0)?.takeIf { it.isNotBlank() }?.let { return it }
                // LIST/INFO 不承载歌词
                is EmbeddedTag.Info -> Unit
            }
        }
        return null
    }

    fun readCover(
        header: ByteArray,
        tail: ByteArray?,
        tailOffset: Long,
        readAt: ((Long, Int) -> ByteArray?)?,
    ): ByteArray? {
        for (tag in collectTags(header, tail, tailOffset, readAt)) {
            when (tag) {
                is EmbeddedTag.Id3 -> Id3v2Tag.readApic(tag.bytes, 0)?.let { return it }
                // LIST/INFO 只承载文本
                is EmbeddedTag.Info -> Unit
            }
        }
        return null
    }

    // 取内嵌文本标签：ID3 优先，INFO 补充
    fun readText(
        header: ByteArray,
        tail: ByteArray?,
        tailOffset: Long,
        readAt: ((Long, Int) -> ByteArray?)?,
    ): TextTag? {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        for (tag in collectTags(header, tail, tailOffset, readAt)) {
            when (tag) {
                is EmbeddedTag.Id3 -> {
                    title = title ?: Id3v2Tag.readTextFrame(tag.bytes, 0, TITLE_FRAME_ID)
                    artist = artist ?: Id3v2Tag.readTextFrame(tag.bytes, 0, ARTIST_FRAME_ID)
                    album = album ?: Id3v2Tag.readTextFrame(tag.bytes, 0, ALBUM_FRAME_ID)
                }
                is EmbeddedTag.Info -> {
                    title = title ?: infoText(tag.items, INFO_TITLE_ID)
                    artist = artist ?: infoText(tag.items, INFO_ARTIST_ID)
                    album = album ?: infoText(tag.items, INFO_ALBUM_ID)
                }
            }
        }
        if (title == null && artist == null && album == null) return null
        return TextTag(title, artist, album)
    }

    /** 取内嵌全量字段：各来源依次补齐 */
    fun readFields(
        header: ByteArray,
        tail: ByteArray?,
        tailOffset: Long,
        readAt: ((Long, Int) -> ByteArray?)?,
    ): TrackTagFields {
        val result = LinkedHashMap<TagField, String>()
        fun collect(field: TagField, value: String?) {
            if (value != null && field !in result) result[field] = value
        }
        for (tag in collectTags(header, tail, tailOffset, readAt)) {
            when (tag) {
                is EmbeddedTag.Id3 -> Id3v2Tag.readFields(tag.bytes, 0)
                    .entries()
                    .forEach { (field, value) -> collect(field, value) }
                is EmbeddedTag.Info -> INFO_IDS.forEach { (field, id) -> collect(field, infoText(tag.items, id)) }
            }
        }
        return TrackTagFields.build { result.forEach { (field, value) -> put(field, value) } }
    }

    // INFO 值以单字节 0 结尾，解码后去掉
    private fun infoText(items: List<Pair<String, ByteArray>>, id: String): String? =
        items.firstOrNull { it.first.uppercase() == id }
            ?.let { String(it.second, StandardCharsets.UTF_8).trimEnd('\u0000').takeIf { it.isNotBlank() } }

    // 收集文件内可能承载标签的区段
    private fun collectTags(
        header: ByteArray,
        tail: ByteArray?,
        tailOffset: Long,
        readAt: ((Long, Int) -> ByteArray?)?,
    ): List<EmbeddedTag> {
        val tags = mutableListOf<EmbeddedTag>()
        if (isWav(header)) collectWavTags(header, tail, tailOffset, readAt, tags)
        return tags
    }

    // WAV 标签两处：容器内块与文件尾 footer
    private fun collectWavTags(
        header: ByteArray,
        tail: ByteArray?,
        tailOffset: Long,
        readAt: ((Long, Int) -> ByteArray?)?,
        into: MutableList<EmbeddedTag>,
    ) {
        if (tail != null) {
            val start = trailingFooterTagStart(tail, tailOffset)
            if (start >= 0) sliceAt(start, header, tail, tailOffset, readAt)?.let { into += EmbeddedTag.Id3(it) }
        }
        findWavTagChunk(header, tail, tailOffset, readAt)
            ?.let { readRange(it, header, tail, tailOffset, readAt) }
            ?.let { into += EmbeddedTag.Id3(it) }
        findWavInfoChunk(header, tail, tailOffset, readAt)
            ?.let { readRange(it, header, tail, tailOffset, readAt) }
            ?.let { into += EmbeddedTag.Info(parseInfoItems(it, 0)) }
    }

    // 读绝对区间：窗口内截取，越界定点读
    private fun readRange(
        range: IntRange,
        header: ByteArray,
        tail: ByteArray?,
        tailOffset: Long,
        readAt: ((Long, Int) -> ByteArray?)?,
    ): ByteArray? {
        if (range.last <= header.size) return header.copyOfRange(range.first, range.last)
        if (tail != null) {
            val localStart = range.first - tailOffset
            val localEnd = range.last - tailOffset
            if (localStart >= 0 && localEnd <= tail.size) {
                return tail.copyOfRange(localStart.toInt(), localEnd.toInt())
            }
        }
        // 块体越出窗口时定点读取
        return readAt?.invoke(range.first.toLong(), range.last - range.first)
    }

    // 按块表定位 "ID3 " 块，返回载荷区间
    private fun findWavTagChunk(
        header: ByteArray,
        tail: ByteArray?,
        tailOffset: Long,
        readAt: ((Long, Int) -> ByteArray?)?,
    ): IntRange? {
        val fileEnd = if (tail != null) tailOffset + tail.size else header.size.toLong()
        var p = RIFF_HEADER_BYTES.toLong()
        while (p + WAV_CHUNK_HEADER_BYTES <= fileEnd) {
            val chunk = windowAt(p, WAV_CHUNK_HEADER_BYTES, header, tail, tailOffset, readAt) ?: return null
            val size = readU32LE(chunk, 4)
            if (size < 0 || p + WAV_CHUNK_HEADER_BYTES + size > fileEnd) return null
            if (String(chunk, 0, 4, StandardCharsets.ISO_8859_1) in WAV_TAG_CHUNK_IDS) {
                val bodyStart = p + WAV_CHUNK_HEADER_BYTES
                return bodyStart.toInt()..(bodyStart + size).toInt()
            }
            p += WAV_CHUNK_HEADER_BYTES + size + (size and 1)
        }
        return null
    }

    // 按块表定位 LIST/INFO，返回项区区间
    private fun findWavInfoChunk(
        header: ByteArray,
        tail: ByteArray?,
        tailOffset: Long,
        readAt: ((Long, Int) -> ByteArray?)?,
    ): IntRange? {
        val fileEnd = if (tail != null) tailOffset + tail.size else header.size.toLong()
        var p = RIFF_HEADER_BYTES.toLong()
        while (p + WAV_CHUNK_HEADER_BYTES <= fileEnd) {
            val chunk = windowAt(p, WAV_CHUNK_HEADER_BYTES, header, tail, tailOffset, readAt) ?: return null
            val size = readU32LE(chunk, 4)
            if (size < 0 || p + WAV_CHUNK_HEADER_BYTES + size > fileEnd) return null
            if (String(chunk, 0, 4, StandardCharsets.ISO_8859_1) == LIST_CHUNK_ID && size >= 4) {
                val form = windowAt(p + WAV_CHUNK_HEADER_BYTES, 4, header, tail, tailOffset, readAt) ?: return null
                if (String(form, 0, 4, StandardCharsets.ISO_8859_1) == INFO_FORM_TYPE) {
                    val start = p + WAV_CHUNK_HEADER_BYTES + 4
                    return start.toInt()..(p + WAV_CHUNK_HEADER_BYTES + size).toInt()
                }
            }
            p += WAV_CHUNK_HEADER_BYTES + size + (size and 1)
        }
        return null
    }

    // 解析 INFO 项区：标识+长度+值，偶数对齐
    private fun parseInfoItems(bytes: ByteArray, from: Int): List<Pair<String, ByteArray>> {
        val items = mutableListOf<Pair<String, ByteArray>>()
        var p = from
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, StandardCharsets.ISO_8859_1)
            val size = readU32LE(bytes, p + 4)
            if (size < 0 || p + 8 + size > bytes.size) break
            items += id to bytes.copyOfRange(p + 8, p + 8 + size)
            p += 8 + size + (size and 1)
        }
        return items
    }

    // 取区间字节：优先截取已有窗口
    private fun windowAt(
        offset: Long,
        count: Int,
        header: ByteArray,
        tail: ByteArray?,
        tailOffset: Long,
        readAt: ((Long, Int) -> ByteArray?)?,
    ): ByteArray? {
        if (offset < 0) return null
        if (offset + count <= header.size) return header.copyOfRange(offset.toInt(), offset.toInt() + count)
        if (tail != null && offset >= tailOffset && offset + count <= tailOffset + tail.size) {
            val local = (offset - tailOffset).toInt()
            return tail.copyOfRange(local, local + count)
        }
        return readAt?.invoke(offset, count)?.takeIf { it.size >= count }
    }

    // 取 offset 起标签区，过短返回 null
    private fun sliceAt(
        offset: Long,
        header: ByteArray,
        tail: ByteArray?,
        tailOffset: Long,
        readAt: ((Long, Int) -> ByteArray?)?,
    ): ByteArray? {
        val window = windowFrom(offset, header, tail, tailOffset) ?: return readAt?.invoke(offset, TAG_READ_BYTES)
        return window.takeIf { it.size >= ID3_HEADER_BYTES }
    }

    // 取自 offset 起至窗口末尾
    private fun windowFrom(offset: Long, header: ByteArray, tail: ByteArray?, tailOffset: Long): ByteArray? {
        if (offset < 0) return null
        if (offset < header.size) return header.copyOfRange(offset.toInt(), header.size)
        if (tail != null && offset >= tailOffset && offset < tailOffset + tail.size) {
            return tail.copyOfRange((offset - tailOffset).toInt(), tail.size)
        }
        return null
    }

    // 末 10 字节为 ID3v2 footer，得起点
    private fun trailingFooterTagStart(tail: ByteArray, tailOffset: Long): Long {
        if (tail.size < 2 * ID3_HEADER_BYTES || !tail.startsWithAscii("3DI", tail.size - 10)) return -1L
        val size = Id3v2Tag.syncsafe(tail, tail.size - 4)
        return tailOffset + tail.size - 10 - ID3_HEADER_BYTES - size
    }

    // 文本标签：标题/艺术家/专辑
    class TextTag(val title: String?, val artist: String?, val album: String?)

    // 标签形态：内嵌 ID3v2 与 LIST/INFO
    private sealed interface EmbeddedTag {
        class Id3(val bytes: ByteArray) : EmbeddedTag
        class Info(val items: List<Pair<String, ByteArray>>) : EmbeddedTag
    }

    // 字节工具

    private fun ByteArray.startsWithAscii(value: String, at: Int): Boolean {
        if (at < 0 || at + value.length > size) return false
        for (i in value.indices) {
            if (this[at + i].toInt() and 0xFF != value[i].code) return false
        }
        return true
    }

    private fun readU32LE(bytes: ByteArray, at: Int): Int =
        if (at + 4 > bytes.size) -1 else
            (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() and 0xFF shl 8) or
                (bytes[at + 2].toInt() and 0xFF shl 16) or (bytes[at + 3].toInt() and 0xFF shl 24)

    private fun writeIntLE(bytes: ByteArray, at: Int, value: Int) {
        if (at + 4 > bytes.size) return
        bytes[at] = value.toByte()
        bytes[at + 1] = (value shr 8).toByte()
        bytes[at + 2] = (value shr 16).toByte()
        bytes[at + 3] = (value shr 24).toByte()
    }

    private fun intBytesLE(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    // WAV 的 ID3 块标识，读取兼顾小写
    private const val TAG_CHUNK_ID = "ID3 "
    private val WAV_TAG_CHUNK_IDS = setOf("ID3 ", "id3 ")

    // 容器标识窗口：RIFF 头 12 字节
    private const val FORM_MAGIC_BYTES = 16

    // 尾部标签定位窗口
    private const val TRAILING_WINDOW_BYTES = 64

    // 块体读取上限，挡住异常容器
    private const val TAG_BODY_READ_LIMIT_BYTES = 32L * 1024 * 1024
    // data 后保留块驻留上限
    private const val TARGET_RESIDENT_LIMIT_BYTES = 32L * 1024 * 1024

    // WAV 块结构：容器头 12，块头 8
    private const val RIFF_MAGIC = "RIFF"
    private const val WAVE_FORM_TYPE = "WAVE"
    private const val RIFF_HEADER_BYTES = 12
    private const val RIFF_SIZE_OFFSET = 4
    // RIFF 尺寸自标识+长度字段之后起算
    private const val RIFF_SIZE_BASE_BYTES = 8
    private const val WAV_CHUNK_HEADER_BYTES = 8
    private const val WAV_DATA_CHUNK_ID = "data"
    private const val LIST_CHUNK_ID = "LIST"
    private const val INFO_FORM_TYPE = "INFO"
    // INFO 项标识
    private const val INFO_TITLE_ID = "INAM"
    private const val INFO_ARTIST_ID = "IART"
    private const val INFO_ALBUM_ID = "IPRD"
    private const val INFO_DATE_ID = "ICRD"
    private const val INFO_GENRE_ID = "IGNR"
    private const val INFO_COMMENT_ID = "ICMT"
    private const val INFO_COPYRIGHT_ID = "ICOP"
    private const val INFO_TRACK_ID = "IPRT"
    // ID3 文本帧
    private const val TITLE_FRAME_ID = "TIT2"
    private const val ARTIST_FRAME_ID = "TPE1"
    private const val ALBUM_FRAME_ID = "TALB"

    private const val ID3_HEADER_BYTES = 10
    private const val TAG_READ_BYTES = 4 * 1024 * 1024
}
