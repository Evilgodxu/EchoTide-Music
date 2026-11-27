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

/** 内嵌标签完整读取入口，供编辑页回填；只走定点窗口 */
internal object MusicMetadataEditor {

    // 容器判定前缀 16 字节
    private const val CONTAINER_PROBE_BYTES = 16

    // 头部窗口：FLAC/Ogg 注释与 M4A moov
    private const val HEADER_CAP = 512 * 1024
    // 尾部窗口，WAV 靠它定位
    private const val TAIL_CAP = 2 * 1024 * 1024
    // MP3 按声明长度精确读取，含大封面
    private const val MAX_MP3_TAG = 32 * 1024 * 1024
    private const val ID3_HEADER_BYTES = 10

    // 歌词候选键，按序取首个非空
    private val LYRICS_KEYS = listOf("LYRICS", "SYNCEDLYRICS", "UNSYNCEDLYRICS")

    // Vorbis 字段候选键，首项为写入端键
    private val VORBIS_ALIASES: Map<TagField, List<String>> = mapOf(
        TagField.TITLE to listOf("TITLE"),
        TagField.ARTIST to listOf("ARTIST"),
        TagField.ALBUM_ARTIST to listOf("ALBUMARTIST", "ALBUM ARTIST"),
        TagField.ALBUM to listOf("ALBUM"),
        TagField.DATE to listOf("DATE", "YEAR"),
        TagField.LANGUAGE to listOf("LANGUAGE"),
        TagField.GENRE to listOf("GENRE"),
        TagField.TRACK_NUMBER to listOf("TRACKNUMBER", "TRACK"),
        TagField.DISC_NUMBER to listOf("DISCNUMBER", "DISC"),
        TagField.LYRICIST to listOf("LYRICIST", "WRITER"),
        TagField.COMPOSER to listOf("COMPOSER"),
        TagField.COPYRIGHT to listOf("COPYRIGHT"),
        TagField.COMMENT to listOf("COMMENT", "DESCRIPTION"),
        TagField.RATING to listOf("RATING"),
        TagField.REPLAY_GAIN_TRACK_GAIN to listOf("REPLAYGAIN_TRACK_GAIN"),
        TagField.REPLAY_GAIN_TRACK_PEAK to listOf("REPLAYGAIN_TRACK_PEAK"),
        TagField.REPLAY_GAIN_ALBUM_GAIN to listOf("REPLAYGAIN_ALBUM_GAIN"),
        TagField.REPLAY_GAIN_ALBUM_PEAK to listOf("REPLAYGAIN_ALBUM_PEAK"),
        TagField.REPLAY_GAIN_REFERENCE_LOUDNESS to listOf("REPLAYGAIN_REFERENCE_LOUDNESS"),
    )

    /** 表单回填内容：文本字段与内嵌歌词分开 */
    class EditableTags(
        val fields: TrackTagFields,
        val lyrics: String?,
        /** 内嵌歌词写入时刻，无则 0 */
        val lyricsModifiedMs: Long = 0L,
    )

    /** 读内嵌标签；不可读或无标签返回空字段，须在 IO 线程 */
    fun read(context: Context, track: MusicTrack): EditableTags {
        val prefix = LocalAudioSource.read(context, track.path, track.audioUri, 0L, CONTAINER_PROBE_BYTES)
            ?: return EditableTags(TrackTagFields.EMPTY, null)
        val fields = readTextTags(context, track, prefix) ?: TrackTagFields.EMPTY
        val lyrics = readLyrics(context, track, prefix)
        return EditableTags(
            fields = fields,
            lyrics = lyrics,
            // 歌词落盘时刻即文件修改时间
            lyricsModifiedMs = if (lyrics != null) modifiedTimeMs(context, track) else 0L,
        )
    }

    // 文件修改时间；不可得返回 0
    private fun modifiedTimeMs(context: Context, track: MusicTrack): Long = runCatching {
        if (track.path.isNotBlank()) {
            File(track.path).lastModified()
        } else {
            queryLastModified(context, track)
        }
    }.getOrDefault(0L)

