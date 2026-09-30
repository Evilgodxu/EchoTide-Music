package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

// 无损与线性 PCM 容器的标签读写：AIFF/AIFC、DSDIFF、DSF、APE、WAV。
//   · AIFF/AIFC 与 DSDIFF 同属 IFF 分块容器，标签是容器内的 "ID3 " 块；两者块头同为
//     「4 字节标识 + 大端长度」，只有长度字段位宽（32 位 / 64 位）不同，共用一套实现；
//   · WAV 同属分块容器（块头为「4 字节标识 + 小端长度」），二进制标签写在容器内的 "ID3 " 块，
//     文本标签按 RIFF 惯例写在 LIST/INFO 块；
//   · DSF 的标签是文件末尾的 ID3v2 标签，位置由文件头 metadata 指针给出，
//     写回时须同步回填指针与文件长度；
//   · APE 用文件末尾的 APEv2 标签，位于音频之后、可选的 ID3v1 之前，由 32 字节页脚定位。
//
// 写路径只产出「头部字面字节 + 音频体区间 + 尾部字面字节」，音频体由调用方按区间流式复制。
// 块表只读块头、标签区与保留的块体定点读取，故重写的驻留量与文件大小无关：高解析无损单文件
// 可达数百 MB，整文件驻留会把进程推到系统内存回收线以下。读路径按同一套定位规则从头部窗口
// 与尾部窗口取标签，窗口之外的标签再按绝对偏移定点读取。ID3 帧的编解码统一由 Id3v2Tag 负责
internal object LosslessContainerTags {

    // 标签重写结果：head 替换源文件 [0, bodyStart)，音频体按 [bodyStart, bodyEnd) 复制，tail 追加在末尾。
    // 区间偏移用 Long：高解析无损单文件可越过 2GB 的 Int 边界
    class TagRewrite(val head: ByteArray, val bodyStart: Long, val bodyEnd: Long, val tail: ByteArray)

    // ---- 容器识别 ----

    fun matches(bytes: ByteArray): Boolean =
        isAiff(bytes) || isDff(bytes) || isDsf(bytes) || isApe(bytes) || isWav(bytes)

    fun isAiff(bytes: ByteArray): Boolean =
        bytes.startsWithAscii("FORM", 0) &&
            (bytes.startsWithAscii("AIFF", FORM_TYPE_OFFSET_32) || bytes.startsWithAscii("AIFC", FORM_TYPE_OFFSET_32))

    fun isDff(bytes: ByteArray): Boolean =
        bytes.startsWithAscii("FRM8", 0) && bytes.startsWithAscii("DSD ", FORM_TYPE_OFFSET_64)

    fun isDsf(bytes: ByteArray): Boolean = bytes.startsWithAscii("DSD ", 0)

    fun isApe(bytes: ByteArray): Boolean = bytes.startsWithAscii(APE_MAGIC, 0)

    fun isWav(bytes: ByteArray): Boolean =
        bytes.startsWithAscii("RIFF", 0) && bytes.startsWithAscii("WAVE", 8)

    // 标签可能位于音频之后、需要读尾窗才能定位的容器：WAV 与 AIFF/DSDIFF 的 "ID3 " 块、
    // DSF 的尾部标签、APE 的 APEv2 均落在音频数据之后。
    // 其余容器（FLAC/M4A/Ogg/MP3）的标签都在头部，读尾窗只是平白多读数据
    fun usesTrailingTag(header: ByteArray): Boolean = matches(header)

    // ---- 写 ----

    // 字节入口（测试与字节级调用）：整段字节交回区间入口，判定与定位逻辑完全共用
    fun write(
        source: ByteArray,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): TagRewrite? = write(ByteArrayTagSource(source), title, artist, album, cover, lyrics)

    /**
     * 区间入口：块表按块头逐个定位，标签区与保留的块体定点读取，音频体只给出区间，交调用方搬运。
     *
     * 标签位置依赖文件末尾的容器（尾部标签、页脚）按 [TagSource.size] 判定，
     * 长度不可知时无从定位，返回 null 交由调用方跳过本次重写。
     */
    fun write(
        source: TagSource,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): TagRewrite? {
        if (source.size == TagSource.UNKNOWN_SIZE) return null
        val magic = source.readAt(0, FORM_MAGIC_BYTES) ?: return null
        return when {
            isAiff(magic) -> writeIff(source, AIFF_FORM, title, artist, album, cover, lyrics)
            isDff(magic) -> writeIff(source, DFF_FORM, title, artist, album, cover, lyrics)
            isWav(magic) -> writeWav(source, title, artist, album, cover, lyrics)
            isDsf(magic) -> writeDsf(source, title, artist, album, cover, lyrics)
            isApe(magic) -> writeApe(source, title, artist, album, cover, lyrics)
            else -> null
        }
    }

