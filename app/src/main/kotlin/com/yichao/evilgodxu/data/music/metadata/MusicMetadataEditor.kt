package com.yichao.evilgodxu.data.music.metadata

import android.content.Context
import android.provider.MediaStore
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 音频文件内嵌标签的完整读取入口，供元数据编辑页回填表单。
 *
 * 与写入端 [MusicMetadataWriter] 覆盖同一批容器与同一批字段：平台元数据提取器只能给出部分字段
 * （WAV/AIFF/DSDIFF/DSF/APE 的文本标签、各容器的内嵌歌词都不在其支持范围内），表单若只回填曲目
 * 内存态，用户会在这些容器上看到空字段并把空值写回去，等于抹掉文件里的既有标签。
 *
 * 读取只走定点窗口，不整文件驻留：字段本身很小，但封面帧可达数 MB。
 */
internal object MusicMetadataEditor {

    // 容器判定前缀：各容器魔数与 MP4 的 ftyp 都在最前 16 字节内
    private const val CONTAINER_PROBE_BYTES = 16

    // 头部窗口：FLAC/OGG 的注释块与 M4A 的 moov 盒都在文件头部附近
    private const val HEADER_CAP = 512 * 1024
    // 尾部窗口：标签位于音频之后的容器（WAV/DSF/APE/后置 AIFF）靠它定位
    private const val TAIL_CAP = 2 * 1024 * 1024
    // MP3 的 ID3v2 标签按声明长度精确读取：标签可含大封面帧
    private const val MAX_MP3_TAG = 32 * 1024 * 1024
    private const val ID3_HEADER_BYTES = 10

    // 歌词字段的候选键：各写入端与第三方工具落点不一，按序取首个非空值
    private val LYRICS_KEYS = listOf("LYRICS", "SYNCEDLYRICS", "UNSYNCEDLYRICS")

    /**
     * 表单回填内容。字段为 null 表示该容器/该文件未写入该项，
     * 与「写入了空字符串」区分：未写入的项在保存时不参与重写，既有值原样保留。
     */
    class EditableTags(
        val title: String?,
        val artist: String?,
        val album: String?,
        val lyrics: String?,
        /**
         * 内嵌歌词的写入时刻（毫秒），取音频文件的最后修改时间。
         *
         * 歌词有「缓存文件」与「文件内嵌」两份副本，两者并非总在同一次事务里落盘
         * （内嵌写入可能失败），故调用方需要这个时刻与缓存文件的修改时间择优，
         * 否则会拿旧的内嵌歌词盖掉刚刷新的缓存歌词。无内嵌歌词时为 0
         */
        val lyricsModifiedMs: Long = 0L,
    )

    /**
     * 读取音频文件当前的内嵌标签；文件不可读、容器不支持或无标签时返回 null。
     * 调用方须在 IO 线程调用，本方法不做线程切换
     */
    fun read(context: Context, track: MusicTrack): EditableTags? {
        val prefix = LocalAudioSource.read(context, track.path, track.audioUri, 0L, CONTAINER_PROBE_BYTES)
            ?: return null
        val text = readTextTags(context, track, prefix)
        val lyrics = readLyrics(context, track, prefix)
        return EditableTags(
            title = text?.title,
            artist = text?.artist,
            album = text?.album,
            lyrics = lyrics,
            // 标签写在文件内，内嵌歌词的落盘时刻即文件的修改时间
            lyricsModifiedMs = if (lyrics != null) modifiedTimeMs(context, track) else 0L,
        )
    }

    // 音频文件的最后修改时间：本地路径取文件属性，content URI 经解析器查询。
    // 取不到时返回 0，由调用方按「无法比较」处理
    private fun modifiedTimeMs(context: Context, track: MusicTrack): Long = runCatching {
        if (track.path.isNotBlank()) {
            File(track.path).lastModified()
        } else {
            queryLastModified(context, track)
        }
    }.getOrDefault(0L)

