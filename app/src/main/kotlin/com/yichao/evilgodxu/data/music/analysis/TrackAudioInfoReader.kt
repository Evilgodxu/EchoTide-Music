package com.yichao.evilgodxu.data.music.analysis

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.AudioSignalPathFormat
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.R
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

// 本地音频格式信息读取：解码头未给出或冷启动未播放时，直接读文件元数据补齐
internal object TrackAudioInfoReader {

    // 容器头可解析的扩展名：无损与线性 PCM 容器，含 DSD 与 APE 这类无平台解码器的格式
    private val CONTAINER_HEADER_FORMATS = setOf(
        "FLAC", "WAV", "WAVE", "RF64", "AIFF", "AIF", "AIFC",
        "ALAC", "M4A", "MP4", "APE", "DSF", "DFF",
    )

    // 容器头窗口：定长头块（STREAMINFO/fmt/COMM/APE 头/DSD 头）都在文件头部，
    // 16KB 足以覆盖前置元数据块偏移与 MP4 的 ftyp+free+moov 前段；
    // 窗口内找不到头结构即放弃解析，不无限扩大读取量
    private const val HEADER_WINDOW_BYTES = 16 * 1024

    // moov 后置（未 faststart）时的二次读取窗口：按顶层盒子尺寸外推 moov 起点后整段读取
    private const val MOOV_WINDOW_BYTES = 64 * 1024

    // 读取真实比特率（kbps）：优先媒体元数据，其次按文件大小/时长估算平均比特率
    fun readBitrateKbps(context: Context, track: MusicTrack): Int? {
        val retriever = MediaMetadataRetriever()
        try {
            setDataSource(retriever, context, track)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
                ?.let { return (it / 1000).toInt().coerceAtLeast(1) }
        } catch (e: Exception) {
            CrashLogManager.logException("TrackAudioInfoReader", "读取曲目比特率失败", e)
        } finally {
            runCatching { retriever.release() }
        }
        return estimateAverageBitrateKbps(context, track)
    }

    // 读取源文件采样率/位深/声道：按容器头解析，供主线程（解码头）调用。
    // 实际格式由容器魔数判定而非扩展名——扩展名只决定该文件是否值得读盘，
    // 被改名或加挂了 ID3v2 标签的文件同样能解析出真实规格
    fun readContainerFormat(context: Context, track: MusicTrack): ContainerFormat? {
        if (!isContainerHeaderFormat(track)) return null
        val head = readSlice(context, track, 0L, HEADER_WINDOW_BYTES) ?: return null
        // ID3v2 前置标签（部分下载源在 FLAC/APE/DSF 前写入）把容器魔数顶到标签之后，
        // 按标签长度重新定位后再读一个窗口
        val id3Size = ContainerHeaderParser.id3v2TagSize(head)
        val bodyStart = if (id3Size > 0) id3Size.toLong() else 0L
        val body = if (bodyStart > 0) {
            readSlice(context, track, bodyStart, HEADER_WINDOW_BYTES) ?: return null
        } else {
            head
        }
        val fileLength = readFileSize(context, track) ?: 0L
        ContainerHeaderParser.parse(body, bodyStart, fileLength)?.let { return it }
        // MP4 的 moov 常位于文件尾（未 faststart）：头窗遍历被超出窗口的 mdat 截断，
        // 据其尺寸外推出 moov 起点后再整段读取解析
        if (fileLength <= 0L || !ContainerHeaderParser.isMp4Like(body)) return null
        val moovOffset = ContainerHeaderParser.mp4BoxOffset(body, bodyStart, fileLength, "moov")
            ?: return null
        if (moovOffset <= bodyStart) return null
        val moov = readSlice(context, track, moovOffset, MOOV_WINDOW_BYTES) ?: return null
        return ContainerHeaderParser.parseMp4(moov, moovOffset, fileLength)
    }

    // 音质异常识别的容器头入口：候选面只有 FLAC，与通用入口共用同一解析实现
    fun readFlacContainerFormat(context: Context, track: MusicTrack): ContainerFormat? =
        if (track.path.substringAfterLast('.', "").uppercase() == "FLAC") {
            readContainerFormat(context, track)
        } else {
            null
        }

    // 冷启动未播放时预填的格式信息：采样率/比特率走官方 MediaMetadataRetriever，
    // 位深与声道按容器头解析（覆盖无损与线性 PCM 容器）。读不到的项一律留空，
    // 不做位深/声道推测；全部读不到时返回 null，由展示层保持空白
    fun readIdleFormat(context: Context, track: MusicTrack): AudioSignalPathFormat? {
        if (!track.isLocalAudioSource) return null
        val formatName = trackFormatName(context, track)
        val sampleRate = readSampleRate(context, track)
        val bitrateKbps = readBitrateKbps(context, track)
        if (formatName == null && sampleRate == null && bitrateKbps == null) return null
        val container = readContainerFormat(context, track)
        return AudioSignalPathFormat(
            format = formatName,
            sampleRate = sampleRate,
            outputRate = sampleRate,
            bitDepth = container?.bitDepth,
            channels = container?.channels,
            bitrate = bitrateKbps,
        )
    }

    private fun trackFormatName(context: Context, track: MusicTrack): String? =
        track.path
            .substringAfterLast('.', "")
            .uppercase()
            .takeIf { it.isNotBlank() }
            ?: readMimeFormat(context, track)