    // IFF 分块容器（AIFF/AIFC、DSDIFF）：重建 FORM/FRM8 内的块序列，
    // 标签块置于音频块之前——头部窗口必然覆盖该位置，读回无需扫描音频体。
    // 块表只读块头，音频块之外的保留块体定点读取，音频块本体按区间搬运
    private fun writeIff(
        source: TagSource,
        form: IffForm,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): TagRewrite? {
        val formTypeOffset = 4L + form.sizeBytes
        val magic = source.readAt(0, (formTypeOffset + FORM_TYPE_BYTES).toInt()) ?: return null
        if (!magic.startsWithAscii(form.magic, 0)) return null
        if (!form.formTypes.any { magic.startsWithAscii(it, formTypeOffset.toInt()) }) return null
        val chunks = readIffChunks(source, formTypeOffset + FORM_TYPE_BYTES, source.size, form.sizeBytes) ?: return null
        val audioIndex = chunks.indexOfFirst { it.id in form.audioChunkIds }
        if (audioIndex < 0) return null
        val audio = chunks[audioIndex]
        val existing = chunks.firstOrNull { it.id in form.tagChunkIds }
            ?.let { readChunkBody(source, it.bodyStart, it.size.toLong()) ?: return null }
        val version = Id3v2Tag.versionOf(existing)
        val frames = Id3v2Tag.replaceFrames(existing, title, artist, album, cover, lyrics, version)
            ?: return null
        // 保留的块体先全部读出再写出：任一读取失败即整体放弃，不留半截结果
        val keptBefore = chunks.take(audioIndex).filter { it.id !in form.tagChunkIds }
        val keptAfter = chunks.drop(audioIndex + 1).filter { it.id !in form.tagChunkIds }
        val bodiesBefore = keptBefore.map { readChunkBody(source, it.bodyStart, it.size.toLong()) ?: return null }
        val bodiesAfter = keptAfter.map { readChunkBody(source, it.bodyStart, it.size.toLong()) ?: return null }
        val head = ByteArrayOutputStream()
        head.write(form.magic.toByteArray(StandardCharsets.US_ASCII))
        head.write(ByteArray(form.sizeBytes)) // 尺寸占位，最后回填
        head.write(magic, formTypeOffset.toInt(), FORM_TYPE_BYTES)
        keptBefore.forEachIndexed { index, chunk -> writeIffChunk(head, chunk.id, bodiesBefore[index], form.sizeBytes) }
        writeIffChunk(head, TAG_CHUNK_ID, Id3v2Tag.buildTag(frames, version), form.sizeBytes)
        writeIffChunkHeader(head, audio.id, audio.size, form.sizeBytes)
        val tail = ByteArrayOutputStream()
        keptAfter.forEachIndexed { index, chunk -> writeIffChunk(tail, chunk.id, bodiesAfter[index], form.sizeBytes) }
        val bodyStart = audio.bodyStart
        // 音频块按偶数字节对齐，对齐字节归入流式复制的音频体
        val bodyEnd = minOf(audio.paddedEnd, source.size)
        val headBytes = head.toByteArray()
        // FORM/FRM8 尺寸自长度字段之后起算，即总长减去「标识 + 长度字段」
        val declaredSize = headBytes.size + (bodyEnd - bodyStart) + tail.size() - (4 + form.sizeBytes)
        if (declaredSize > Int.MAX_VALUE) return null
        writeSizeBE(headBytes, 4, declaredSize.toInt(), form.sizeBytes)
        return TagRewrite(headBytes, bodyStart, bodyEnd, tail.toByteArray())
    }

