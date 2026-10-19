package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

// WAV（RIFF）容器的标签读写：二进制标签写在容器内的 "ID3 " 块，
// 文本标签按 RIFF 惯例写在 LIST/INFO 块。
//
// 写路径只产出「头部字面字节 + 音频体区间 + 尾部字面字节」，音频体由调用方按区间流式复制。
// 块表只读块头、标签区与保留的块体定点读取，故重写的驻留量与文件大小无关：高解析 WAV 单文件
// 可达数百 MB，整文件驻留会把进程推到系统内存回收线以下。读路径按同一套定位规则从头部窗口
// 与尾部窗口取标签，窗口之外的标签再按绝对偏移定点读取。ID3 帧的编解码统一由 Id3v2Tag 负责
internal object LosslessContainerTags {

    // 标签重写结果：head 替换源文件 [0, bodyStart)，音频体按 [bodyStart, bodyEnd) 复制，tail 追加在末尾。
    // 区间偏移用 Long：高解析无损单文件可越过 2GB 的 Int 边界
    class TagRewrite(val head: ByteArray, val bodyStart: Long, val bodyEnd: Long, val tail: ByteArray)

    // ---- 容器识别 ----

    fun matches(bytes: ByteArray): Boolean = isWav(bytes)

    fun isWav(bytes: ByteArray): Boolean =
        bytes.startsWithAscii("RIFF", 0) && bytes.startsWithAscii("WAVE", 8)

    // 标签可能位于音频之后、需要读尾窗才能定位的容器：WAV 的 "ID3 " 块落在音频数据之后。
    // 其余容器（FLAC/M4A/Ogg/MP3）的标签都在头部，读尾窗只是平白多读数据
    fun usesTrailingTag(header: ByteArray): Boolean = matches(header)

    // ---- 写 ----

    // 兼容入口：以标题/艺术家/专辑/封面/歌词为字段集合的快捷形式
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

    // 兼容入口：区间源上的字段快捷形式
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

    // 判定与定位逻辑与区间入口共用
    fun write(
        source: ByteArray,
        fields: TrackTagFields,
        cover: ByteArray? = null,
        removeCover: Boolean = false,
    ): TagRewrite? = write(ByteArrayTagSource(source), fields, cover, removeCover)

    /**
     * 区间入口：块表按块头逐个定位，标签区与保留的块体定点读取，音频体只给出区间，交调用方搬运。
     *
     * 标签位置依赖文件末尾的容器（尾部标签、页脚）按 [TagSource.size] 判定，
     * 长度不可知时无从定位，返回 null 交由调用方跳过本次重写。
     *
     * @param cover 新的封面图；为 null 且 [removeCover] 为真时删除既有封面
     * @param removeCover 本次要删除内嵌封面，与「cover 为 null（不改封面）」区分
     */
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