    // URI 源经 MediaStore 查修改时间
    private fun queryLastModified(context: Context, track: MusicTrack): Long = runCatching {
        context.contentResolver.query(
            track.audioUri.toUri(),
            arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) * 1000L else 0L } ?: 0L
    }.getOrDefault(0L)

    /** 读内嵌封面原图；无则 null，须在 IO 线程 */
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
            // FLAC/M4A 等由内嵌封面读取器取回
            else -> EmbeddedCoverReader.readRawBytes(context, track.audioUri, track.path)
        }
    }

    // 文本标签：MP3 取 ID3 区，WAV 走窗口
    private fun readTextTags(
        context: Context,
        track: MusicTrack,
        prefix: ByteArray,
    ): TrackTagFields? = when {
        // MP3 单独按 ID3 帧解析
        prefix.startsWith("ID3") -> readId3Tag(context, track)
            ?.let { withoutLyrics(Id3v2Tag.readFields(it, 0)) }
        LosslessContainerTags.matches(prefix) -> readFromWindows(context, track, prefix) { header, tail, tailOffset, readAt ->
            LosslessContainerTags.readFields(header, tail, tailOffset, readAt)
        }?.let { withoutLyrics(it) }
        // FLAC/Ogg 注释在头窗内，M4A 定点下钻
        else -> readHeadTaggedFields(context, track, prefix)
    }

    // 歌词不由文本字段回填，避免重复读取
    private fun withoutLyrics(fields: TrackTagFields): TrackTagFields = TrackTagFields.build {
        fields.entries().forEach { (field, value) -> if (field != TagField.LYRICS) put(field, value) }
    }

    // FLAC/Ogg 在头窗内解析；M4A 定点下钻
    private fun readHeadTaggedFields(
        context: Context,
        track: MusicTrack,
        prefix: ByteArray,
    ): TrackTagFields? = when {
        prefix.startsWith("ftyp", 4) -> readMp4Fields(context, track)
        prefix.startsWith("fLaC") || prefix.startsWith("OggS") -> {
            val header = LocalAudioSource.read(context, track.path, track.audioUri, 0L, HEADER_CAP)
                ?: return null
            val comments = if (prefix.startsWith("fLaC")) flacComments(header) else oggComments(header)
            comments?.let { readVorbisFields(it) }
        }
        else -> null
    }

    // 定点读回调，不整段读头窗
    private fun readAt(context: Context, track: MusicTrack): (Long, Int) -> ByteArray? =
        { offset, count -> LocalAudioSource.read(context, track.path, track.audioUri, offset, count) }

    // 键名大小写不敏感，按序取首个非空
    private fun readVorbisFields(comments: List<String>): TrackTagFields? {
        val result = LinkedHashMap<TagField, String>()
        VORBIS_ALIASES.forEach { (field, keys) ->
            fieldValue(comments, keys)?.let { result[field] = it }
        }
        return result.takeIf { it.isNotEmpty() }?.let { TrackTagFields.build { it.forEach { (f, v) -> put(f, v) } } }
    }

    // 键名大小写不敏感，按给定键序取首个非空值
    private fun fieldValue(fields: List<String>, keys: List<String>): String? = keys.firstNotNullOfOrNull { key ->
        fields.firstOrNull { it.substringBefore('=').equals(key, ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotBlank() }
    }

    // 歌词键取值入口，各容器定位方式不同
    private fun readVorbisField(
        header: ByteArray,
        keys: List<String>,
        comments: (ByteArray) -> List<String>?,
    ): String? = comments(header)?.let { fieldValue(it, keys) }

    private fun readLyrics(context: Context, track: MusicTrack, prefix: ByteArray): String? = when {
        // USLT 非文本帧，单独解析
        prefix.startsWith("ID3") -> readId3Tag(context, track)?.let { Id3v2Tag.readUslt(it, 0) }
            ?.takeIf { it.isNotBlank() }
        LosslessContainerTags.matches(prefix) -> readFromWindows(context, track, prefix) { header, tail, tailOffset, readAt ->
            LosslessContainerTags.readLyrics(header, tail, tailOffset, readAt)
        }
        else -> {
            // 先判容器，避免多读头窗
            if (!prefix.startsWith("fLaC") && !prefix.startsWith("OggS") && !prefix.startsWith("ftyp", 4)) return null
            when {
                // M4A 歌词可能在封面之后，定点下钻
                prefix.startsWith("ftyp", 4) -> MusicEmbeddedLyricReader.readMp4Lyrics(
                    LocalAudioSource.size(context, track.path, track.audioUri),
                    readAt(context, track),
                )

                else -> {
                    val header = LocalAudioSource.read(context, track.path, track.audioUri, 0L, HEADER_CAP)
                        ?: return null
                    if (prefix.startsWith("fLaC")) {
                        readVorbisField(header, LYRICS_KEYS) { flacComments(it) }
                    } else {
                        readVorbisField(header, LYRICS_KEYS) { oggComments(it) }
                    }
                }
            }
        }
    }

    // 头窗、尾窗与窗口外定点读取
    private fun <T> readFromWindows(
        context: Context,
        track: MusicTrack,
        prefix: ByteArray,
        parse: (ByteArray, ByteArray?, Long, ((Long, Int) -> ByteArray?)?) -> T?,
    ): T? {
        val header = LocalAudioSource.read(context, track.path, track.audioUri, 0L, HEADER_CAP) ?: return null
        // 标签在头部的容器不读尾窗
        if (!LosslessContainerTags.usesTrailingTag(prefix)) {
            return parse(header, null, 0L, null)
        }
        val tail = LocalAudioSource.tail(context, track.path, track.audioUri, TAIL_CAP)
        return parse(header, tail?.first, tail?.second ?: 0L) { offset, count ->
            LocalAudioSource.read(context, track.path, track.audioUri, offset, count)
        }
    }

    private fun readId3Apic(context: Context, track: MusicTrack): ByteArray? =
        readId3Tag(context, track)?.let { Id3v2Tag.readApic(it, 0) }

    // ID3 区按声明长度读，超上限放弃
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

    // Vorbis：vendor、字段数、KEY=value
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
        // 字段数以剩余字节为上限，避免损坏文件空转
        repeat(count.coerceAtLeast(0).coerceAtMost((end - p).coerceAtLeast(0) / 4)) {
            if (p + 4 > end) return@repeat
            val valueLength = intLE(data, p)
            p += 4
            if (valueLength < 0 || p + valueLength > end) return@repeat
            fields += String(data, p, valueLength, StandardCharsets.UTF_8)
            p += valueLength
        }
        return fields
    }

    // M4A 全由 Mp4TagReader 定点下钻
    private fun readMp4Fields(context: Context, track: MusicTrack): TrackTagFields? =
        Mp4TagReader.readFields(
            LocalAudioSource.size(context, track.path, track.audioUri),
            readAt(context, track),
        )


    private fun ByteArray.startsWith(value: String): Boolean =
        size >= value.length && String(this, 0, value.length, StandardCharsets.US_ASCII) == value

    private fun ByteArray.startsWith(value: String, offset: Int): Boolean =
        size >= offset + value.length && String(this, offset, value.length, StandardCharsets.US_ASCII) == value

    private fun ByteArray.indexOfAscii(value: String): Int =
        (0..(size - value.length).coerceAtLeast(0)).firstOrNull { startsWith(value, it) } ?: -1

    private fun intLE(bytes: ByteArray, p: Int): Int =
        (bytes[p].toInt() and 0xff) or (bytes[p + 1].toInt() and 0xff shl 8) or
            (bytes[p + 2].toInt() and 0xff shl 16) or (bytes[p + 3].toInt() and 0xff shl 24)
}

/** 编辑页数据门面：整批一次写回，缺项保留原值 */
internal object TrackMetadataEditor {

    /** 读内嵌标签；无本地源返回 null，失败按无标签 */
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

    /** 读内嵌封面；失败返回 null */
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

    /** 写回编辑结果；cover 三态区分不改/写入/删除 */
    suspend fun save(
        context: Context,
        track: MusicTrack,
        fields: TrackTagFields,
        cover: CoverUpdate,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!track.isLocalAudioSource) return@withContext false
        try {
            MusicMetadataWriter.writeFields(context, track, fields, cover)
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