    // DSF：标签为文件末尾的 ID3v2 标签（规范要求不带 footer），文件头 metadata 指针与文件长度同步回填。
    // 头部 28 字节整体读入回填，音频体按指针给出的边界搬运
    private fun writeDsf(
        source: TagSource,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): TagRewrite? {
        val fileSize = source.size
        if (fileSize < DSF_HEADER_BYTES) return null
        val head = source.readAt(0, DSF_HEADER_BYTES) ?: return null
        if (!isDsf(head)) return null
        if (!(source.readAt(DSF_DSD_CHUNK_BYTES.toLong(), 4) ?: return null).startsWithAscii("fmt ", 0)) return null
        // 指针未指向 ID3 标签时按无元数据处理：宁可新建标签，也不按可疑偏移截断音频
        val pointer = readU64LE(head, DSF_METADATA_POINTER_OFFSET)
        val embeddedTag = pointer in 1 until fileSize &&
            (source.readAt(pointer, 3)?.startsWithAscii("ID3", 0) == true)
        val audioEnd = if (embeddedTag) pointer else fileSize
        val existing = if (audioEnd < fileSize) {
            readChunkBody(source, audioEnd, fileSize - audioEnd) ?: return null
        } else {
            null
        }
        val version = Id3v2Tag.versionOf(existing)
        val frames = Id3v2Tag.replaceFrames(existing, title, artist, album, cover, lyrics, version)
            ?: return null
        val tail = Id3v2Tag.buildTag(frames, version)
        writeU64LE(head, DSF_FILE_SIZE_OFFSET, audioEnd + tail.size)
        writeU64LE(head, DSF_METADATA_POINTER_OFFSET, audioEnd)
        return TagRewrite(head, DSF_HEADER_BYTES.toLong(), audioEnd, tail)
    }

    // APE：APEv2 标签位于音频之后、可选的 ID3v1 之前。仅重写标签区，音频体整段流式复制
    private fun writeApe(
        source: TagSource,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): TagRewrite? {
        val fileSize = source.size
        if (!(source.readAt(0, APE_MAGIC.length) ?: return null).startsWithAscii(APE_MAGIC, 0)) return null
        val id3v1 = hasId3v1(source, fileSize)
        val end = if (id3v1) fileSize - ID3V1_BYTES else fileSize
        val existing = findApeTag(source, end)
        val items = mergeApeItems(existing?.items.orEmpty(), title, artist, album, cover, lyrics)
        if (items.isEmpty()) return null
        val tail = ByteArrayOutputStream()
        tail.write(buildApeTag(items))
        if (id3v1) {
            val id3v1Bytes = source.readAt(fileSize - ID3V1_BYTES, ID3V1_BYTES) ?: return null
            tail.write(id3v1Bytes)
        }
        return TagRewrite(ByteArray(0), 0L, existing?.audioEnd ?: end, tail.toByteArray())
    }

    // APEv2 条目合并：键名大小写不敏感，被覆盖的键连同同义键一并剔除，其余条目原样保留
    private fun mergeApeItems(
        existing: List<ApeItem>,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): List<ApeItem> {
        val replaced = mutableSetOf<String>()
        if (title != null) replaced += "title"
        if (artist != null) replaced += "artist"
        if (album != null) replaced += "album"
        if (lyrics != null) replaced += LYRICS_KEYS
        if (cover != null) replaced += COVER_KEYS
        val items = existing.filterNot { it.key.lowercase() in replaced }.toMutableList()
        if (title != null) items += ApeItem("Title", false, title.toByteArray(StandardCharsets.UTF_8))
        if (artist != null) items += ApeItem("Artist", false, artist.toByteArray(StandardCharsets.UTF_8))
        if (album != null) items += ApeItem("Album", false, album.toByteArray(StandardCharsets.UTF_8))
        if (lyrics != null) items += ApeItem("Lyrics", false, lyrics.toByteArray(StandardCharsets.UTF_8))
        if (cover != null) {
            // 封面为二进制条目：值 = 文件名 + \0 + 图片数据，文件名仅用于标识图片类型
            val name = APE_COVER_NAMES[MusicMetadataWriter.sniffMimeType(cover)] ?: "cover.jpg"
            val value = name.toByteArray(StandardCharsets.ISO_8859_1) + byteArrayOf(0) + cover
            items += ApeItem("Cover Art (Front)", true, value)
        }
        // 规范建议条目按长度升序排列，便于流式场景尽早拿到重要字段
        return items.sortedBy { it.value.size }
    }