    // content URI 曲目经 MediaStore 查询修改时间；非媒体库条目查询不到时返回 0
    private fun queryLastModified(context: Context, track: MusicTrack): Long = runCatching {
        context.contentResolver.query(
            track.audioUri.toUri(),
            arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) * 1000L else 0L } ?: 0L
    }.getOrDefault(0L)

    /**
     * 读取内嵌封面原图字节；无内嵌封面时返回 null。
     *
     * 封面与文本标签分开读取：封面可达数 MB，页面在表单就绪后才按需取回预览。
     * 为 suspend：FLAC/M4A 等容器的封面取回走内嵌封面读取器，其内部按容器派发 IO 调度
     */
    suspend fun readCover(context: Context, track: MusicTrack): ByteArray? {
        val prefix = LocalAudioSource.read(context, track.path, track.audioUri, 0L, CONTAINER_PROBE_BYTES)
            ?: return null
        return when {
            prefix.startsWith("ID3") -> readId3Apic(context, track)
            LosslessContainerTags.matches(prefix) -> withContext(Dispatchers.IO) {
                readFromWindows(context, track, prefix) { header, tail, tailOffset, readAt ->
                    LosslessContainerTags.readCover(header, tail, tailOffset, readAt)
                }
            }
            // FLAC 的 PICTURE 块、M4A 的 covr 原子与其余容器由内嵌封面读取器统一取回
            else -> EmbeddedCoverReader.readRawBytes(context, track.audioUri, track.path)
        }
    }

    // 文本标签：MP3 先按 ID3 声明长度取标签区，其余容器按头部窗口 + 尾部窗口的容器规则定位
    private fun readTextTags(
        context: Context,
        track: MusicTrack,
        prefix: ByteArray,
    ): LosslessContainerTags.TextTag? = when {
        // MP3/ID3 标签不在 LosslessContainerTags 的容器范围内，单独按文本帧解析取回
        prefix.startsWith("ID3") -> readId3Text(context, track, "TIT2", "TPE1", "TALB")
            ?.let { LosslessContainerTags.TextTag(it[0], it[1], it[2]) }
            ?.takeIf { it.title != null || it.artist != null || it.album != null }
        LosslessContainerTags.matches(prefix) -> readFromWindows(context, track, prefix) { header, tail, tailOffset, readAt ->
            LosslessContainerTags.readText(header, tail, tailOffset, readAt)
        }
        // FLAC 的 VORBIS_COMMENT、Ogg 的注释包与 M4A 的 ilst 都在头部窗口内，按各自结构解析
        else -> readHeadTaggedText(context, track, prefix)
    }

    // FLAC/Ogg 的 Vorbis 注释与 M4A 的 ilst 原子：头窗内的结构解析
    private fun readHeadTaggedText(
        context: Context,
        track: MusicTrack,
        prefix: ByteArray,
    ): LosslessContainerTags.TextTag? {
        if (!prefix.startsWith("fLaC") && !prefix.startsWith("OggS") && !prefix.startsWith("ftyp", 4)) return null
        val header = LocalAudioSource.read(context, track.path, track.audioUri, 0L, HEADER_CAP) ?: return null
        if (prefix.startsWith("ftyp", 4)) return readMp4Text(header)
        val comments = if (prefix.startsWith("fLaC")) flacComments(header) else oggComments(header)
        if (comments == null) return null
        val title = fieldValue(comments, listOf("TITLE"))
        val artist = fieldValue(comments, listOf("ARTIST"))
        val album = fieldValue(comments, listOf("ALBUM"))
        if (title == null && artist == null && album == null) return null
        return LosslessContainerTags.TextTag(title, artist, album)
    }

    // Vorbis 注释中的字段取值：键名大小写不敏感，按给定键序取首个非空值
    private fun fieldValue(fields: List<String>, keys: List<String>): String? = keys.firstNotNullOfOrNull { key ->
        fields.firstOrNull { it.substringBefore('=').equals(key, ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotBlank() }
    }

    // 歌词字段在大写键中的取值入口：容器不同，注释区定位方式不同
    private fun readVorbisField(
        header: ByteArray,
        keys: List<String>,
        comments: (ByteArray) -> List<String>?,
    ): String? = comments(header)?.let { fieldValue(it, keys) }

    private fun readLyrics(context: Context, track: MusicTrack, prefix: ByteArray): String? = when {
        // USLT 不是文本帧，不能走 readTextFrame 的 TXXX 之外解码路径，单独解析
        prefix.startsWith("ID3") -> readId3Tag(context, track)?.let { Id3v2Tag.readUslt(it, 0) }
            ?.takeIf { it.isNotBlank() }
        LosslessContainerTags.matches(prefix) -> readFromWindows(context, track, prefix) { header, tail, tailOffset, readAt ->
            LosslessContainerTags.readLyrics(header, tail, tailOffset, readAt)
        }
        else -> {
            // 先判容器再读窗口：其余容器没有可取回歌词的落点，不必为此多读一个头窗
            if (!prefix.startsWith("fLaC") && !prefix.startsWith("OggS") && !prefix.startsWith("ftyp", 4)) return null
            val header = LocalAudioSource.read(context, track.path, track.audioUri, 0L, HEADER_CAP) ?: return null
            when {
                prefix.startsWith("fLaC") -> readVorbisField(header, LYRICS_KEYS) { flacComments(it) }
                prefix.startsWith("OggS") -> readVorbisField(header, LYRICS_KEYS) { oggComments(it) }
                else -> MusicEmbeddedLyricReader.extractLyrics(header)
            }
        }
    }

    // 头部窗口 + 尾部窗口 + 窗口外定点读取：无损容器（WAV/AIFF/DFF/DSF/APE）的标签定位统一走这套窗口，
    // 解析规则仍由 LosslessContainerTags 持有
    private fun <T> readFromWindows(
        context: Context,
        track: MusicTrack,
        prefix: ByteArray,
        parse: (ByteArray, ByteArray?, Long, ((Long, Int) -> ByteArray?)?) -> T?,
    ): T? {
        val header = LocalAudioSource.read(context, track.path, track.audioUri, 0L, HEADER_CAP) ?: return null
        // 标签在头部的容器不读尾窗：平白多读数据
        if (!LosslessContainerTags.usesTrailingTag(prefix)) {
            return parse(header, null, 0L, null)
        }
        val tail = LocalAudioSource.tail(context, track.path, track.audioUri, TAIL_CAP)
        return parse(header, tail?.first, tail?.second ?: 0L) { offset, count ->
            LocalAudioSource.read(context, track.path, track.audioUri, offset, count)
        }
    }

    // ID3v2 标签区：按标签头声明的长度精确读取（含封面帧），超出上限即放弃。
    // 帧标识不区分大小写，同一标识出现多次时取首个非空值
    private fun readId3Text(context: Context, track: MusicTrack, vararg frameIds: String): List<String?>? {
        val tag = readId3Tag(context, track) ?: return null
        return frameIds.map { frameId ->
            Id3v2Tag.readTextFrame(tag, 0, frameId)
        }
    }

    private fun readId3Apic(context: Context, track: MusicTrack): ByteArray? =
        readId3Tag(context, track)?.let { Id3v2Tag.readApic(it, 0) }

    private fun readId3Tag(context: Context, track: MusicTrack): ByteArray? {
        val prefix = LocalAudioSource.read(context, track.path, track.audioUri, 0L, ID3_HEADER_BYTES) ?: return null
        if (!prefix.startsWith("ID3") || prefix.size < ID3_HEADER_BYTES) return null
        val tagSize = Id3v2Tag.syncsafe(prefix, 6)
        if (tagSize <= 0) return null
        val total = (tagSize + ID3_HEADER_BYTES).coerceAtMost(MAX_MP3_TAG)
        return LocalAudioSource.read(context, track.path, track.audioUri, 0L, total)
    }

    private fun flacComments(bytes: ByteArray): List<String>? {
        var p = 4
        while (p + 4 <= bytes.size) {
            val type = bytes[p].toInt() and 0x7f
            val last = bytes[p].toInt() and 0x80 != 0
            val length = (bytes[p + 1].toInt() and 0xff shl 16) or
                (bytes[p + 2].toInt() and 0xff shl 8) or (bytes[p + 3].toInt() and 0xff)
            p += 4
            if (p + length > bytes.size) return null
            if (type == 4) return parseVorbisComments(bytes, p, length)
            p += length
            if (last) break
        }
        return null
    }

    private fun oggComments(bytes: ByteArray): List<String>? {
        val opus = bytes.indexOfAscii("OpusTags")
        val vorbis = bytes.indexOfAscii("\u0003vorbis")
        val (start, headerLength) = when {
            opus >= 0 -> opus to "OpusTags".length
            vorbis >= 0 -> vorbis to "\u0003vorbis".length
            else -> return null
        }
        return parseVorbisComments(bytes, start + headerLength, bytes.size - start - headerLength)
    }

    // Vorbis 注释结构：LE 长度的 vendor + 字段数 + 「KEY=value」字段
    private fun parseVorbisComments(data: ByteArray, offset: Int, length: Int): List<String>? {
        var p = offset
        val end = (offset + length).coerceAtMost(data.size)
        if (p + 4 > end) return null
        val vendorLength = intLE(data, p)
        if (vendorLength < 0 || p + 4 + vendorLength > end) return null
        p += 4 + vendorLength
        if (p + 4 > end) return null
        val count = intLE(data, p)
        p += 4
        val fields = mutableListOf<String>()
        repeat(count.coerceAtLeast(0)) {
            if (p + 4 > end) return@repeat
            val valueLength = intLE(data, p)
            p += 4
            if (valueLength < 0 || p + valueLength > end) return@repeat
            fields += String(data, p, valueLength, StandardCharsets.UTF_8)
            p += valueLength
        }
        return fields
    }

    // M4A：递归遍历 moov/udta/meta/ilst，取 ©nam/©ART/©alb 三个文本原子
    private fun readMp4Text(bytes: ByteArray): LosslessContainerTags.TextTag? {
        val title = readMp4TextAtom(bytes, 0, bytes.size, false, "©nam", "©NAM")
        val artist = readMp4TextAtom(bytes, 0, bytes.size, false, "©ART", "©art")
        val album = readMp4TextAtom(bytes, 0, bytes.size, false, "©alb", "©ALB")
        if (title == null && artist == null && album == null) return null
        return LosslessContainerTags.TextTag(title, artist, album)
    }

    private fun readMp4TextAtom(
        bytes: ByteArray,
        start: Int,
        end: Int,
        isMeta: Boolean,
        vararg types: String,
    ): String? {
        var p = start + if (isMeta) 4 else 0
        while (p + 8 <= end) {
            val size = int32BE(bytes, p)
            if (size < 8 || p + size > end) return null
            val type = String(bytes, p + 4, 4, StandardCharsets.ISO_8859_1)
            when {
                type in types -> decodeMp4DataAtom(bytes, p + 8, p + size)?.let { return it }
                type == "moov" || type == "udta" || type == "meta" || type == "ilst" ->
                    readMp4TextAtom(bytes, p + 8, p + size, type == "meta", *types)?.let { return it }
            }
            p += size
        }
        return null
    }

    // data 子原子的载荷：头 16 字节（4 类型 + 4 区域 + 8 版本标志）之后为 UTF-8 文本
    private fun decodeMp4DataAtom(bytes: ByteArray, start: Int, end: Int): String? {
        var p = start
        while (p + 8 <= end) {
            val size = int32BE(bytes, p)
            if (size < 8 || p + size > end) return null
            if (String(bytes, p + 4, 4, StandardCharsets.ISO_8859_1) == "data") {
                if (size <= 16) return null
                return String(bytes, p + 16, size - 16, StandardCharsets.UTF_8).takeIf { it.isNotBlank() }
            }
            p += size
        }
        return null
    }

    private fun ByteArray.startsWith(value: String): Boolean =
        size >= value.length && String(this, 0, value.length, StandardCharsets.US_ASCII) == value

    private fun ByteArray.startsWith(value: String, offset: Int): Boolean =
        size >= offset + value.length && String(this, offset, value.length, StandardCharsets.US_ASCII) == value

    private fun ByteArray.indexOfAscii(value: String): Int =
        (0..(size - value.length).coerceAtLeast(0)).firstOrNull { startsWith(value, it) } ?: -1

    private fun int32BE(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0xff shl 24) or (bytes[p + 1].toInt() and 0xff shl 16) or
            (bytes[p + 2].toInt() and 0xff shl 8) or (bytes[p + 3].toInt() and 0xff)

    private fun intLE(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0xff) or (bytes[p + 1].toInt() and 0xff shl 8) or
            (bytes[p + 2].toInt() and 0xff shl 16) or (bytes[p + 3].toInt() and 0xff shl 24)
}

