package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object MusicMetadataWriter {

    // 元数据写入结果：Full 为整曲重建；HeadAndTail 仅重建头部标签，
    // 音频躯干按 audioStart 偏移从源复制，降低大文件写入的峰值内存。
    // 区间偏移用 Long：高解析无损单文件可越过 2GB 的 Int 边界
    private sealed interface WriteResult {
        data class Full(val bytes: ByteArray) : WriteResult
        data class HeadAndTail(val head: ByteArray, val audioStart: Long) : WriteResult
        // 中间音频体按 [bodyStart, bodyEnd) 流式复制，尾部追加元数据（用于 WAV 文件尾 ID3 标签）
        data class HeadAndRange(val head: ByteArray, val bodyStart: Long, val bodyEnd: Long, val tail: ByteArray) : WriteResult
    }

    /**
     * 元数据重写方案：头部字面字节替换源文件 [0, bodyStart)，音频躯干按 [bodyStart, bodyEnd)
     * 逐块搬运，尾部字面字节追加在末尾。
     *
     * 解析只吃头部窗口，音频躯干不整体驻留内存，单次重写的峰值内存与文件大小解耦。
     * 在线缓存高解析无损（单文件可达数百 MB）时，整文件驻留会把进程推到系统内存回收线以下，
     * 被系统直接杀死且不产生任何崩溃日志。
     */
    private class RewritePlan(
        val head: ByteArray,
        val bodyStart: Long,
        val bodyEnd: Long,
        val tail: ByteArray,
    ) {
        // 落到字节数组（整文件解析路径与测试用）：躯干区间按实际可用长度截断
        fun materialize(body: ByteArray): ByteArray {
            val start = bodyStart.coerceIn(0L, body.size.toLong()).toInt()
            val end = bodyEnd.coerceIn(start.toLong(), body.size.toLong()).toInt()
            return head + body.copyOfRange(start, end) + tail
        }
    }

    // 流式复制音频躯干时的读缓冲大小
    private const val STREAM_BUFFER_SIZE = 64 * 1024

    // 容器嗅探窗口：各容器魔数都在文件最前，该窗口同时覆盖 OpusHead 的定位范围
    private const val SNIFF_WINDOW_BYTES = 64 * 1024

    // 单次重写允许驻留内存的字节上限：标签解析窗口与按原字节搬运的 MP4 顶层盒子共用该上限，
    // 超过即放弃本次重写并记日志，不无限扩大驻留量
    private const val MAX_IN_MEMORY_BYTES = 32 * 1024 * 1024

    // Ogg 页序列重排上限：标签页数变化后需逐页重编号与重算 CRC，故须持有整条包流；
    // 超过该上限即跳过本次重写并记日志
    private const val OGG_PARSE_LIMIT_BYTES = 32L * 1024 * 1024

    // MP4 顶层盒子的长度字段宽度与盒子标识字段宽度
    private const val MP4_BOX_HEADER_BYTES = 8

    // FLAC 元数据块头：1 字节标志 + 3 字节长度
    private const val FLAC_BLOCK_HEADER_BYTES = 4

    // ID3v2 标签头长度
    private const val ID3_HEADER_BYTES = 10

    // content URI 重写的中转文件前缀，与缓存台账的临时文件回收共用同一份命名约定
    private const val METADATA_TEMP_PREFIX = "metadata"

    // Ogg 单页段数上限：段表为单字节长度数组，一个页面最多承载 255 段
    private const val MAX_SEGMENTS = 255

    // Ogg 页头中 CRC 字段的偏移（页头 27 字节内）
    private const val CRC_FIELD_OFFSET = 22

    suspend fun writeCover(context: Context, track: MusicTrack, coverBytes: ByteArray): Boolean =
        writeToTrack(context, track) { source -> plan(source, null, null, null, coverBytes, null) }

    // 一次性写入标题/艺术家/封面：本地文件走文件路径重建，在线缓存歌走 content URI 就地重写
    suspend fun writeMetadataToSource(
        context: Context,
        track: MusicTrack,
        title: String,
        artist: String,
        cover: ByteArray?,
        lyrics: String? = null,
    ): Boolean = writeToTrack(context, track) { source -> plan(source, title, artist, null, cover, lyrics) }

    /**
     * 把歌词文本内嵌进音频文件：本地文件走文件路径重建，在线缓存歌走 content URI 就地重写，
     * 纯在线流（无本地文件）无可写目标而跳过。
     * 内嵌的是增强 LRC 文本，与歌词缓存文件同源，可被内嵌歌词读取器原样解析回来。
     */
    suspend fun writeLyricsToSource(context: Context, track: MusicTrack, lyrics: String): Boolean =
        writeToTrack(context, track) { source -> plan(source, null, null, null, null, lyrics) }

    // 按曲目的源形态选择写入通道：有本地路径即写文件，在线缓存歌经 content URI 回写
    private suspend fun writeToTrack(
        context: Context,
        track: MusicTrack,
        plan: (TagSource) -> RewritePlan?,
    ): Boolean = withContext(Dispatchers.IO) {
        if (track.path.isNotBlank()) write(context, track.path, plan) else rewriteByUri(context, track.audioUri, plan)
    }

    // 写本地文件：同目录中转文件 + 原子替换，音频躯干在写出过程中按区间搬运
    private fun write(context: Context, path: String, plan: (TagSource) -> RewritePlan?): Boolean {
        if (path.isBlank()) {
            CrashLogManager.logException("MusicMetadataWriter", "写入音频文件元数据跳过: 路径为空")
            return false
        }
        return try {
            val file = File(path)
            val source = FileTagSource(file)
            val rewrite = plan(source) ?: run {
                CrashLogManager.logException(
                    "MusicMetadataWriter",
                    "写入音频文件元数据失败: 文件为空/损坏、格式无法识别或超出解析上限," +
                        " 路径=$path, 大小=${source.size}, 头部=${hexPrefix(source.readAt(0, 16) ?: ByteArray(0))}",
                )
                return false
            }
            val temporary = File(file.parentFile, ".${file.name}.${System.nanoTime()}.metadata.tmp")
            try {
                FileOutputStream(temporary).use { output -> writeRewrite(rewrite, source, output) }
                moveReplacing(temporary, file)
            } catch (e: Throwable) {
                // 半截的中转文件不可留：替换失败时目标文件仍是原文件
                temporary.delete()
                throw e
            }
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            true
        } catch (e: Throwable) {
            CrashLogManager.logException("MusicMetadataWriter", "写入音频文件元数据失败", e)
            false
        }
    }

    // 就地重写 content URI 音频文件：content URI 无寻址写能力，先按方案把结果流式落到缓存中转文件，
    // 再整体回写目标，全程不把文件读进堆；非 content 协议不可写时返回 false。
    // 各失败分支均记录错误日志，便于定位“读取到不完整数据”导致的静默写入失败
    private fun rewriteByUri(context: Context, uriString: String, plan: (TagSource) -> RewritePlan?): Boolean {
        if (uriString.isBlank()) {
            CrashLogManager.logException("MusicMetadataWriter", "经 content URI 写入元数据跳过: URI 为空")
            return false
        }
        val uri = Uri.parse(uriString)
        if (uri.scheme != "content") {
            CrashLogManager.logException(
                "MusicMetadataWriter",
                "经 content URI 写入元数据跳过: 非 content 协议, scheme=${uri.scheme}",
            )
            return false
        }
        return try {
            val source = ContentUriTagSource(context, uri)
            val rewrite = plan(source) ?: run {
                CrashLogManager.logException(
                    "MusicMetadataWriter",
                    "经 content URI 写入元数据失败: 文件为空/损坏、格式无法识别或超出解析上限," +
                        " uri=$uriString, 大小=${source.size}, 头部=${hexPrefix(source.readAt(0, 16) ?: ByteArray(0))}",
                )
                return false
            }
            val temporary = File.createTempFile(METADATA_TEMP_PREFIX, ".tmp", context.cacheDir)
            try {
                FileOutputStream(temporary).use { output -> writeRewrite(rewrite, source, output) }
                val target = context.contentResolver.openOutputStream(uri, "wt") ?: run {
                    CrashLogManager.logException("MusicMetadataWriter", "经 content URI 写入元数据失败: 无法打开输出流, uri=$uriString")
                    return false
                }
                target.use { output -> temporary.inputStream().use { it.copyTo(output, STREAM_BUFFER_SIZE) } }
            } finally {
                temporary.delete()
            }
            true
        } catch (e: Throwable) {
            CrashLogManager.logException("MusicMetadataWriter", "经 content URI 写入音频元数据失败", e)
            false
        }
    }

    // 方案落盘：头部字面字节 → 音频躯干区间搬运 → 尾部字面字节
    private fun writeRewrite(rewrite: RewritePlan, source: TagSource, out: OutputStream) {
        out.write(rewrite.head)
        if (rewrite.bodyEnd > rewrite.bodyStart) source.copyRange(rewrite.bodyStart, rewrite.bodyEnd, out)
        out.write(rewrite.tail)
    }

    suspend fun writeTitleArtist(
        context: Context,
        track: MusicTrack,
        title: String,
        artist: String,
    ): Boolean = writeToTrack(context, track) { source -> plan(source, title, artist, null, null, null) }

    // 将专辑名写回音频文件标签，保留原有标题/艺术家/封面
    suspend fun writeAlbum(
        context: Context,
        track: MusicTrack,
        album: String,
    ): Boolean = writeToTrack(context, track) { source -> plan(source, null, null, album, null, null) }

    /**
     * 重写方案的分派：按容器魔数识别（扩展名不参与判定，改名或加挂 ID3 的文件同样能识别）。
     *
     * 头部窗口足以定位标签的容器（MP3/FLAC/M4A）走窗口解析，音频区不进入内存；
     * 需要遍历全文才能定位标签的容器（WAV/AIFF/DSF/APE 的块表、Ogg 的页序列）走整文件解析，
     * 且受整文件解析上限约束。识别不出容器即不重写。
     */
    private fun plan(
        source: TagSource,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): RewritePlan? {
        val head = source.readAt(0, SNIFF_WINDOW_BYTES) ?: return null
        return when {
            isMp3(head) -> mp3Plan(source, head, title, artist, album, cover, lyrics)
            isFlac(head) -> flacPlan(source, title, artist, album, cover, lyrics)
            isMp4(head) -> mp4Plan(source, title, artist, album, cover, lyrics)
            isOpus(head) -> oggPlan(source, title, artist, album, cover, lyrics)
            LosslessContainerTags.matches(head) -> losslessPlan(source, title, artist, album, cover, lyrics)
            else -> null
        }
    }

    // MP3：解析窗口取 ID3v2 标签区（帧不越过标签末尾），音频帧按标签结束偏移整段搬运
    private fun mp3Plan(
        source: TagSource,
        head: ByteArray,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): RewritePlan? {
        if (!head.startsWith("ID3") || head.size < ID3_HEADER_BYTES) {
            return writeMp3(head, title, artist, album, cover, lyrics)?.toPlan(source)
        }
        val tagEnd = ID3_HEADER_BYTES + Id3v2Tag.syncsafe(head, 6)
        val window = readParseWindow(source, 0L, tagEnd.toLong(), "MP3 标签区") ?: return null
        return writeMp3(window, title, artist, album, cover, lyrics)?.toPlan(source)
    }

    // FLAC：元数据块区从头部窗口起增量扩窗，读到末块标记即止，音频帧不参与解析
    private fun flacPlan(
        source: TagSource,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): RewritePlan? {
        var window = source.readAt(0, SNIFF_WINDOW_BYTES) ?: return null
        while (true) {
            val metadataEnd = flacMetadataEnd(window)
            if (metadataEnd != null) {
                return writeFlac(window.copyOf(metadataEnd), title, artist, album, cover, lyrics)?.toPlan(source)
            }
            val grown = minOf(window.size.toLong() * 2, MAX_IN_MEMORY_BYTES.toLong()).toInt()
            if (grown <= window.size) {
                // 元数据块区超过上限：多为异常文件或超大内嵌封面，放弃本次重写而不无限扩窗
                CrashLogManager.logException(
                    "MusicMetadataWriter",
                    "FLAC 元数据块区超出内存驻留上限，跳过元数据重写: 已读=${window.size}B",
                )
                return null
            }
            window = source.readAt(0, grown) ?: return null
            // 短读说明文件短于窗口：末块标记缺失即结构不完整，不做截断猜测
            if (window.size < grown) return null
        }
    }

    // 元数据块头：类型、块体长度与是否末块
    private class FlacBlockHeader(val type: Int, val length: Int, val last: Boolean)

    private fun flacBlockHeader(bytes: ByteArray, offset: Int): FlacBlockHeader? {
        if (offset + FLAC_BLOCK_HEADER_BYTES > bytes.size) return null
        val header = bytes[offset].toInt() and 0xff
        val length = (bytes[offset + 1].toInt() and 0xff shl 16) or
            (bytes[offset + 2].toInt() and 0xff shl 8) or (bytes[offset + 3].toInt() and 0xff)
        return FlacBlockHeader(header and 0x7f, length, header and 0x80 != 0)
    }

    // 元数据块区结束偏移（末块之后的第一个字节）；窗口未覆盖末块时返回 null
    private fun flacMetadataEnd(window: ByteArray): Int? {
        var offset = 4
        while (true) {
            val header = flacBlockHeader(window, offset) ?: return null
            val end = offset + FLAC_BLOCK_HEADER_BYTES + header.length
            if (end > window.size) return null
            if (header.last) return end
            offset = end
        }
    }

    // MP4 顶层盒子：解析只读盒子头，数据区按长度跳过
    private class Mp4TopBox(val type: String, val offset: Long, val size: Long)

    /**
     * M4A/MP4：顶层盒子表按盒子头逐个定位，moov 盒整体读入重建，mdat 盒按区间原样搬运。
     *
     * moov 位于 mdat 之前时音频数据整体后移，chunk 偏移随 moov 尺寸变化同步修正；
     * 位于其后时音频数据位置未变，无需修正。
     */
    private fun mp4Plan(
        source: TagSource,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): RewritePlan? {
        val boxes = readMp4TopBoxes(source) ?: return null
        val mdat = boxes.firstOrNull { it.type == "mdat" } ?: return null
        val moov = boxes.firstOrNull { it.type == "moov" } ?: return null
        val moovBytes = readParseWindow(source, moov.offset, moov.size, "MP4 moov 盒") ?: return null
        val parsed = Mp4Atom.parseAll(moovBytes)?.singleOrNull()?.takeIf { it.type == "moov" } ?: return null
        val originalSize = parsed.build().size
        parsed.replaceMetadata(title, artist, album, cover, lyrics)
        val sizeDelta = parsed.build().size - originalSize
        if (sizeDelta != 0 && moov.offset < mdat.offset) parsed.adjustChunkOffsets(sizeDelta)
        val rebuiltMoov = parsed.build()
        val headBoxes = boxes.filter { it.offset < mdat.offset }
        val tailBoxes = boxes.filter { it.offset > mdat.offset }
        if ((headBoxes + tailBoxes).any { it.type != "moov" && it.size > MAX_IN_MEMORY_BYTES }) {
            // 音频盒子之外的顶层盒子异常巨大：多为多段 mdat 或结构异常的容器，不做搬运
            CrashLogManager.logException(
                "MusicMetadataWriter",
                "MP4 顶层盒子超出搬运上限，跳过元数据重写: 大小=${source.size}B",
            )
            return null
        }
        val head = ByteArrayOutputStream()
        headBoxes.forEach { box ->
            if (box.type == "moov") head.write(rebuiltMoov) else source.copyRange(box.offset, box.offset + box.size, head)
        }
        val tail = ByteArrayOutputStream()
        tailBoxes.forEach { box ->
            if (box.type == "moov") tail.write(rebuiltMoov) else source.copyRange(box.offset, box.offset + box.size, tail)
        }
        return RewritePlan(head.toByteArray(), mdat.offset, mdat.offset + mdat.size, tail.toByteArray())
    }

    // 顶层盒子表：逐个读盒子头并按声明的长度定位下一个，数据区不进入内存。
    // 长度字段不合法（含 64 位长度与「延伸到文件末尾」）或未能恰好覆盖全文时放弃解析，
    // 与旧实现「解析失败即放弃」同口径，不做截断猜测
    private fun readMp4TopBoxes(source: TagSource): List<Mp4TopBox>? {
        val boxes = mutableListOf<Mp4TopBox>()
        var offset = 0L
        while (offset < source.size) {
            val header = source.readAt(offset, MP4_BOX_HEADER_BYTES) ?: return null
            if (header.size < MP4_BOX_HEADER_BYTES) return null
            val size = int32(header, 0).toLong() and 0xffffffffL
            // 长度字段为 1（64 位长度）或 0（延续到文件末尾）不解析：与旧实现「解析失败即放弃」同口径
            if (size < MP4_BOX_HEADER_BYTES) return null
            boxes += Mp4TopBox(String(header, 4, 4, StandardCharsets.ISO_8859_1), offset, size)
            offset += size
        }
        return boxes.takeIf { it.isNotEmpty() && offset == source.size }
    }

    // 读取 [offset, offset + size) 的解析窗口；超过窗口上限或短读时返回 null
    private fun readParseWindow(source: TagSource, offset: Long, size: Long, what: String): ByteArray? {
        if (size > MAX_IN_MEMORY_BYTES) {
            CrashLogManager.logException(
                "MusicMetadataWriter",
                "$what 超出内存驻留上限，跳过元数据重写: 长度=${size}B",
            )
            return null
        }
        val window = source.readAt(offset, size.toInt()) ?: return null
        return window.takeIf { it.size.toLong() == size }
    }

    /**
     * 无损与线性 PCM 容器（WAV、AIFF/AIFC、DSDIFF、DSF、APE）：标签布局由 LosslessContainerTags
     * 按块表 / 尾部标签结构计算，返回头部字面字节 + 音频体区间 + 尾部字面字节，
     * 定位只读块头与标签区，音频体按区间搬运。
     */
    private fun losslessPlan(
        source: TagSource,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): RewritePlan? {
        val rewrite = LosslessContainerTags.write(source, title, artist, album, cover, lyrics) ?: return null
        return RewritePlan(rewrite.head, rewrite.bodyStart, rewrite.bodyEnd, rewrite.tail)
    }

    /**
     * Ogg/Opus：标签包与音频包同处一条页序列，标签长度变化会改变标签页数，
     * 其后每页的序号与 CRC 都要随之重排，故需要整条包流才能定位。
     * 超过 [OGG_PARSE_LIMIT_BYTES] 即跳过本次重写并记日志，不把大文件读进堆。
     */
    private fun oggPlan(
        source: TagSource,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): RewritePlan? {
        if (source.size > OGG_PARSE_LIMIT_BYTES) {
            CrashLogManager.logException(
                "MusicMetadataWriter",
                "Ogg 文件超出页序列重排上限，跳过元数据重写: 大小=${source.size}B",
            )
            return null
        }
        val bytes = source.readAt(0, source.size.toInt()) ?: return null
        if (bytes.size.toLong() < source.size) return null
        return writeOpus(bytes, title, artist, album, cover, lyrics)?.toPlan(ByteArrayTagSource(bytes))
    }

    // 把整文件解析结果转为重写方案：音频体区间落在给定的字节源上，写出时按区间搬运
    private fun WriteResult.toPlan(source: TagSource): RewritePlan = when (this) {
        is WriteResult.Full -> RewritePlan(bytes, 0L, 0L, ByteArray(0))
        is WriteResult.HeadAndTail -> RewritePlan(head, audioStart, source.size, ByteArray(0))
        is WriteResult.HeadAndRange -> RewritePlan(head, bodyStart, bodyEnd, tail)
    }

    /**
     * 元数据写入的纯字节入口（字节级调用与测试）：解析与线上重写共用同一套方案，
     * 差别只在音频躯干由已驻留的字节提供，故此处覆盖的正是线上路径的容器解析逻辑。
     * 文件为空、格式不支持或无需写入时返回 null
     */
    internal fun writeMetadataBytes(
        bytes: ByteArray,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): ByteArray? = plan(ByteArrayTagSource(bytes), title, artist, album, cover, lyrics)?.materialize(bytes)

    /**
     * 按文件源重写并返回结果字节（测试入口）：与线上写盘共用同一套方案与区间搬运实现，
     * 使「定位读取 + 躯干搬运」的偏移正确性可在 JVM 上直接验证 —— 字节入口覆盖不到这一段
     */
    internal fun rewriteFileBytes(
        file: File,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): ByteArray? {
        val source = FileTagSource(file)
        val rewrite = plan(source, title, artist, album, cover, lyrics) ?: return null
        val out = ByteArrayOutputStream()
        writeRewrite(rewrite, source, out)
        return out.toByteArray()
    }

    // 容器标签布局统一由 LosslessContainerTags 计算，返回的头部字面字节 + 音频体区间 +
    // 尾部字面字节直接落到流式写入分支
    private fun isMp3(bytes: ByteArray) =
        bytes.startsWith("ID3") || (bytes.size >= 2 && bytes[0].toInt() and 0xff == 0xff && bytes[1].toInt() and 0xe0 == 0xe0)

    private fun isMp4(bytes: ByteArray) = bytes.size >= 12 && String(bytes, 4, 4, StandardCharsets.US_ASCII) == "ftyp"
    private fun isFlac(bytes: ByteArray) = bytes.startsWith("fLaC")
    private fun isOpus(bytes: ByteArray) = bytes.startsWith("OggS") && bytes.indexOf("OpusHead".toByteArray()) >= 0

    private fun writeMp3(
        source: ByteArray,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): WriteResult? {
        val hasId3 = source.startsWith("ID3") && source.size >= 10
        val version = if (hasId3) source[3].toInt() and 0xff else 4
        val flags = if (hasId3) source[5].toInt() and 0xff else 0
        if (hasId3 && (version !in 3..4 || flags and 0x1f != 0)) return null
        val tagEnd = if (hasId3) {
            val end = 10 + Id3v2Tag.syncsafe(source, 6)
            if (end > source.size) return null
            end
        } else 0
        val frameStart = if (hasId3) id3FrameStart(source, version, flags, tagEnd) else 0
        val frames = ByteArrayOutputStream()
        if (hasId3 && frameStart > 10) frames.write(source, 10, frameStart - 10)
        var titleWritten = title == null
        var artistWritten = artist == null
        var albumWritten = album == null
        var coverWritten = cover == null
        var lyricsWritten = lyrics == null
        var p = frameStart
        while (p + 10 <= tagEnd) {
            val id = String(source, p, 4, StandardCharsets.US_ASCII)
            if (id.all { it == '\u0000' }) break
            val length = if (version >= 4) Id3v2Tag.syncsafe(source, p + 4) else int32(source, p + 4)
            if (length < 0 || p + 10 + length > tagEnd) return null
            val raw = source.copyOfRange(p, p + 10 + length)
            when (id) {
                "TIT2" -> if (!titleWritten) { Id3v2Tag.textFrame(frames, "TIT2", title!!, version); titleWritten = true } else frames.write(raw)
                "TPE1" -> if (!artistWritten) { Id3v2Tag.textFrame(frames, "TPE1", artist!!, version); artistWritten = true } else frames.write(raw)
                "TALB" -> if (!albumWritten) { Id3v2Tag.textFrame(frames, "TALB", album!!, version); albumWritten = true } else frames.write(raw)
                "APIC" -> if (cover != null && !coverWritten) { Id3v2Tag.apicFrame(frames, cover, version); coverWritten = true } else frames.write(raw)
                "USLT" -> if (!lyricsWritten) { Id3v2Tag.usltFrame(frames, lyrics!!, version); lyricsWritten = true } else frames.write(raw)
                else -> frames.write(raw)
            }
            p += 10 + length
        }
        if (!titleWritten) Id3v2Tag.textFrame(frames, "TIT2", title!!, version)
        if (!artistWritten) Id3v2Tag.textFrame(frames, "TPE1", artist!!, version)
        if (!albumWritten) Id3v2Tag.textFrame(frames, "TALB", album!!, version)
        if (!coverWritten) Id3v2Tag.apicFrame(frames, cover!!, version)
        if (!lyricsWritten) Id3v2Tag.usltFrame(frames, lyrics!!, version)
        val outputVersion = if (hasId3 && version == 3) 3 else 4
        val tag = ByteArrayOutputStream()
        tag.write("ID3".toByteArray()); tag.write(byteArrayOf(outputVersion.toByte(), 0, flags.toByte()))
        tag.write(Id3v2Tag.syncsafeBytes(frames.size())); tag.write(frames.toByteArray())
        // 头部为重建的 ID3 标签，音频躯干按原偏移流式复制，避免整曲二次驻留内存
        val audioStart = if (hasId3) tagEnd else 0
        return WriteResult.HeadAndTail(tag.toByteArray(), audioStart.toLong())
    }

    private fun id3FrameStart(source: ByteArray, version: Int, flags: Int, tagEnd: Int): Int {
        var p = 10
        if (flags and 0x40 != 0 && p + 4 <= tagEnd) {
            val size = if (version >= 4) Id3v2Tag.syncsafe(source, p) else int32(source, p)
            p += 4 + size
        }
        return p.coerceAtMost(tagEnd)
    }

    private fun writeFlac(
        source: ByteArray,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): WriteResult? {
        if (!source.startsWith("fLaC")) return null
        val blocks = mutableListOf<FlacBlock>()
        var p = 4
        var hasLastBlock = false
        while (true) {
            val header = flacBlockHeader(source, p) ?: break
            val end = p + FLAC_BLOCK_HEADER_BYTES + header.length
            if (end > source.size) return null
            blocks += FlacBlock(header.type, source.copyOfRange(p + FLAC_BLOCK_HEADER_BYTES, end))
            p = end
            if (header.last) {
                hasLastBlock = true
                break
            }
        }
        if (!hasLastBlock || p > source.size) return null
        if (title != null || artist != null || album != null || lyrics != null) {
            val commentIndex = blocks.indexOfFirst { it.type == 4 }
            val comments = buildComments(
                if (commentIndex >= 0) blocks[commentIndex].data else null,
                title, artist, album, lyrics,
            )
            if (commentIndex >= 0) blocks[commentIndex] = FlacBlock(4, comments) else blocks.add(FlacBlock(4, comments))
        }
        if (cover != null) {
            blocks.removeAll { block ->
                block.type == 6 && block.data.size >= 4 && int32(block.data, 0) == 3
            }
            blocks.add(FlacBlock(6, pictureBlock(cover)))
        }
        // 头部为重建的元数据块，音频帧按原偏移流式复制
        return WriteResult.HeadAndTail(buildFlac(blocks), p.toLong())
    }

    private data class FlacBlock(val type: Int, val data: ByteArray)

    private fun buildComments(
        original: ByteArray?,
        title: String?,
        artist: String?,
        album: String?,
        lyrics: String?,
    ): ByteArray {
        val vendor: ByteArray
        val fields = mutableListOf<String>()
        if (original != null && original.size >= 8) {
            val vendorLength = intLE(original, 0)
            if (vendorLength >= 0 && 8 + vendorLength <= original.size) {
                vendor = original.copyOfRange(4, 4 + vendorLength)
                var p = 8 + vendorLength
                val count = intLE(original, 4 + vendorLength)
                repeat(count.coerceAtLeast(0)) {
                    if (p + 4 > original.size) return@repeat
                    val length = intLE(original, p); p += 4
                    if (length >= 0 && p + length <= original.size) {
                        val value = String(original, p, length, StandardCharsets.UTF_8)
                        val key = value.substringBefore('=').uppercase()
                        if ((title == null || key != "TITLE") &&
                            (artist == null || key != "ARTIST") &&
                            (album == null || key != "ALBUM") &&
                            (lyrics == null || key != "LYRICS")
                        ) fields += value
                        p += length
                    }
                }
            } else return buildComments(null, title, artist, album, lyrics)
        } else vendor = "EdgeGesture".toByteArray()
        if (title != null) fields.add("TITLE=$title")
        if (artist != null) fields.add("ARTIST=$artist")
        if (album != null) fields.add("ALBUM=$album")
        if (lyrics != null) fields.add("LYRICS=$lyrics")
        val out = ByteArrayOutputStream(); out.write(intBytesLE(vendor.size)); out.write(vendor); out.write(intBytesLE(fields.size))
        fields.forEach { val value = it.toByteArray(StandardCharsets.UTF_8); out.write(intBytesLE(value.size)); out.write(value) }
        return out.toByteArray()
    }

    private fun buildFlac(blocks: List<FlacBlock>): ByteArray {
        val out = ByteArrayOutputStream(); out.write("fLaC".toByteArray())
        blocks.forEachIndexed { index, block ->
            out.write(byteArrayOf((block.type or if (index == blocks.lastIndex) 0x80 else 0).toByte()))
            out.write(byteArrayOf((block.data.size shr 16).toByte(), (block.data.size shr 8).toByte(), block.data.size.toByte()))
            out.write(block.data)
        }
        return out.toByteArray()
    }

    private fun writeOpus(
        source: ByteArray,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): WriteResult? {
        val parsed = OggFile.parse(source) ?: return null
        val tagsIndex = parsed.packets.indexOfFirst { it.data.startsWith("OpusTags") }
        if (tagsIndex < 0) return null
        parsed.packets[tagsIndex] = parsed.packets[tagsIndex].copy(
            data = updateOpusTags(parsed.packets[tagsIndex].data, title, artist, album, cover, lyrics),
        )
        return WriteResult.Full(OggFile.build(parsed))
    }

    private fun updateOpusTags(
        original: ByteArray,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): ByteArray {
        val fields = mutableListOf<String>(); var p = 8
        var vendor = "EdgeGesture".toByteArray()
        if (p + 4 <= original.size) {
            val vendorLength = intLE(original, p)
            if (vendorLength < 0 || p + 4 + vendorLength > original.size) return original
            vendor = original.copyOfRange(p + 4, p + 4 + vendorLength)
            p += 4 + vendorLength
            if (p + 4 <= original.size) {
                val count = intLE(original, p); p += 4
                repeat(count.coerceAtLeast(0)) {
                    if (p + 4 > original.size) return@repeat
                    val length = intLE(original, p); p += 4
                    if (length >= 0 && p + length <= original.size) {
                        val value = String(original, p, length, StandardCharsets.UTF_8)
                        val key = value.substringBefore('=').uppercase()
                        if ((title == null || key != "TITLE") &&
                            (artist == null || key != "ARTIST") &&
                            (album == null || key != "ALBUM") &&
                            (lyrics == null || key != "LYRICS") &&
                            (cover == null || key != "METADATA_BLOCK_PICTURE")
                        ) fields += value
                        p += length
                    }
                }
            }
        }
        if (title != null) fields += "TITLE=$title"
        if (artist != null) fields += "ARTIST=$artist"
        if (album != null) fields += "ALBUM=$album"
        if (lyrics != null) fields += "LYRICS=$lyrics"
        if (cover != null) fields += "METADATA_BLOCK_PICTURE=" + java.util.Base64.getEncoder().encodeToString(pictureBlock(cover))
        val out = ByteArrayOutputStream(); out.write("OpusTags".toByteArray()); out.write(intBytesLE(vendor.size)); out.write(vendor); out.write(intBytesLE(fields.size))
        fields.forEach { val bytes = it.toByteArray(); out.write(intBytesLE(bytes.size)); out.write(bytes) }
        return out.toByteArray()
    }

    private fun pictureBlock(cover: ByteArray): ByteArray {
        val mime = sniffMimeType(cover).toByteArray(); val out = ByteArrayOutputStream()
        out.write(intBytes(3)); out.write(intBytes(mime.size)); out.write(mime); out.write(intBytes(0)); repeat(4) { out.write(intBytes(0)) }; out.write(intBytes(cover.size)); out.write(cover)
        return out.toByteArray()
    }

    private data class Mp4Atom(
        val type: String,
        var data: ByteArray,
        val children: MutableList<Mp4Atom>?,
        // 顶层 atom 在文件中的起始偏移，供 mdat 流式复制定位
        val offset: Int = 0,
    ) {
        fun build(): ByteArray {
            val body = if (children != null) data + children.joinToByteArray() else data
            return intBytes(body.size + 8) + type.toByteArray(StandardCharsets.ISO_8859_1) + body
        }

        fun replaceMetadata(title: String?, artist: String?, album: String?, cover: ByteArray?, lyrics: String?): Mp4Atom {
            if (type == "moov") {
                val udta = children?.firstOrNull { it.type == "udta" }
                if (udta != null) udta.replaceMetadata(title, artist, album, cover, lyrics) else children?.add(Mp4Atom("udta", ByteArray(0), mutableListOf(metaAtom(title, artist, album, cover, lyrics))))
            } else if (type == "udta") {
                val meta = children?.firstOrNull { it.type == "meta" }
                if (meta != null) meta.replaceMetadata(title, artist, album, cover, lyrics) else children?.add(metaAtom(title, artist, album, cover, lyrics))
            } else if (type == "meta") {
                if (children?.none { it.type == "hdlr" } == true) {
                    children.add(0, hdlrAtom())
                }
                val ilst = children?.firstOrNull { it.type == "ilst" }
                if (ilst != null) {
                    ilst.replaceItems(title, artist, album, cover, lyrics)
                } else {
                    children?.add(ilstAtom(title, artist, album, cover, lyrics))
                }
            }
            return this
        }

        fun adjustChunkOffsets(delta: Int) {
            if (type == "stco" && data.size >= 8) {
                val count = int32(data, 4)
                if (count >= 0 && 8 + count * 4 <= data.size) {
                    for (index in 0 until count) {
                        val position = 8 + index * 4
                        val offset = int32(data, position).toLong() + delta
                        if (offset !in 0..0xffffffffL) return
                        writeInt32(data, position, offset.toInt())
                    }
                }
            } else if (type == "co64" && data.size >= 8) {
                val count = int32(data, 4)
                if (count >= 0 && 8L + count * 8L <= data.size) {
                    for (index in 0 until count) {
                        val position = 8 + index * 8
                        val offset = long64(data, position) + delta
                        if (offset < 0) return
                        writeLong64(data, position, offset)
                    }
                }
            }
            children?.forEach { it.adjustChunkOffsets(delta) }
        }

        // ©lyr 为歌词原子；此处不把它列入解析时的容器类型，避免历史文件中格式异常的
        // ©lyr 让整个 atom 树解析失败（进而导致封面/标题写入整体失败），按整块丢弃重建即可
        private fun replaceItems(title: String?, artist: String?, album: String?, cover: ByteArray?, lyrics: String?) {
            val mp4Cover = cover?.let { toMp4Cover(it) }
            val kept = children.orEmpty().filterNot {
                (title != null && it.type == "©nam") ||
                    (artist != null && it.type == "©ART") ||
                    (album != null && it.type == "©alb") ||
                    (lyrics != null && it.type == "©lyr") ||
                    (mp4Cover != null && it.type == "covr")
            }.toMutableList()
            if (title != null) kept.add(dataAtom("©nam", title.toByteArray()))
            if (artist != null) kept.add(dataAtom("©ART", artist.toByteArray()))
            if (album != null) kept.add(dataAtom("©alb", album.toByteArray()))
            if (lyrics != null) kept.add(dataAtom("©lyr", lyrics.toByteArray()))
            if (mp4Cover != null) kept.add(dataAtom("covr", mp4Cover.first, mp4Cover.second))
            children?.clear(); children?.addAll(kept)
        }

        companion object {
            fun parseAll(bytes: ByteArray): MutableList<Mp4Atom>? {
                val result = mutableListOf<Mp4Atom>(); var p = 0
                while (p + 8 <= bytes.size) { val atom = parse(bytes, p, bytes.size, p) ?: return null; result += atom; val size = int32(bytes, p); if (size < 8) return null; p += size }
                return if (p == bytes.size) result else null
            }
            private fun parse(bytes: ByteArray, start: Int, end: Int, offset: Int): Mp4Atom? {
                if (start + 8 > end) return null
                val size = int32(bytes, start); if (size < 8 || start + size > end) return null
                val type = String(bytes, start + 4, 4, StandardCharsets.ISO_8859_1); val payloadStart = start + 8; val payloadEnd = start + size
                // trak/mdia/minf/stbl 按容器解析，调整 stco/co64 时才能遍历到内部的 chunk 偏移
                val container = type == "moov" || type == "trak" || type == "mdia" || type == "minf" || type == "stbl" ||
                    type == "udta" || type == "meta" || type == "ilst" || type == "©nam" || type == "©ART" || type == "©alb" || type == "covr"
                if (!container) return Mp4Atom(type, bytes.copyOfRange(payloadStart, payloadEnd), null, offset)
                val head = if (type == "meta") 4 else 0; val children = mutableListOf<Mp4Atom>(); var p = payloadStart + head
                while (p + 8 <= payloadEnd) { val child = parse(bytes, p, payloadEnd, p) ?: return null; children += child; val childSize = int32(bytes, p); if (childSize < 8) return null; p += childSize }
                if (p != payloadEnd) return null
                return Mp4Atom(type, bytes.copyOfRange(payloadStart, payloadStart + head), children, offset)
            }
            private fun metaAtom(title: String?, artist: String?, album: String?, cover: ByteArray?, lyrics: String?) =
                Mp4Atom("meta", byteArrayOf(0, 0, 0, 0), mutableListOf(hdlrAtom(), ilstAtom(title, artist, album, cover, lyrics)))

            // meta 需要 hdlr（handler_type=mdir）才被识别为 iTunes 风格元数据
            private fun hdlrAtom(): Mp4Atom {
                val data = ByteArrayOutputStream()
                data.write(byteArrayOf(0, 0, 0, 0))
                data.write(byteArrayOf(0, 0, 0, 0))
                data.write("mdir".toByteArray(StandardCharsets.ISO_8859_1))
                data.write(ByteArray(12))
                data.write(0)
                return Mp4Atom("hdlr", data.toByteArray(), null)
            }
            private fun ilstAtom(title: String?, artist: String?, album: String?, cover: ByteArray?, lyrics: String?) = Mp4Atom("ilst", ByteArray(0), buildList {
                if (title != null) add(dataAtom("©nam", title.toByteArray()))
                if (artist != null) add(dataAtom("©ART", artist.toByteArray()))
                if (album != null) add(dataAtom("©alb", album.toByteArray()))
                if (lyrics != null) add(dataAtom("©lyr", lyrics.toByteArray()))
                cover?.let { toMp4Cover(it) }?.let { add(dataAtom("covr", it.first, it.second)) }
            }.toMutableList())
            // data atom 布局：type(4字节，1=文本/13=JPEG/14=PNG) + locale(4字节全0) + 数据
            private fun dataAtom(type: String, value: ByteArray, kind: Int = 1) =
                Mp4Atom(type, ByteArray(0), mutableListOf(Mp4Atom("data", intBytes(kind) + byteArrayOf(0, 0, 0, 0) + value, null)))
        }
    }

    // endPage 为包结束所在的原页面序号：改写只动标签包的内容，页面归属须原样保留
    private data class OggPacket(var data: ByteArray, val granulePosition: Long, val endPage: Int)

    private data class OggPage(
        val headerType: Int,
        val granulePosition: Long,
        val serial: Int,
        val sequence: Int,
        val segmentCount: Int,
    )

    private data class OggFile(
        val packets: MutableList<OggPacket>,
        val serial: Int,
        val firstSequence: Int,
        val firstHeaderType: Int,
    ) {
        companion object {
            fun parse(bytes: ByteArray): OggFile? {
                val pages = mutableListOf<OggPage>()
                val packets = mutableListOf<OggPacket>()
                val packet = ByteArrayOutputStream()
                var p = 0
                var serial: Int? = null
                var expectedSequence: Long? = null
                while (p + 27 <= bytes.size) {
                    if (String(bytes, p, 4, StandardCharsets.US_ASCII) != "OggS" || bytes[p + 4].toInt() != 0) return null
                    val headerType = bytes[p + 5].toInt() and 0xff
                    val granule = longLE(bytes, p + 6)
                    val pageSerial = intLE(bytes, p + 14)
                    val sequence = intLE(bytes, p + 18)
                    val segmentCount = bytes[p + 26].toInt() and 0xff
                    val lacingEnd = p + 27 + segmentCount
                    if (lacingEnd > bytes.size) return null
                    val bodyLength = (0 until segmentCount).sumOf { bytes[p + 27 + it].toInt() and 0xff }
                    val pageEnd = lacingEnd + bodyLength
                    if (pageEnd > bytes.size || (headerType and 1 != 0) != (packet.size() != 0)) return null
                    if (serial == null) serial = pageSerial
                    if (serial != pageSerial || (expectedSequence != null && expectedSequence != sequence.toLong())) return null
                    expectedSequence = sequence.toLong() + 1
                    pages += OggPage(headerType, granule, pageSerial, sequence, segmentCount)
                    var bodyOffset = lacingEnd
                    for (index in 0 until segmentCount) {
                        val length = bytes[p + 27 + index].toInt() and 0xff
                        packet.write(bytes, bodyOffset, length)
                        bodyOffset += length
                        // granule 记录的是「包结束所在页面」的位置：Ogg 把页的 granule 归于
                        // 该页内最后一个完整包，标签包的 granule 因而是 0
                        if (length < 255) {
                            packets += OggPacket(packet.toByteArray(), granule, pages.lastIndex)
                            packet.reset()
                        }
                    }
                    p = pageEnd
                }
                if (p != bytes.size || packet.size() != 0 || pages.isEmpty()) return null
                return OggFile(packets, serial!!, pages.first().sequence, pages.first().headerType)
            }

            fun build(file: OggFile): ByteArray {
                val pages = mutableListOf<ByteArray>()
                var granule = 0L
                var continued = false
                var packetIndex = 0
                // 按「包 → 原页面」归属成页：标签包长度变化只改变该页的段数，
                // 不会把后续音频包吸到头包所在的页上——Ogg Opus 要求头两页 granule 为 0
                while (packetIndex < file.packets.size) {
                    val endPage = file.packets[packetIndex].endPage
                    val group = mutableListOf<OggPacket>()
                    while (packetIndex < file.packets.size && file.packets[packetIndex].endPage == endPage) {
                        group += file.packets[packetIndex]
                        packetIndex++
                    }
                    val units = lacingOf(group)
                    var offset = 0
                    while (offset < units.size) {
                        val count = minOf(MAX_SEGMENTS, units.size - offset)
                        val lacing = units.subList(offset, offset + count)
                        val completed = lacing.lastOrNull { it.packet >= 0 }
                        val pageGranule = if (completed != null) group[completed.packet].granulePosition else granule
                        val headerType = (if (continued) 1 else 0) or
                            (if (pages.isEmpty() && file.firstHeaderType and 2 != 0) 2 else 0)
                        pages += buildPage(headerType, pageGranule, file.serial, file.firstSequence + pages.size, lacing)
                        // 末段不是包末即包跨页，下一页须标记续包
                        continued = lacing.last().packet < 0
                        granule = pageGranule
                        offset += count
                    }
                }
                if (pages.isNotEmpty()) {
                    val last = pages.last()
                    last[5] = (last[5].toInt() and 0xff or 4).toByte()
                    patchCrc(last)
                }
                return pages.fold(ByteArrayOutputStream()) { out, page -> out.apply { write(page) } }.toByteArray()
            }

            // 段表：每 255 字节一段；包长度为 255 的整数倍（含 0）时补一个空段标记包结束
            private fun lacingOf(packets: List<OggPacket>): List<LacingUnit> {
                val units = mutableListOf<LacingUnit>()
                packets.forEachIndexed { index, packet ->
                    var offset = 0
                    while (offset < packet.data.size) {
                        val length = minOf(255, packet.data.size - offset)
                        val endsPacket = offset + length == packet.data.size && length < 255
                        units += LacingUnit(packet.data.copyOfRange(offset, offset + length), if (endsPacket) index else -1)
                        offset += length
                    }
                    if (packet.data.isEmpty() || packet.data.size % 255 == 0) units += LacingUnit(ByteArray(0), index)
                }
                return units
            }

            private fun buildPage(
                headerType: Int,
                granule: Long,
                serial: Int,
                sequence: Int,
                lacing: List<LacingUnit>,
            ): ByteArray {
                val page = ByteArrayOutputStream()
                page.write("OggS".toByteArray(StandardCharsets.US_ASCII)); page.write(0); page.write(headerType)
                page.write(longBytesLE(granule)); page.write(intBytesLE(serial)); page.write(intBytesLE(sequence))
                page.write(intBytesLE(0)); page.write(lacing.size)
                lacing.forEach { page.write(it.bytes.size) }
                lacing.forEach { page.write(it.bytes) }
                val result = page.toByteArray()
                patchCrc(result)
                return result
            }

            private fun patchCrc(page: ByteArray) {
                val crc = oggCrc(page)
                page[22] = crc.toByte(); page[23] = (crc shr 8).toByte()
                page[24] = (crc shr 16).toByte(); page[25] = (crc shr 24).toByte()
            }

            // 段与其所属包在成页分组内的序号；-1 表示该段不是包末段
            private data class LacingUnit(val bytes: ByteArray, val packet: Int)
        }
    }

    // 按文件头嗅探图片类型；无法识别时按 JPEG 处理（内嵌封面绝大多数为 JPEG）。
    // 封面导出到相册需据此定扩展名，故与写入端共用同一份判定
    internal fun sniffMimeType(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
        bytes.size >= 12 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    // M4A 的 covr 只支持 JPEG(13)/PNG(14)：其余格式（WEBP/HEIC/BMP 等）解码后转成 JPEG 再内嵌，
    // 否则播放器按声明的 JPEG 解码真实数据会失败导致封面空白
    private fun toMp4Cover(cover: ByteArray): Pair<ByteArray, Int>? {
        if (cover.size >= 3 && cover[0] == 0xff.toByte() && cover[1] == 0xd8.toByte() && cover[2] == 0xff.toByte()) return cover to 13
        if (cover.size >= 8 && cover.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) return cover to 14
        return try {
            val bitmap = BitmapFactory.decodeByteArray(cover, 0, cover.size) ?: return null
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) return null
            bitmap.recycle()
            out.toByteArray() to 13
        } catch (e: Exception) {
            CrashLogManager.logException("MusicMetadataWriter", "封面转 JPEG 失败", e)
            null
        }
    }

    // Ogg 页面校验和：多项式 0x04C11DB7，起始值 0、不反转、不异或。
    // 计算时 CRC 字段按零参与——跳过这 4 字节等于少做 32 次移位，算出的校验和必然无效，
    // 而 Ogg 解码器会校验每页 CRC，无效页会让整个流无法打开
    private fun oggCrc(bytes: ByteArray): Int {
        var crc = 0
        bytes.forEachIndexed { index, value ->
            val octet = if (index in CRC_FIELD_OFFSET until CRC_FIELD_OFFSET + 4) 0 else value.toInt() and 0xff
            crc = crc xor (octet shl 24)
            repeat(8) {
                crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04c11db7 else crc shl 1
            }
        }
        return crc
    }
    private fun int32(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 4).order(ByteOrder.BIG_ENDIAN).int
    private fun long64(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 8).order(ByteOrder.BIG_ENDIAN).long
    private fun writeInt32(b: ByteArray, p: Int, value: Int) { ByteBuffer.wrap(b, p, 4).order(ByteOrder.BIG_ENDIAN).putInt(value) }
    private fun writeLong64(b: ByteArray, p: Int, value: Long) { ByteBuffer.wrap(b, p, 8).order(ByteOrder.BIG_ENDIAN).putLong(value) }
    private fun intLE(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun longLE(b: ByteArray, p: Int) = ByteBuffer.wrap(b, p, 8).order(ByteOrder.LITTLE_ENDIAN).long
    private fun intBytes(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun intBytesLE(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun longBytesLE(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
    private fun ByteArray.startsWith(value: String) = size >= value.length && String(this, 0, value.length, StandardCharsets.US_ASCII) == value

    // 文件头字节的十六进制表示，写入失败日志用于判断读取到的数据是否完整
    private fun hexPrefix(bytes: ByteArray, max: Int = 16): String =
        bytes.take(max).joinToString("") { "%02x".format(it) }

    // 用中转文件替换目标：优先原子移动，文件系统不支持时退化为覆盖式移动
    private fun moveReplacing(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun ByteArray.indexOf(value: ByteArray): Int = (0..(size - value.size)).firstOrNull { copyOfRange(it, it + value.size).contentEquals(value) } ?: -1
    private fun List<Mp4Atom>.joinToByteArray(): ByteArray { val out = ByteArrayOutputStream(); forEach { out.write(it.build()) }; return out.toByteArray() }
}