    // WAV：重建 RIFF 块序列。二进制标签收敛为 data 之后的一个 "ID3 " 块——与 ffmpeg 等工具的
    // 布局一致，容器内只有一份标签，旧版本写在容器外文件末尾的标签随之并入该块；
    // 文本标签收敛为 data 之前的一个 LIST/INFO 块。块表只读块头，音频体按区间流式复制
    private fun writeWav(
        source: TagSource,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
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
        // 无既有标签且无字段可写时不落标签块：仍完成块序列重建，返回文件本身
        val frames = Id3v2Tag.replaceFrames(existing, title, artist, album, cover, lyrics, version)
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
                buildListInfo(info, title, artist, album)?.let {
                    writeRiffChunk(head, LIST_CHUNK_ID, it)
                    infoWritten = true
                }
                return@forEachIndexed
            }
            writeRiffChunk(head, chunk.id, bodiesBefore[bodyIndex++])
        }
        if (!infoWritten) {
            buildListInfo(info, title, artist, album)?.let { writeRiffChunk(head, LIST_CHUNK_ID, it) }
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

    // 重建 LIST/INFO 内容：保留既有项原样，覆盖 INAM(标题)/IART(艺术家)/IPRD(专辑)。
    // INFO 值按 RIFF 惯例以单字节 0 结尾，新建项据此补齐
    private fun buildListInfo(existing: ByteArray?, title: String?, artist: String?, album: String?): ByteArray? {
        val kept = existing?.takeIf { it.size >= 4 }?.let { parseInfoItems(it, 4) }.orEmpty()
        val replaced = mutableSetOf<String>()
        if (title != null) replaced += INFO_TITLE_ID
        if (artist != null) replaced += INFO_ARTIST_ID
        if (album != null) replaced += INFO_ALBUM_ID
        // 被覆盖的键不进新块，其余项连同原标识原样保留
        val items = kept.filterNot { it.first.uppercase() in replaced }.toMutableList()
        if (title != null) items += INFO_TITLE_ID to infoValue(title)
        if (artist != null) items += INFO_ARTIST_ID to infoValue(artist)
        if (album != null) items += INFO_ALBUM_ID to infoValue(album)
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
                is EmbeddedTag.Ape -> LYRICS_KEYS.firstNotNullOfOrNull { key ->
                    tag.items.firstOrNull { it.key.lowercase() == key && !it.binary }
                }?.let { item ->
                    String(item.value, StandardCharsets.UTF_8).takeIf { it.isNotBlank() }?.let { return it }
                }
                // LIST/INFO 不承载歌词
                is EmbeddedTag.Info -> Unit
            }
        }
        return null
    }

    // 取内嵌封面图片字节
    fun readCover(
        header: ByteArray,
        tail: ByteArray?,
        tailOffset: Long,
        readAt: ((Long, Int) -> ByteArray?)?,
    ): ByteArray? {
        for (tag in collectTags(header, tail, tailOffset, readAt)) {
            when (tag) {
                is EmbeddedTag.Id3 -> Id3v2Tag.readApic(tag.bytes, 0)?.let { return it }
                is EmbeddedTag.Ape -> tag.items
                    .firstOrNull { it.key.lowercase() in COVER_KEYS && it.binary }
                    ?.let { item -> apeCoverPayload(item.value) }
                    ?.let { return it }
                // LIST/INFO 只承载文本，封面不由它提供
                is EmbeddedTag.Info -> Unit
            }
        }
        return null
    }

    // 取内嵌文本标签（标题/艺术家/专辑）。ID3 帧与 APE 条目是首选来源，
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
                is EmbeddedTag.Ape -> {
                    title = title ?: apeText(tag.items, "title")
                    artist = artist ?: apeText(tag.items, "artist")
                    album = album ?: apeText(tag.items, "album")
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

    // APE 文本条目：值为 UTF-8，可能带结尾 NUL
    private fun apeText(items: List<ApeItem>, key: String): String? =
        items.firstOrNull { it.key.lowercase() == key && !it.binary }
            ?.let { String(it.value, StandardCharsets.UTF_8).trimEnd('\u0000').takeIf { it.isNotBlank() } }

    // LIST/INFO 项值：按 RIFF 惯例以单字节 0 结尾，解码后一并去掉
    private fun infoText(items: List<Pair<String, ByteArray>>, id: String): String? =
        items.firstOrNull { it.first.uppercase() == id }
            ?.let { String(it.second, StandardCharsets.UTF_8).trimEnd('\u0000').takeIf { it.isNotBlank() } }

    // 按容器类型收集文件内可能承载标签的区段
    private fun collectTags(
        header: ByteArray,
        tail: ByteArray?,
        tailOffset: Long,
        readAt: ((Long, Int) -> ByteArray?)?,
    ): List<EmbeddedTag> {
        val tags = mutableListOf<EmbeddedTag>()
        when {
            isAiff(header) || isDff(header) -> {
                val form = if (isAiff(header)) AIFF_FORM else DFF_FORM
                // 块表在头窗内即可定位标签块；块体（可能含大封面）越出窗口时定点读取
                findIffTagChunkRange(header, 4 + form.sizeBytes + 4, form.sizeBytes)?.let { range ->
                    val bytes = if (range.last <= header.size) {
                        header.copyOfRange(range.first, range.last)
                    } else {
                        readAt?.invoke(range.first.toLong(), range.last - range.first)
                    }
                    if (bytes != null) tags += EmbeddedTag.Id3(bytes)
                }
                // 标签块位于音频之后时超出头窗，转由尾窗按块结构定位
                if (tags.isEmpty() && tail != null) {
                    findIffTagChunk(tail, form.sizeBytes)?.let { tags += EmbeddedTag.Id3(it) }
                }
            }
            isDsf(header) -> {
                val pointer = readU64LE(header, DSF_METADATA_POINTER_OFFSET)
                if (pointer > 0) {
                    sliceAt(pointer, header, tail, tailOffset, readAt)?.let { tags += EmbeddedTag.Id3(it) }
                }
            }
            isApe(header) -> {
                // APE 的标签在文件末尾，头窗读不到；页脚只能从尾窗定位
                if (tail != null) {
                    findApeFooter(tail)?.let { footer ->
                        val tagSize = readU32LE(tail, footer + APE_TAG_SIZE_OFFSET)
                        val itemCount = readU32LE(tail, footer + APE_ITEM_COUNT_OFFSET)
                        val itemsStart = tailOffset + footer + APE_HEADER_BYTES - tagSize
                        if (tagSize > 0 && itemCount > 0) {
                            sliceAt(itemsStart, header, tail, tailOffset, readAt)?.let { region ->
                                tags += EmbeddedTag.Ape(parseApeItems(region, 0, itemCount))
                            }
                        }
                    }
                }
            }
            isWav(header) -> collectWavTags(header, tail, tailOffset, readAt, tags)
            else -> {
                // 其余容器（FLAC/M4A/Ogg/MP3）的标签都在头部，窗口取不到即视为无标签
            }
        }
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

    // APE 页脚位置：标签位于音频之后、可选 ID3v1 之前，故页脚至多前移 128 字节
    private fun findApeFooter(tail: ByteArray): Int? {
        if (tail.size < APE_HEADER_BYTES) return null
        val from = (tail.size - APE_HEADER_BYTES - ID3V1_BYTES).coerceAtLeast(0)
        for (local in tail.size - APE_HEADER_BYTES downTo from) {
            if (tail.startsWithAscii(APE_PREAMBLE, local)) return local
        }
        return null
    }

    // 从页脚定位并解析 APEv2 标签：返回音频体结束偏移与全部条目。
    // 页脚只在文件末尾，故只读末尾 32 字节；条目区（含封面，可达数 MB）按页脚声明的长度定点读取
    private fun findApeTag(source: TagSource, end: Long): ApeTag? {
        val footerStart = end - APE_HEADER_BYTES
        if (footerStart < 0) return null
        val footer = source.readAt(footerStart, APE_HEADER_BYTES)
            ?.takeIf { it.size == APE_HEADER_BYTES } ?: return null
        if (!footer.startsWithAscii(APE_PREAMBLE, 0)) return null
        // Tag Size 含页脚、不含头部；含头部时条目区之前另有 32 字节头
        val tagSize = readU32LE(footer, APE_TAG_SIZE_OFFSET)
        val itemCount = readU32LE(footer, APE_ITEM_COUNT_OFFSET)
        val flags = readU32LE(footer, APE_FLAGS_OFFSET)
        val itemsStart = footerStart + APE_HEADER_BYTES - tagSize
        val hasHeader = flags and APE_FLAG_HAS_HEADER != 0
        val audioEnd = itemsStart - if (hasHeader) APE_HEADER_BYTES else 0
        if (itemsStart < 0 || audioEnd < 0) return null
        val region = readChunkBody(source, itemsStart, end - itemsStart) ?: return null
        return ApeTag(audioEnd, parseApeItems(region, 0, itemCount))
    }

    private fun parseApeItems(source: ByteArray, from: Int, count: Int): List<ApeItem> {
        val items = mutableListOf<ApeItem>()
        var p = from
        repeat(count) {
            if (p + 8 > source.size) return@repeat
            val valueSize = readU32LE(source, p)
            val flags = readU32LE(source, p + 4)
            p += 8
            val keyEnd = (p until source.size).firstOrNull { source[it] == 0.toByte() } ?: return@repeat
            val key = String(source, p, keyEnd - p, StandardCharsets.ISO_8859_1)
            p = keyEnd + 1
            if (valueSize < 0 || p + valueSize > source.size) return@repeat
            items += ApeItem(key, flags and APE_ITEM_ENCODING_MASK == APE_ITEM_BINARY, source.copyOfRange(p, p + valueSize))
            p += valueSize
        }
        return items
    }

    // 封面条目的值：文件名 + \0 + 图片数据
    private fun apeCoverPayload(value: ByteArray): ByteArray? {
        val separator = value.indexOf(0)
        if (separator < 0) return value.takeIf { it.isNotEmpty() }
        return value.copyOfRange(separator + 1, value.size).takeIf { it.isNotEmpty() }
    }

    // 在窗口中按 IFF 块结构定位 ID3 块的载荷范围（绝对偏移，终点不含）。
    // 只依赖块头即可定位，故块体（可能含大封面）越出窗口时仍能给出完整范围，交由调用方定点读取；
    // 非标签块的块体越界则后续块无从定位，终止遍历
    private fun findIffTagChunkRange(window: ByteArray, from: Int, sizeBytes: Int): IntRange? {
        var p = from
        while (p + 4 + sizeBytes <= window.size) {
            val id = String(window, p, 4, StandardCharsets.ISO_8859_1)
            val size = readSizeBE(window, p + 4, sizeBytes)
            if (size <= 0) return null
            val body = p + 4 + sizeBytes
            if (id in IFF_TAG_CHUNK_IDS) return body..(body + size)
            val next = body.toLong() + size + (size and 1)
            if (next > window.size) return null
            p = next.toInt()
        }
        return null
    }

    // 在窗口中按 IFF 块结构定位 ID3 块：块标识命中后还要校验块体内的 ID3 魔数，
    // 避免把音频数据中偶然出现的 "ID3 " 当成标签
    private fun findIffTagChunk(window: ByteArray, sizeBytes: Int): ByteArray? {
        var p = 0
        while (p + 4 + sizeBytes <= window.size) {
            val id = String(window, p, 4, StandardCharsets.ISO_8859_1)
            if (id in IFF_TAG_CHUNK_IDS) {
                val size = readSizeBE(window, p + 4, sizeBytes)
                val body = p + 4 + sizeBytes
                if (size > 0 && body + size <= window.size && window.startsWithAscii("ID3", body)) {
                    return window.copyOfRange(body, body + size)
                }
            }
            p++
        }
        return null
    }

    // ---- IFF 块 ----

    private class IffForm(
        val magic: String,
        val formTypes: Set<String>,
        val audioChunkIds: Set<String>,
        val tagChunkIds: Set<String>,
        val sizeBytes: Int,
    )

    private class IffChunk(val id: String, val bodyStart: Long, val size: Int) {
        val bodyEnd get() = bodyStart + size
        val paddedEnd get() = bodyEnd + (size and 1)
    }

    // 遍历 IFF 块表：只读块头（块体按需定点读取），块结构不成立（长度越界）时返回 null，不做截断猜测
    private fun readIffChunks(source: TagSource, from: Long, to: Long, sizeBytes: Int): List<IffChunk>? {
        val chunks = mutableListOf<IffChunk>()
        var p = from
        while (p + FORM_TYPE_BYTES + sizeBytes <= to) {
            val header = source.readAt(p, FORM_TYPE_BYTES + sizeBytes)
                ?.takeIf { it.size == FORM_TYPE_BYTES + sizeBytes } ?: return null
            val id = String(header, 0, 4, StandardCharsets.ISO_8859_1)
            val size = readSizeBE(header, FORM_TYPE_BYTES, sizeBytes)
            val bodyStart = p + FORM_TYPE_BYTES + sizeBytes
            if (size < 0 || bodyStart + size > to) return null
            chunks += IffChunk(id, bodyStart, size)
            p = bodyStart + size + (size and 1)
        }
        return chunks
    }

    private fun writeIffChunk(out: ByteArrayOutputStream, id: String, body: ByteArray, sizeBytes: Int) {
        writeIffChunkHeader(out, id, body.size, sizeBytes)
        out.write(body)
        if (body.size and 1 != 0) out.write(0)
    }

    private fun writeIffChunkHeader(out: ByteArrayOutputStream, id: String, size: Int, sizeBytes: Int) {
        out.write(id.toByteArray(StandardCharsets.ISO_8859_1))
        writeSizeBE(out, size, sizeBytes)
    }

    // 文本标签：标题/艺术家/专辑，任一字段都可能为 null（容器内未写该字段）
    class TextTag(val title: String?, val artist: String?, val album: String?)

    // 容器内承载标签的三种形态：内嵌 ID3v2 标签、APEv2 条目区、WAV 的 LIST/INFO 项区
    private sealed interface EmbeddedTag {
        class Id3(val bytes: ByteArray) : EmbeddedTag
        class Ape(val items: List<ApeItem>) : EmbeddedTag
        class Info(val items: List<Pair<String, ByteArray>>) : EmbeddedTag
    }

    private class ApeItem(val key: String, val binary: Boolean, val value: ByteArray)

    // APEv2 标签：音频体结束偏移与条目列表
    private class ApeTag(val audioEnd: Long, val items: List<ApeItem>)

    // APEv2 标签整体布局：头部(32) + 条目区 + 页脚(32)。Tag Size 含页脚、不含头部，
    // 头部与页脚除 flags 的「本块是头部」位外完全一致
    private fun buildApeTag(items: List<ApeItem>): ByteArray {
        val itemBytes = ByteArrayOutputStream()
        items.forEach { item ->
            itemBytes.write(intBytesLE(item.value.size))
            itemBytes.write(intBytesLE(if (item.binary) APE_ITEM_BINARY else 0))
            itemBytes.write(item.key.toByteArray(StandardCharsets.US_ASCII))
            itemBytes.write(0)
            itemBytes.write(item.value)
        }
        val body = itemBytes.toByteArray()
        val tagSize = body.size + APE_HEADER_BYTES
        val out = ByteArrayOutputStream()
        out.write(apeHeaderOrFooter(tagSize, items.size, APE_FLAG_HEADER))
        out.write(body)
        out.write(apeHeaderOrFooter(tagSize, items.size, APE_FLAG_FOOTER))
        return out.toByteArray()
    }

    private fun apeHeaderOrFooter(tagSize: Int, itemCount: Int, flags: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(APE_PREAMBLE.toByteArray(StandardCharsets.US_ASCII))
        out.write(intBytesLE(APE_VERSION))
        out.write(intBytesLE(tagSize))
        out.write(intBytesLE(itemCount))
        out.write(intBytesLE(flags))
        out.write(ByteArray(8))
        return out.toByteArray()
    }

    // 末尾 128 字节为 ID3v1 标签：APE 标签位于其之前，故只读文件末尾这一小段
    private fun hasId3v1(source: TagSource, fileSize: Long): Boolean {
        if (fileSize < ID3V1_BYTES) return false
        val tail = source.readAt(fileSize - ID3V1_BYTES, 4) ?: return false
        return tail.startsWithAscii("TAG", 0)
    }

    // ---- 字节工具 ----

    private fun readSizeBE(bytes: ByteArray, at: Int, sizeBytes: Int): Int {
        if (sizeBytes == 4) return readU32BE(bytes, at)
        // 64 位长度只取低 32 位：音频容器不可能超过 4GB 的单个块
        val high = readU32BE(bytes, at)
        val low = readU32BE(bytes, at + 4)
        return if (high != 0) -1 else low
    }

    private fun writeSizeBE(out: ByteArrayOutputStream, value: Int, sizeBytes: Int) {
        if (sizeBytes == 8) out.write(intBytesBE(0))
        out.write(intBytesBE(value))
    }

    private fun writeSizeBE(bytes: ByteArray, at: Int, value: Int, sizeBytes: Int) {
        if (sizeBytes == 8) writeIntBE(bytes, at, 0)
        writeIntBE(bytes, at + sizeBytes - 4, value)
    }

    private fun ByteArray.startsWithAscii(value: String, at: Int): Boolean {
        if (at < 0 || at + value.length > size) return false
        for (i in value.indices) {
            if (this[at + i].toInt() and 0xFF != value[i].code) return false
        }
        return true
    }

    private fun readU32BE(bytes: ByteArray, at: Int): Int =
        if (at + 4 > bytes.size) -1 else
            (bytes[at].toInt() and 0xFF shl 24) or (bytes[at + 1].toInt() and 0xFF shl 16) or
                (bytes[at + 2].toInt() and 0xFF shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun readU32LE(bytes: ByteArray, at: Int): Int =
        if (at + 4 > bytes.size) -1 else
            (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() and 0xFF shl 8) or
                (bytes[at + 2].toInt() and 0xFF shl 16) or (bytes[at + 3].toInt() and 0xFF shl 24)

    private fun readU64LE(bytes: ByteArray, at: Int): Long =
        if (at + 8 > bytes.size) 0L else
            (readU32LE(bytes, at + 4).toLong() shl 32) or (readU32LE(bytes, at).toLong() and 0xFFFFFFFFL)

    private fun writeU64LE(bytes: ByteArray, at: Int, value: Long) {
        writeIntLE(bytes, at, value.toInt())
        writeIntLE(bytes, at + 4, (value ushr 32).toInt())
    }

    private fun writeIntLE(bytes: ByteArray, at: Int, value: Int) {
        if (at + 4 > bytes.size) return
        bytes[at] = value.toByte()
        bytes[at + 1] = (value shr 8).toByte()
        bytes[at + 2] = (value shr 16).toByte()
        bytes[at + 3] = (value shr 24).toByte()
    }

    private fun writeIntBE(bytes: ByteArray, at: Int, value: Int) {
        if (at + 4 > bytes.size) return
        bytes[at] = (value shr 24).toByte()
        bytes[at + 1] = (value shr 16).toByte()
        bytes[at + 2] = (value shr 8).toByte()
        bytes[at + 3] = value.toByte()
    }

    private fun intBytesBE(value: Int): ByteArray =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun intBytesLE(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    // 标签块标识：AIFF 与 DSDIFF 均以大写 "ID3 " 写入，读取时兼收小写变体；WAV 的 ID3 块同理
    private const val TAG_CHUNK_ID = "ID3 "
    private val IFF_TAG_CHUNK_IDS = setOf("ID3 ", "id3 ")
    private val WAV_TAG_CHUNK_IDS = setOf("ID3 ", "id3 ")
    private val AIFF_FORM = IffForm("FORM", setOf("AIFF", "AIFC"), setOf("SSND"), IFF_TAG_CHUNK_IDS, 4)
    private val DFF_FORM = IffForm("FRM8", setOf("DSD "), setOf("DSD ", "DST "), IFF_TAG_CHUNK_IDS, 8)
    private const val FORM_TYPE_OFFSET_32 = 8
    private const val FORM_TYPE_OFFSET_64 = 12

    // 容器标识窗口：IFF 的形态字段最远落在偏移 12（FRM8 + 64 位长度），16 字节足以判定容器
    private const val FORM_MAGIC_BYTES = 16

    // 块标识字段宽度：IFF 与 RIFF 的块标识同为 4 字节
    private const val FORM_TYPE_BYTES = 4

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
    // LIST/INFO 的项标识：标题、艺术家、专辑
    private const val INFO_TITLE_ID = "INAM"
    private const val INFO_ARTIST_ID = "IART"
    private const val INFO_ALBUM_ID = "IPRD"
    // ID3 文本帧：标题、艺术家、专辑
    private const val TITLE_FRAME_ID = "TIT2"
    private const val ARTIST_FRAME_ID = "TPE1"
    private const val ALBUM_FRAME_ID = "TALB"

    private const val ID3_HEADER_BYTES = 10
    private const val TAG_READ_BYTES = 4 * 1024 * 1024
    private const val DSF_HEADER_BYTES = 28
    private const val DSF_DSD_CHUNK_BYTES = 28
    private const val DSF_FILE_SIZE_OFFSET = 12
    private const val DSF_METADATA_POINTER_OFFSET = 20

    private const val APE_PREAMBLE = "APETAGEX"
    // APE（Monkey's Audio）容器标识：标签页脚标识为 APE_PREAMBLE，两者不可混用
    private const val APE_MAGIC = "MAC "
    private const val APE_VERSION = 2000
    private const val APE_HEADER_BYTES = 32
    private const val APE_TAG_SIZE_OFFSET = 12
    private const val APE_ITEM_COUNT_OFFSET = 16
    private const val APE_FLAGS_OFFSET = 20
    private const val APE_FLAG_HAS_HEADER = 0x80000000.toInt()
    private const val APE_FLAG_IS_HEADER = 0x20000000
    private const val APE_FLAG_HEADER = APE_FLAG_HAS_HEADER or APE_FLAG_IS_HEADER
    private const val APE_FLAG_FOOTER = APE_FLAG_HAS_HEADER
    // 条目编码位（位 1..2）：0=文本，1=二进制
    private const val APE_ITEM_ENCODING_MASK = 0x6
    private const val APE_ITEM_BINARY = 0x2
    private const val ID3V1_BYTES = 128
    // 歌词键按优先级排列：APE 规范推荐 "Lyrics"，同时兼容由 ID3 映射而来的同步/非同步歌词键
    private val LYRICS_KEYS = listOf("lyrics", "unsyncedlyrics", "syncedlyrics")
    private val COVER_KEYS = setOf("cover art (front)", "cover art (back)")
    private val APE_COVER_NAMES = mapOf(
        "image/jpeg" to "cover.jpg",
        "image/png" to "cover.png",
        "image/webp" to "cover.webp",
    )
}