/**
 * 元数据编辑页的数据门面：读取表单初值并把用户改动写回音频文件。
 *
 * 写入是整批一次完成 —— 各容器的标签重写都要重建容器结构，逐字段写入会把文件重写多遍，
 * 每遍都是一次全量复制。字段为 null 表示本次不改该项，由写入端保留既有值。
 */
internal object TrackMetadataEditor {

    /**
     * 读取曲目当前的内嵌标签；曲目无可读本地音频源时返回 null。
     * 不抛异常，读取失败按「无标签」处理，由页面展示空表单
     */
    suspend fun read(context: Context, track: MusicTrack): MusicMetadataEditor.EditableTags? =
        withContext(Dispatchers.IO) {
            if (!track.isLocalAudioSource) return@withContext null
            try {
                MusicMetadataEditor.read(context, track)
            } catch (e: Exception) {
                CrashLogManager.logException(
                    "TrackMetadataEditor",
                    "读取内嵌标签失败: 歌曲=${track.title} 路径=${track.path}",
                    e,
                )
                null
            }
        }

    /** 读取内嵌封面原图字节；无内嵌封面或读取失败时返回 null */
    suspend fun readCover(context: Context, track: MusicTrack): ByteArray? = withContext(Dispatchers.IO) {
        if (!track.isLocalAudioSource) return@withContext null
        try {
            MusicMetadataEditor.readCover(context, track)
        } catch (e: Exception) {
            CrashLogManager.logException(
                "TrackMetadataEditor",
                "读取内嵌封面失败: 歌曲=${track.title} 路径=${track.path}",
                e,
            )
            null
        }
    }

    /**
     * 把编辑结果写回音频文件。仅在字段非 null 时覆盖该项，留空的字段保持文件原值。
     *
     * 返回是否写入成功：容器不支持、超出解析上限或文件不可写时返回 false，由页面提示用户。
     */
    suspend fun save(
        context: Context,
        track: MusicTrack,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
        lyrics: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!track.isLocalAudioSource) return@withContext false
        try {
            MusicMetadataWriter.writeFields(context, track, title, artist, album, cover, lyrics)
        } catch (e: Exception) {
            CrashLogManager.logException(
                "TrackMetadataEditor",
                "写入内嵌标签失败: 歌曲=${track.title} 路径=${track.path}",
                e,
            )
            false
        }
    }
}
