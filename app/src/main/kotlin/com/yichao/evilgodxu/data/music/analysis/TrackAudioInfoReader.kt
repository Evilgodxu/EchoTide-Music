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

// 读取曲目格式：解码头缺失时直接读文件元数据
internal object TrackAudioInfoReader {

    // 可解析容器头的扩展名：无损与线性 PCM
    // 位深与码流名只能从容器头得到
    private val CONTAINER_HEADER_FORMATS = setOf(
        "FLAC", "WAV", "WAVE", "RF64",
        "ALAC", "M4A", "M4B", "MP4",
    )

    // 头部窗口 16KB，够覆盖定长头块
    private const val HEADER_WINDOW_BYTES = 16 * 1024

    // moov 后置时按盒子尺寸读整段
    private const val MOOV_WINDOW_BYTES = 64 * 1024

    // 比特率优先取元数据，缺失按大小估算
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

    // 读采样率/位深/声道，主线程调用
    // 格式按魔数判定，扩展名只作门限
    fun readContainerFormat(context: Context, track: MusicTrack): ContainerFormat? {
        if (!isContainerHeaderFormat(track.path)) return null
        val head = readSlice(context, track, 0L, HEADER_WINDOW_BYTES) ?: return null
        // ID3v2 会顶开魔数，按长度重定位
        val id3Size = ContainerHeaderParser.id3v2TagSize(head)
        val bodyStart = if (id3Size > 0) id3Size.toLong() else 0L
        val body = if (bodyStart > 0) {
            readSlice(context, track, bodyStart, HEADER_WINDOW_BYTES) ?: return null
        } else {
            head
        }
        val fileLength = readFileSize(context, track) ?: 0L
        ContainerHeaderParser.parse(body, bodyStart, fileLength)?.let { return it }
        // moov 常在文件尾，按 mdat 尺寸外推
        if (fileLength <= 0L || !ContainerHeaderParser.isMp4Like(body)) return null
        val moovOffset = ContainerHeaderParser.mp4BoxOffset(body, bodyStart, fileLength, "moov")
            ?: return null
        if (moovOffset <= bodyStart) return null
        val moov = readSlice(context, track, moovOffset, MOOV_WINDOW_BYTES) ?: return null
        return ContainerHeaderParser.parseMp4(moov, moovOffset, fileLength)
    }

    // 冷启动预填格式，读不到的项留空不猜
    fun readIdleFormat(context: Context, track: MusicTrack): AudioSignalPathFormat? {
        if (!track.isLocalAudioSource) return null
        val formatName = trackFormatName(context, track)
        val sampleRate = readSampleRate(context, track)
        val bitrateKbps = readBitrateKbps(context, track)
        if (formatName == null && sampleRate == null && bitrateKbps == null) return null
        val container = readContainerFormat(context, track)
        return AudioSignalPathFormat(
            // 容器头给出码流名，读不出才回退
            format = container?.codecName ?: formatName,
            sampleRate = sampleRate,
            outputRate = sampleRate,
            bitDepth = container?.bitDepth,
            channels = container?.channels,
            bitrate = bitrateKbps,
        )
    }

    // 展示格式名，容器扩展名借 mime
    private fun trackFormatName(context: Context, track: MusicTrack): String? =
        formatDisplayName(
            track.path,
            if (extensionNamesFormat(track.path)) null else readMimeFormat(context, track),
        )

    // 已知格式统一命名，其余取 mime 尾段
    fun mimeToFormatName(mime: String?): String? = when (mime) {
        "audio/mpeg" -> "MP3"
        "audio/flac" -> "FLAC"
        "audio/alac" -> "ALAC"
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

    private fun estimateAverageBitrateKbps(context: Context, track: MusicTrack): Int? {
        val sizeBytes = readFileSize(context, track) ?: return null
        val durationSec = track.duration / 1000
        if (sizeBytes <= 0 || durationSec <= 0) return null
        return (sizeBytes * 8 / durationSec / 1000).toInt().takeIf { it > 0 }
    }

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

    // 读指定区间，短读按实际返回
    private fun readSlice(context: Context, track: MusicTrack, offset: Long, size: Int): ByteArray? {
        val input = openInputStream(context, track) ?: return null
        return runCatching {
            input.use { stream ->
                if (!skipFully(stream, offset)) return@use null
                readUpTo(stream, size)
            }
        }.getOrNull()
    }

    private fun openInputStream(context: Context, track: MusicTrack): InputStream? = when {
        track.path.isNotBlank() -> runCatching { FileInputStream(track.path) }.getOrNull()
        track.audioUri.startsWith("content:") || track.audioUri.startsWith("file:") ->
            runCatching { context.contentResolver.openInputStream(track.audioUri.toUri()) }
                .getOrNull()
        else -> null
    }

    // InputStream.skip 允许少跳，循环补齐
    private fun skipFully(stream: InputStream, count: Long): Boolean {
        var skipped = 0L
        while (skipped < count) {
            val step = stream.skip(count - skipped)
            if (step <= 0) return false
            skipped += step
        }
        return true
    }

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

    // 仅决定是否读盘，须与格式名判定对齐
    internal fun isContainerHeaderFormat(path: String?): Boolean =
        path?.substringAfterLast('.', "")?.uppercase()?.let { it in CONTAINER_HEADER_FORMATS } == true
}

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
    "ALAC" to "ALAC",
    "WMA" to "WMA",
    "MID" to "MID",
    "MIDI" to "MIDI",
)

// 曲库格式分类名，纯在线流归「在线」
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