    // WAV：重建 RIFF 块序列。二进制标签收敛为 data 之后的一个 "ID3 " 块——与 ffmpeg 等工具的
    // 布局一致，容器内只有一份标签，旧版本写在容器外文件末尾的标签随之并入该块；
    // 文本标签收敛为 data 之前的一个 LIST/INFO 块。块表只读块头，音频体按区间流式复制
    private fun writeWav(
        source: TagSource,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean,
    ): TagRewrite? {
        val fileSize = source.size
        val magic = source.readAt(0, RIFF_HEADER_BYTES) ?: return null
        if (!isWav(magic)) return null
        // 容器外尾部标签（旧版本写出的布局）位于 RIFF 之外，其起点即参与重建的区间上限
        val limit = wavTrailingTagStart(source, fileSize)
        val chunks = readWavChunks(source, limit) ?: return null
        val dataIndex = chunks.indexOfFirst { it.id == WAV_DATA_CHUNK_ID }
        if (dataIndex < 0) return null
        val data = chunks[dataIndex]
        // 保留帧的来源：容器外尾部标签是较新的一次写入，优先以它为底；
        // 两者并存时容器内块是更早写入后遗留的旧标签
        val trailing = if (limit < fileSize) readChunkBody(source, limit, fileSize - limit) ?: return null else null
        val embedded = chunks.firstOrNull { it.id in WAV_TAG_CHUNK_IDS }
            ?.let { readChunkBody(source, it.payloadStart, it.size.toLong()) ?: return null }
        val existing = trailing ?: embedded
        // INFO 块的既有项并入重写结果：其原位置可能在音频之后，重写后统一置于 data 之前
        val infoChunk = chunks.firstOrNull { it.isInfoChunk(source) }
        val info = infoChunk?.let { readChunkBody(source, it.payloadStart, it.size.toLong()) ?: return null }
        val version = Id3v2Tag.versionOf(existing)
        // 无既有标签且无字段可写时不落标签块：仍完成块序列重建，返回文件本身。
        // 删除封面是例外：标签块须落盘（哪怕帧区为空），否则封面块会被原样保留下来
        val frames = Id3v2Tag.replaceFrames(existing, fields, cover, removeCover, version)
            ?: if (removeCover) ByteArray(0) else null
        // data 之前块体的读取先于写出：任一读取失败即整体放弃，不留半截结果
        val keptBefore = chunks.take(dataIndex).filter { it.id !in WAV_TAG_CHUNK_IDS && it !== infoChunk }
        val bodiesBefore = keptBefore.map { readChunkBody(source, it.payloadStart, it.size.toLong()) ?: return null }
        val keptAfter = chunks.drop(dataIndex + 1).filter { it.id !in WAV_TAG_CHUNK_IDS && it !== infoChunk }
        val head = ByteArrayOutputStream()
        head.write(RIFF_MAGIC.toByteArray(StandardCharsets.US_ASCII))
        head.write(ByteArray(4)) // RIFF 尺寸占位，最后回填
        head.write(WAVE_FORM_TYPE.toByteArray(StandardCharsets.US_ASCII))
        var infoWritten = false
        var bodyIndex = 0
        chunks.forEachIndexed { index, chunk ->
            // data 及其后的块由音频体与尾部字面字节承载，不进入头部
            if (index >= dataIndex) return@forEachIndexed
            // 标签统一重写到 data 之后，避免容器内出现多份
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
        // 块按偶数字节对齐，data 的对齐字节归入尾部字面字节
        if (data.size and 1 != 0) tail.write(0)
        // 其余块保持原序原样保留：含块头与对齐字节，按区间搬运
        keptAfter.forEach { chunk -> source.copyRange(chunk.headerStart, minOf(chunk.paddedEnd, fileSize), tail) }
        // 标签以容器内的 "ID3 " 块承载，块头随块体一并写入
        frames?.let { writeRiffChunk(tail, TAG_CHUNK_ID, Id3v2Tag.buildTag(it, version)) }
        val headBytes = head.toByteArray()
        // RIFF 尺寸自「标识 + 长度字段」之后起算：头部 + 音频体 + 尾部块总长减 8
        val declaredSize = headBytes.size.toLong() + data.size + tail.size() - RIFF_SIZE_BASE_BYTES
        if (declaredSize > Int.MAX_VALUE) return null
        writeIntLE(headBytes, RIFF_SIZE_OFFSET, declaredSize.toInt())
        return TagRewrite(headBytes, data.payloadStart, data.payloadEnd, tail.toByteArray())
    }

    // 读取 WAV 块表（[12, limit) 内的块）：块表只读块头，块体按需定点读取；
    // 块结构不成立（长度为负或越界）时返回 null，不做截断猜测
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

    // 容器外尾部标签（旧版本布局）的起始偏移；未内嵌或标签头不成立时返回文件末尾。
    // 标签起点由文件末尾 10 字节的 ID3v2 footer 给出，故只需读末尾一个小窗口
    private fun wavTrailingTagStart(source: TagSource, fileSize: Long): Long {
        val windowSize = minOf(fileSize, TRAILING_WINDOW_BYTES.toLong()).toInt()
        val window = source.readAt(fileSize - windowSize, windowSize) ?: return fileSize
        val start = trailingFooterTagStart(window, fileSize - windowSize)
        if (start < 0) return fileSize
        if (!(source.readAt(start, 3) ?: return fileSize).startsWithAscii("ID3", 0)) return fileSize
        return start
    }

    // 定点读取块体或标签区：超过驻留上限即放弃，避免异常容器把音频体当标签读进堆
    private fun readChunkBody(source: TagSource, offset: Long, size: Long): ByteArray? {
        if (size < 0 || size > TAG_BODY_READ_LIMIT_BYTES) return null
        if (size == 0L) return ByteArray(0)
        val bytes = source.readAt(offset, size.toInt()) ?: return null
        return bytes.takeIf { it.size.toLong() == size }
    }

    // LIST/INFO 项标识：字段到 RIFF INFO 标识的映射。
    // INFO 的项标识是有限集合，没有对应标识的字段（专辑艺术家、碟号、作词、作曲、评分、ReplayGain、语言）
    // 由同步写入的 ID3 块承载，此处不强行造标识
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

    // 重建 LIST/INFO 内容：保留既有项原样，覆盖本字段集合中有对应项标识的项。
    // INFO 值按 RIFF 惯例以单字节 0 结尾，新建项据此补齐
    private fun buildListInfo(existing: ByteArray?, fields: TrackTagFields): ByteArray? {
        val kept = existing?.takeIf { it.size >= 4 }?.let { parseInfoItems(it, 4) }.orEmpty()
        val replaced = mutableSetOf<String>()
        fields.entries().forEach { (field, _) -> INFO_IDS[field]?.let { replaced += it } }
        // 被覆盖的键不进新块，其余项连同原标识原样保留
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

    // ---- 读 ----

    // 取内嵌歌词文本。header 为文件头窗口，tail 为文件尾窗口（可为 null），
    // tailOffset 为尾窗在文件中的绝对偏移；窗口之外的标签经 readAt(绝对偏移, 期望长度) 定点读取
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
                // LIST/INFO 只承载文本，封面不由它提供
                is EmbeddedTag.Info -> Unit
            }
        }
        return null
    }

    // 取内嵌文本标签（标题/艺术家/专辑）。ID3 帧是首选来源，
    // WAV 的 LIST/INFO 作为补充：只写 INFO 的第三方工具产出的文件没有 ID3 块，靠它取回字段。
    // 各来源按优先级依次补齐缺失字段，先取到的优先——同一容器内 ID3 与 INFO 由写入端同步维护，内容一致
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

    /**
     * 取内嵌的全量字段。各来源按优先级依次补齐缺失字段，先取到的优先 ——
     * 同一容器内 ID3 与 LIST/INFO 由写入端同步维护，内容一致。
     */
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

    // LIST/INFO 项值：按 RIFF 惯例以单字节 0 结尾，解码后一并去掉
    private fun infoText(items: List<Pair<String, ByteArray>>, id: String): String? =
        items.firstOrNull { it.first.uppercase() == id }
            ?.let { String(it.second, StandardCharsets.UTF_8).trimEnd('\u0000').takeIf { it.isNotBlank() } }

    // 按容器类型收集文件内可能承载标签的区段。
    // 只有 WAV 需要自解析；其余容器（FLAC/M4A/Ogg/MP3）的标签都在头部且有各自的读取端，此处不处理
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

    // WAV 的两种标签布局：容器内的 "ID3 " 块（普遍布局，紧跟 data 之后），
    // 以及旧版本写在容器外文件末尾的带 footer 标签。容器外标签是较新的一次写入，优先取用。
    // LIST/INFO 是文本标签的另一处落点：仅写 INFO 的工具产出的文件没有 ID3 块，靠它取回标题等字段
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

    // 读取绝对区间 [range) 的字节：落在头窗内就地截取，越出窗口时按块头给出的长度定点读取
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
        // 块体（可能含大封面）越出窗口：按块头给出的长度定点读取
        return readAt?.invoke(range.first.toLong(), range.last - range.first)
    }

    // 按 WAV 块表定位 "ID3 " 块，返回其载荷的绝对区间（终点不含）。
    // data 块通常远大于任何读取窗口，故只按块头推进：块头落在头窗或尾窗内就地取，
    // 落在窗口外按绝对偏移定点读取 8 字节；块结构不成立（长度为负或越界）即终止
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

    // 按 WAV 块表定位 LIST/INFO 块，返回其项区的绝对区间（终点不含）。
    // 定位方式与标签块一致：只按块头推进，块头落在头窗或尾窗内就地取，落在窗口外按绝对偏移定点读取
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

    // 解析 LIST/INFO 项区：项 = 标识(4) + 小端长度 + 值，块按偶数字节对齐；结构不成立即终止
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

    // 取文件 [offset, offset + count) 的字节：优先截取已有窗口，窗口未完整覆盖时定点读取
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

    // 取文件 [offset, offset + maxBytes) 的字节：优先截取已有窗口，落在窗口外时定点读取
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

    // 取文件中自 offset 起、落在已读窗口内的字节（直到窗口末尾）；窗口未覆盖该位置时返回 null
    private fun windowFrom(offset: Long, header: ByteArray, tail: ByteArray?, tailOffset: Long): ByteArray? {
        if (offset < 0) return null
        if (offset < header.size) return header.copyOfRange(offset.toInt(), header.size)
        if (tail != null && offset >= tailOffset && offset < tailOffset + tail.size) {
            return tail.copyOfRange((offset - tailOffset).toInt(), tail.size)
        }
        return null
    }

    // 文件末尾 10 字节为 ID3v2 footer（"3DI"）的标签起始偏移，其声明的长度不含头尾各 10 字节。
    // 未内嵌尾部标签时返回 -1
    private fun trailingFooterTagStart(tail: ByteArray, tailOffset: Long): Long {
        if (tail.size < 2 * ID3_HEADER_BYTES || !tail.startsWithAscii("3DI", tail.size - 10)) return -1L
        val size = Id3v2Tag.syncsafe(tail, tail.size - 4)
        return tailOffset + tail.size - 10 - ID3_HEADER_BYTES - size
    }

    // 文本标签：标题/艺术家/专辑，任一字段都可能为 null（容器内未写该字段）
    class TextTag(val title: String?, val artist: String?, val album: String?)

    // 容器内承载标签的两种形态：内嵌 ID3v2 标签、WAV 的 LIST/INFO 项区
    private sealed interface EmbeddedTag {
        class Id3(val bytes: ByteArray) : EmbeddedTag
        class Info(val items: List<Pair<String, ByteArray>>) : EmbeddedTag
    }

    // ---- 字节工具 ----

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

    // WAV 的 ID3 块标识：读取时兼收小写变体
    private const val TAG_CHUNK_ID = "ID3 "
    private val WAV_TAG_CHUNK_IDS = setOf("ID3 ", "id3 ")

    // 容器标识窗口：RIFF 头 12 字节（"RIFF" + 尺寸 + "WAVE"），16 字节足以判定容器
    private const val FORM_MAGIC_BYTES = 16

    // 尾部标签的定位窗口：ID3v2 footer 占末尾 10 字节，取一个小窗口即可完成定位
    private const val TRAILING_WINDOW_BYTES = 64

    // 块体与标签区的读取上限：标签含封面时可达数 MB，上限用于挡住异常容器把音频体当标签读进堆
    private const val TAG_BODY_READ_LIMIT_BYTES = 32L * 1024 * 1024

    // WAV（RIFF）块结构：容器头 12 字节（"RIFF" + 尺寸 + "WAVE"），块头 8 字节（标识 + 小端长度）
    private const val RIFF_MAGIC = "RIFF"
    private const val WAVE_FORM_TYPE = "WAVE"
    private const val RIFF_HEADER_BYTES = 12
    private const val RIFF_SIZE_OFFSET = 4
    // RIFF 尺寸字段自「标识 + 长度字段」之后起算，故总长按该基数扣减
    private const val RIFF_SIZE_BASE_BYTES = 8
    private const val WAV_CHUNK_HEADER_BYTES = 8
    private const val WAV_DATA_CHUNK_ID = "data"
    private const val LIST_CHUNK_ID = "LIST"
    private const val INFO_FORM_TYPE = "INFO"
    // LIST/INFO 的项标识：标题、艺术家、专辑、日期、流派、备注、版权、音轨号
    private const val INFO_TITLE_ID = "INAM"
    private const val INFO_ARTIST_ID = "IART"
    private const val INFO_ALBUM_ID = "IPRD"
    private const val INFO_DATE_ID = "ICRD"
    private const val INFO_GENRE_ID = "IGNR"
    private const val INFO_COMMENT_ID = "ICMT"
    private const val INFO_COPYRIGHT_ID = "ICOP"
    private const val INFO_TRACK_ID = "IPRT"
    // ID3 文本帧：标题、艺术家、专辑
    private const val TITLE_FRAME_ID = "TIT2"
    private const val ARTIST_FRAME_ID = "TPE1"
    private const val ALBUM_FRAME_ID = "TALB"

    private const val ID3_HEADER_BYTES = 10
    private const val TAG_READ_BYTES = 4 * 1024 * 1024
}
