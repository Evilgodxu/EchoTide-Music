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

    // 同文件读改写互斥，避免多路写入互相覆盖
    private val writeMutex = Mutex()

    // 流式复制音频躯干的读缓冲
    private const val STREAM_BUFFER_SIZE = 64 * 1024

    // ID3v2 标签头长度
    private const val ID3_HEADER_BYTES = 10

    // content URI 重写中转文件前缀
    private const val METADATA_TEMP_PREFIX = "metadata"

    suspend fun writeCover(context: Context, track: MusicTrack, coverBytes: ByteArray): Boolean =
        writeToTrack(context, track) { source -> plan(source, TrackTagFields.EMPTY, coverBytes, removeCover = false) }

    /** 一次写入整批字段与封面；缺项保留原值，封面三态 */
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

    /** 歌词内嵌：本地写文件，在线走 URI，纯流跳过 */
    suspend fun writeLyricsToSource(context: Context, track: MusicTrack, lyrics: String): Boolean =
        writeToTrack(context, track) { source ->
            plan(source, TrackTagFields.build { put(TagField.LYRICS, lyrics) }, null)
        }

    // 有本地路径写文件，否则回写 content URI
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

    // 写本地文件：同目录中转 + 原子替换
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
                // 替换失败时目标仍是原文件，删除中转
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

    // 原地重写 URI：先写私有中转再回写
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
                    // 目标已截断，保留副本供恢复
                    keepOnFailure = true
                    CrashLogManager.logException(
                        "MusicMetadataWriter",
                        "经 content URI 回写被中断，恢复副本保留于 ${temporary.absolutePath}, uri=$uriString",
                        e,
                    )
                    return false
                }
            } finally {
                // 成功或目标未截断时删除中转
                if (!keepOnFailure && temporary.exists()) temporary.delete()
            }
            true
        } catch (e: Throwable) {
            CrashLogManager.logException("MusicMetadataWriter", "经 content URI 写入音频元数据失败", e)
            false
        }
    }

    // 中转文件放私有目录，清缓存不波及
    private fun createMetadataTemp(context: Context): File =
        File.createTempFile(METADATA_TEMP_PREFIX, ".tmp", context.filesDir)

    // 方案落盘：头部 → 躯干区间搬运 → 尾部
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

    // 写回专辑名，保留标题/艺术家/封面
    suspend fun writeAlbum(
        context: Context,
        track: MusicTrack,
        album: String,
    ): Boolean = writeToTrack(context, track) { source ->
        plan(source, TrackTagFields.build { put(TagField.ALBUM, album) }, null)
    }

    /** 按魔数分派容器，不认扩展名；识别不出不重写 */
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

    // MP3 解析窗口取 ID3v2 区，音频帧整段搬运
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

    /** WAV/RIFF：按块表计算标签布局 */
    private fun losslessPlan(
        source: TagSource,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean,
    ): RewritePlan? {
        val rewrite = LosslessContainerTags.write(source, fields, cover, removeCover) ?: return null
        return RewritePlan(rewrite.head, rewrite.bodyStart, rewrite.bodyEnd, rewrite.tail)
    }

    /** 纯字节写入入口；空或格式不支持返回 null */
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

    /** 按文件源重写并返回结果字节，测试入口 */
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

    // 快捷重载：散字段转 TrackTagFields
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

    // 布局由无损容器模块计算
    private fun isMp3(bytes: ByteArray) =
        bytes.startsWith("ID3") || (bytes.size >= 2 && bytes[0].toInt() and 0xff == 0xff && bytes[1].toInt() and 0xe0 == 0xe0)

    private fun isMp4(bytes: ByteArray) = bytes.size >= 12 && String(bytes, 4, 4, StandardCharsets.US_ASCII) == "ftyp"
    private fun isFlac(bytes: ByteArray) = bytes.startsWith("fLaC")

    // Ogg：只认 OggS，无 OpusHead
    private fun isOgg(bytes: ByteArray) = bytes.startsWith("OggS")

    // MP3：既有标签区作底本，帧交 Id3v2Tag
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
        // 删除封面时标签仍须落盘，否则 APIC 帧会保留
        val frames = Id3v2Tag.replaceFrames(existing, fields, cover, removeCover, outputVersion)
            ?: if (removeCover) ByteArray(0) else return null
        // 重建 ID3 标签，音频躯干流式复制；扩展头不保留
        val tag = Id3v2Tag.buildTag(frames, outputVersion)
        return WriteResult.HeadAndTail(tag, tagEnd.toLong())
    }

    // 按文件头嗅探图片类型，未识别按 JPEG
    internal fun sniffMimeType(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
        bytes.size >= 12 && String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> "image/jpeg"
    }

    // 文件头十六进制，失败日志判断数据完整性
    private fun hexPrefix(bytes: ByteArray, max: Int = 16): String =
        bytes.take(max).joinToString("") { "%02x".format(it) }

    // 原子替换，不支持时退化为覆盖移动
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
