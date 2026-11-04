package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.media.MediaScannerConnection
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object MusicMetadataWriter {

    // 同一文件的读改写事务互斥：封面、歌词、改名等入口各自完成一次整文件重写，
    // 并发进入会以「最后写完的版本」覆盖先完成的那一份。元数据写入次数少且每次都要搬运音频躯干，
    // 全局串行不会让不同文件的写入互相等待成瓶颈，却能让同一音频的多路写入不再互相吞改。
    private val writeMutex = Mutex()

    // 流式复制音频躯干时的读缓冲大小
    private const val STREAM_BUFFER_SIZE = 64 * 1024

    // ID3v2 标签头长度
    private const val ID3_HEADER_BYTES = 10

    // content URI 重写的中转文件前缀，与缓存台账的临时文件回收共用同一份命名约定
    private const val METADATA_TEMP_PREFIX = "metadata"

    suspend fun writeCover(context: Context, track: MusicTrack, coverBytes: ByteArray): Boolean =
        writeToTrack(context, track) { source -> plan(source, TrackTagFields.EMPTY, coverBytes, removeCover = false) }

    /**
     * 一次写入整批字段与封面，供元数据编辑页保存使用。
     *
     * 缺失字段表示不改该项，由容器解析保留文件中的既有值；封面另以 [CoverUpdate] 表达三态
     * （不改 / 写入 / 删除），使「移除封面」能与「本次未涉及封面」区分开。一次调用只重写一遍文件 ——
     * 各容器的标签重建都要搬运音频躯干，逐字段分别写入会把同一份音频复制多遍。
     */
    suspend fun writeFields(
        context: Context,
        track: MusicTrack,
        fields: TrackTagFields,
        cover: CoverUpdate,
    ): Boolean = writeToTrack(context, track) { source ->
        when (cover) {
            CoverUpdate.Keep -> plan(source, fields, null, removeCover = false)
            is CoverUpdate.Set -> plan(source, fields, cover.bytes, removeCover = false)
            CoverUpdate.Remove -> plan(source, fields, null, removeCover = true)
        }
    }

    // 一次性写入标题/艺术家/封面：本地文件走文件路径重建，在线缓存歌走 content URI 就地重写
    suspend fun writeMetadataToSource(
        context: Context,
        track: MusicTrack,
        title: String,
        artist: String,
        cover: ByteArray?,
        lyrics: String? = null,
    ): Boolean = writeToTrack(context, track) { source ->
        plan(
            source,
            TrackTagFields.build {
                put(TagField.TITLE, title)
                put(TagField.ARTIST, artist)
                put(TagField.LYRICS, lyrics)
            },
            cover,
        )
    }

    /**
     * 把歌词文本内嵌进音频文件：本地文件走文件路径重建，在线缓存歌走 content URI 就地重写，
     * 纯在线流（无本地文件）无可写目标而跳过。
     * 内嵌的是增强 LRC 文本，与歌词缓存文件同源，可被内嵌歌词读取器原样解析回来。
     */
    suspend fun writeLyricsToSource(context: Context, track: MusicTrack, lyrics: String): Boolean =
        writeToTrack(context, track) { source ->
            plan(source, TrackTagFields.build { put(TagField.LYRICS, lyrics) }, null)
        }

    // 按曲目的源形态选择写入通道：有本地路径即写文件，在线缓存歌经 content URI 回写
    private suspend fun writeToTrack(
        context: Context,
        track: MusicTrack,
        plan: (TagSource) -> RewritePlan?,
    ): Boolean = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            if (track.path.isNotBlank()) write(context, track.path, plan)
            else rewriteByUri(context, track.audioUri, plan)
        }
    }

    // 写本地文件：同目录中转文件 + 原子替换，音频躯干在写出过程中按区间搬运
    private fun write(context: Context, path: String, plan: (TagSource) -> RewritePlan?): Boolean {
        if (path.isBlank()) return false
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

    // 就地重写 content URI 音频文件：content URI 无寻址写能力，先按方案把结果流式落到应用私有中转文件，
    // 再整体回写目标，全程不把文件读进堆；非 content 协议不可写时返回 false。
    // 中转文件放在 filesDir 而非 cacheDir：清理缓存会把 cacheDir 整目录删掉，正好删到正在回写的中转文件。
    private fun rewriteByUri(context: Context, uriString: String, plan: (TagSource) -> RewritePlan?): Boolean {
        if (uriString.isBlank()) return false
        val uri = uriString.toUri()
        if (uri.scheme != "content") return false
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
            val temporary = createMetadataTemp(context)
            var keepOnFailure = false
            try {
                FileOutputStream(temporary).use { output -> writeRewrite(rewrite, source, output) }
                val target = context.contentResolver.openOutputStream(uri, "wt")
                if (target == null) {
                    CrashLogManager.logException("MusicMetadataWriter", "经 content URI 写入元数据失败: 无法打开输出流, uri=$uriString")
                    return false
                }
                try {
                    target.use { output -> temporary.inputStream().use { it.copyTo(output, STREAM_BUFFER_SIZE) } }
                } catch (e: Throwable) {
                    // 目标以 wt 打开时已被截断，完整的新内容只剩这份副本：保留它并写清位置，
                    // 否则失败一次就同时失去原文件与恢复来源
                    keepOnFailure = true
                    CrashLogManager.logException(
                        "MusicMetadataWriter",
                        "经 content URI 回写被中断，恢复副本保留于 ${temporary.absolutePath}, uri=$uriString",
                        e,
                    )
                    return false
                }
            } finally {
                // 目标未被截断或回写成功时删除中转；目标已截断的失败路径保留副本
                if (!keepOnFailure && temporary.exists()) temporary.delete()
            }
            true
        } catch (e: Throwable) {
            CrashLogManager.logException("MusicMetadataWriter", "经 content URI 写入音频元数据失败", e)
            false
        }
    }

    // 元数据中转文件落在应用私有目录：缓存清理不覆盖这里，失败时也能作为恢复副本保留
    private fun createMetadataTemp(context: Context): File =
        File.createTempFile(METADATA_TEMP_PREFIX, ".tmp", context.filesDir)

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
    ): Boolean = writeToTrack(context, track) { source ->
        plan(source, TrackTagFields.build { put(TagField.TITLE, title); put(TagField.ARTIST, artist) }, null)
    }

    // 将专辑名写回音频文件标签，保留原有标题/艺术家/封面
    suspend fun writeAlbum(
        context: Context,
        track: MusicTrack,
        album: String,
    ): Boolean = writeToTrack(context, track) { source ->
        plan(source, TrackTagFields.build { put(TagField.ALBUM, album) }, null)
    }

    /**
     * 重写方案的分派：按容器魔数识别（扩展名不参与判定，改名或加挂 ID3 的文件同样能识别）。
     *
     * 头部窗口足以定位标签的容器（MP3/FLAC/M4A）走窗口解析，音频区不进入内存；
     * 需要遍历全文才能定位标签的容器（WAV 的块表、Ogg 的页序列）走整文件解析，
     * 且受整文件解析上限约束。识别不出容器即不重写。
     */
    private fun plan(
        source: TagSource,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean = false,
    ): RewritePlan? {
        val head = source.readAt(0, SNIFF_WINDOW_BYTES) ?: return null
        return when {
            isMp3(head) -> mp3Plan(source, head, fields, cover, removeCover)
            isFlac(head) -> FlacTagPlan.plan(source, fields, cover, removeCover)
            isMp4(head) -> Mp4TagPlan.plan(source, fields, cover, removeCover)
            isOgg(head) -> OggTagPlan.plan(source, fields, cover, removeCover)
            LosslessContainerTags.matches(head) -> losslessPlan(source, fields, cover, removeCover)
            else -> null
        }
    }

    // MP3：解析窗口取 ID3v2 标签区（帧不越过标签末尾），音频帧按标签结束偏移整段搬运
    private fun mp3Plan(
        source: TagSource,
        head: ByteArray,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean,
    ): RewritePlan? {
        if (!head.startsWith("ID3") || head.size < ID3_HEADER_BYTES) {
            return writeMp3(head, fields, cover, removeCover)?.toPlan(source)
        }
        val tagEnd = ID3_HEADER_BYTES + Id3v2Tag.syncsafe(head, 6)
        val window = readParseWindow(source, 0L, tagEnd.toLong()) ?: return null
        return writeMp3(window, fields, cover, removeCover)?.toPlan(source)
    }

    /**
     * WAV（RIFF）：标签布局由 LosslessContainerTags
     * 按块表计算，返回头部字面字节 + 音频体区间 + 尾部字面字节，
     * 定位只读块头与标签区，音频体按区间搬运。
     */
    private fun losslessPlan(
        source: TagSource,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean,
    ): RewritePlan? {
        val rewrite = LosslessContainerTags.write(source, fields, cover, removeCover) ?: return null
        return RewritePlan(rewrite.head, rewrite.bodyStart, rewrite.bodyEnd, rewrite.tail)
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
    ): ByteArray? = writeMetadataBytes(
        bytes,
        TrackTagFields.build {
            put(TagField.TITLE, title)
            put(TagField.ARTIST, artist)
            put(TagField.ALBUM, album)
            put(TagField.LYRICS, lyrics)
        },
        cover,
    )

    internal fun writeMetadataBytes(
        bytes: ByteArray,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean = false,
    ): ByteArray? = plan(ByteArrayTagSource(bytes), fields, cover, removeCover)?.materialize(bytes)

    /**
     * 按文件源重写并返回结果字节（测试入口）：与线上写盘共用同一套方案与区间搬运实现，
     * 使「定位读取 + 躯干搬运」的偏移正确性可在 JVM 上直接验证 —— 字节入口覆盖不到这一段
     */
    internal fun rewriteFileBytes(
        file: File,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean = false,
    ): ByteArray? {
        val source = FileTagSource(file)
        val rewrite = plan(source, fields, cover, removeCover) ?: return null
        val out = ByteArrayOutputStream()
        writeRewrite(rewrite, source, out)
        return out.toByteArray()
    }

    // 兼容入口：以标题/艺术家/专辑/封面/歌词为字段集合的快捷形式
    internal fun rewriteFileBytes(
        file: File,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): ByteArray? = rewriteFileBytes(
        file,
        TrackTagFields.build {
            put(TagField.TITLE, title)
            put(TagField.ARTIST, artist)
            put(TagField.ALBUM, album)
            put(TagField.LYRICS, lyrics)
        },
        cover,
    )

    // 容器标签布局统一由 LosslessContainerTags 计算，返回的头部字面字节 + 音频体区间 +
    // 尾部字面字节直接落到流式写入分支
    private fun isMp3(bytes: ByteArray) =
        bytes.startsWith("ID3") || (bytes.size >= 2 && bytes[0].toInt() and 0xff == 0xff && bytes[1].toInt() and 0xe0 == 0xe0)

    private fun isMp4(bytes: ByteArray) = bytes.size >= 12 && String(bytes, 4, 4, StandardCharsets.US_ASCII) == "ftyp"
    private fun isFlac(bytes: ByteArray) = bytes.startsWith("fLaC")

    // Ogg 容器：Opus 与 Vorbis 的注释包位置与结构由 writeOggComments 按包头分辨。
    // 只认 OggS 而不认 OpusHead——Vorbis 的包头不含 "OpusHead"
    private fun isOgg(bytes: ByteArray) = bytes.startsWith("OggS")

    // MP3：既有标签区作为帧改写底本，音频帧按标签结束偏移整段搬运。
    // 帧的编解码与保帧规则统一由 Id3v2Tag 承担，与 WAV 内嵌的 ID3 块走同一实现
    private fun writeMp3(
        source: ByteArray,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean,
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
        val outputVersion = if (hasId3 && version == 3) 3 else 4
        val existing = if (hasId3) source.copyOfRange(0, tagEnd) else null
        // 删除封面时标签仍须落盘（哪怕帧区为空），否则原有 APIC 帧会被整段保留下来
        val frames = Id3v2Tag.replaceFrames(existing, fields, cover, removeCover, outputVersion)
            ?: if (removeCover) ByteArray(0) else return null
        // 头部为重建的 ID3 标签，音频躯干按原标签结束偏移流式复制，避免整曲二次驻留内存。
        // 扩展头不随帧区保留：新标签不再声明它，故一并省去，避免头尾声明与实际结构不符
        val tag = Id3v2Tag.buildTag(frames, outputVersion)
        return WriteResult.HeadAndTail(tag, tagEnd.toLong())
    }

    // 按文件头嗅探图片类型；无法识别时按 JPEG 处理（内嵌封面绝大多数为 JPEG）。
    // 封面导出到相册需据此定扩展名，故与写入端共用同一份判定
    internal fun sniffMimeType(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
        bytes.size >= 12 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

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
}