    // mime 映射为展示用格式名；已知格式统一命名，其余取 mime 尾段
    fun mimeToFormatName(mime: String?): String? = when (mime) {
        "audio/mpeg" -> "MP3"
        "audio/flac" -> "FLAC"
        "audio/wav", "audio/x-wav" -> "WAV"
        "audio/ogg" -> "OGG"
        "audio/mp4", "audio/aac" -> "AAC"
        null -> null
        else -> mime.substringAfterLast('/').uppercase().takeIf { it.isNotBlank() }
    }

    private fun readSampleRate(context: Context, track: MusicTrack): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            setDataSource(retriever, context, track)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
        } catch (e: Exception) {
            CrashLogManager.logException("TrackAudioInfoReader", "读取采样率失败", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readMimeFormat(context: Context, track: MusicTrack): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            setDataSource(retriever, context, track)
            mimeToFormatName(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
        } catch (e: Exception) {
            CrashLogManager.logException("TrackAudioInfoReader", "读取音频类型失败", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun setDataSource(retriever: MediaMetadataRetriever, context: Context, track: MusicTrack) {
        if (track.path.isNotBlank()) {
            retriever.setDataSource(track.path)
        } else {
            retriever.setDataSource(context, track.audioUri.toUri())
        }
    }

    // 估算平均比特率：文件大小 × 8 ÷ 时长（秒）÷ 1000
    private fun estimateAverageBitrateKbps(context: Context, track: MusicTrack): Int? {
        val sizeBytes = readFileSize(context, track) ?: return null
        val durationSec = track.duration / 1000
        if (sizeBytes <= 0 || durationSec <= 0) return null
        return (sizeBytes * 8 / durationSec / 1000).toInt().takeIf { it > 0 }
    }

    // 读取本地音频文件字节大小：文件路径优先，否则经 ContentResolver 打开
    fun readFileSize(context: Context, track: MusicTrack): Long? {
        if (track.path.isNotBlank()) {
            val file = File(track.path)
            if (file.isFile) return file.length()
        } else if (track.audioUri.startsWith("content:") || track.audioUri.startsWith("file:")) {
            return runCatching {
                track.audioUri.toUri().let {
                    context.contentResolver.openFileDescriptor(it, "r")?.use { fd -> fd.statSize }
                }
            }.getOrNull()
        }
        return null
    }

    // 读取文件 [offset, offset+size) 区间的字节：文件短于请求长度时按实际读到的字节返回，
    // 各容器解析器自行判断头结构是否完整；区间起点越界或流不可用返回 null
    private fun readSlice(context: Context, track: MusicTrack, offset: Long, size: Int): ByteArray? {
        val input = openInputStream(context, track) ?: return null
        return runCatching {
            input.use { stream ->
                if (!skipFully(stream, offset)) return@use null
                readUpTo(stream, size)
            }
        }.getOrNull()
    }

    // 打开本地音频输入流：文件路径优先，否则经 ContentResolver
    private fun openInputStream(context: Context, track: MusicTrack): InputStream? = when {
        track.path.isNotBlank() -> runCatching { FileInputStream(track.path) }.getOrNull()
        track.audioUri.startsWith("content:") || track.audioUri.startsWith("file:") ->
            runCatching { context.contentResolver.openInputStream(track.audioUri.toUri()) }
                .getOrNull()
        else -> null
    }

    // 跳过指定字节数：InputStream.skip 允许少跳，循环补齐
    private fun skipFully(stream: InputStream, count: Long): Boolean {
        var skipped = 0L
        while (skipped < count) {
            val step = stream.skip(count - skipped)
            if (step <= 0) return false
            skipped += step
        }
        return true
    }

    // 读取至多 size 字节
    private fun readUpTo(stream: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var read = 0
        while (read < size) {
            val step = stream.read(buffer, read, size - read)
            if (step <= 0) break
            read += step
        }
        return buffer.copyOf(read)
    }

    // 是否属于可直接解析容器头的格式：扩展名门限，避免对有损格式做无谓读盘。
    // M4A/MP4 一并纳入——ALAC 的位深只存在于容器头中（有损编码则该字段留空）
    private fun isContainerHeaderFormat(track: MusicTrack): Boolean =
        track.path.substringAfterLast('.', "").uppercase() in CONTAINER_HEADER_FORMATS
}

// 已知音频扩展名到展示名的映射
private val FORMAT_EXTENSION_NAMES = mapOf(
    "MP3" to "MP3",
    "FLAC" to "FLAC",
    "WAV" to "WAV",
    "WAVE" to "WAV",
    "AAC" to "AAC",
    "M4A" to "M4A",
    "MP4" to "M4A",
    "OGG" to "OGG",
    "OPUS" to "OPUS",
    "APE" to "APE",
    "ALAC" to "ALAC",
    "WMA" to "WMA",
    "AIFF" to "AIFF",
    "AIF" to "AIFF",
    "DSF" to "DSF",
    "DFF" to "DFF",
    "MID" to "MID",
    "MIDI" to "MIDI",
)

// 曲库格式分类名：扩展名映射为规范名，未知取扩展名，纯在线流归「在线」，其余归「其他」；
// 供曲库分析统计与按格式定位歌单共用，保证两处分类一致
internal fun trackFormatCategory(context: Context, track: MusicTrack): String {
    val extension = track.path.substringAfterLast('.', "").uppercase()
    if (extension.isNotBlank()) return FORMAT_EXTENSION_NAMES[extension] ?: extension
    val scheme = runCatching { track.audioUri.toUri().scheme }.getOrNull()
    return if (scheme == "http" || scheme == "https") {
        context.getString(R.string.library_analysis_online)
    } else {
        context.getString(R.string.library_analysis_other)
    }
}
