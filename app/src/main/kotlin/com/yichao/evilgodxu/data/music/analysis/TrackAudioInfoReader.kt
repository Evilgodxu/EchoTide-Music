package com.yichao.evilgodxu.data.music.analysis

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.AudioSignalPathFormat
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.R
import java.io.File
import java.io.FileInputStream

// 本地音频格式信息读取：解码头未给出或冷启动未播放时，直接读文件元数据补齐
internal object TrackAudioInfoReader {

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

    // 容器头解析出的基础格式参数（采样率/位深/声道）
    data class ContainerFormat(val sampleRate: Int, val bitDepth: Int, val channels: Int)

    // 读取源文件位深与声道：FLAC 解析 STREAMINFO、WAV 解析 RIFF fmt 块。
    // 仅按文件扩展名判定格式，供主线程（解码头）轻量调用；其余格式返回 null
    fun readContainerFormat(context: Context, track: MusicTrack): ContainerFormat? =
        when (track.path.substringAfterLast('.', "").uppercase().takeIf { it.isNotBlank() }) {
            "FLAC" -> readFlacContainerFormat(context, track)
            "WAV", "WAVE" -> readWavContainerFormat(context, track)
            else -> null
        }

    // 冷启动未播放时预填的格式信息：采样率/比特率走官方 MediaMetadataRetriever，
    // 位深与声道对 FLAC/WAV 解析容器头。读不到的项一律留空，不做位深/声道推测；
    // 全部读不到时返回 null，由展示层保持空白
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
            retriever.setDataSource(context, Uri.parse(track.audioUri))
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
                Uri.parse(track.audioUri).let {
                    context.contentResolver.openFileDescriptor(it, "r")?.use { fd -> fd.statSize }
                }
            }.getOrNull()
        }
        return null
    }

    // 解析 FLAC STREAMINFO（fLaC + 块头 + 34 字节流信息）中的采样率、声道与位深。
    // 位域规范：采样率 20 位 + 声道 3 位 + 位深 5 位 + 总采样 36 位。
    // 供音质异常识别在 IO 线程解析头信息
    fun readFlacContainerFormat(context: Context, track: MusicTrack): ContainerFormat? =
        readHeader(context, track, 42) { bytes ->
            if (!bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x66, 0x4C, 0x61, 0x43))) return@readHeader null
            ContainerFormat(
                // 采样率跨字节 18/19/20：18 全 8 位 + 19 全 8 位 + 20 高 4 位
                sampleRate = ((bytes[18].toInt() and 0xFF) shl 12) or
                    ((bytes[19].toInt() and 0xFF) shl 4) or
                    ((bytes[20].toInt() and 0xF0) ushr 4),
                channels = ((bytes[20].toInt() and 0x0E) ushr 1) + 1,
                // 位深 5 位域（20 位 bit0 + 21 位高 4 位）先拼合再加 1：加 1 需作用于整个 5 位值，
                // 否则 32bit（5 位域=31）会被低 4 位进位吞掉高位而误读为 16bit
                bitDepth = (((bytes[20].toInt() and 0x01) shl 4) or ((bytes[21].toInt() and 0xF0) ushr 4)) + 1,
            )
        }

    // 解析 WAV RIFF 头（44 字节）fmt 块中的采样率、声道与位深
    private fun readWavContainerFormat(context: Context, track: MusicTrack): ContainerFormat? =
        readHeader(context, track, 44) { bytes ->
            if (!bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x52, 0x49, 0x46, 0x46)) ||
                !bytes.copyOfRange(8, 12).contentEquals(byteArrayOf(0x57, 0x41, 0x56, 0x45))
            ) return@readHeader null
            ContainerFormat(
                // fmt 块偏移 24-27 为小端采样率
                sampleRate = ((bytes[27].toInt() and 0xFF) shl 24) or
                    ((bytes[26].toInt() and 0xFF) shl 16) or
                    ((bytes[25].toInt() and 0xFF) shl 8) or
                    (bytes[24].toInt() and 0xFF),
                channels = ((bytes[23].toInt() and 0xFF) shl 8) or (bytes[22].toInt() and 0xFF),
                bitDepth = ((bytes[35].toInt() and 0xFF) shl 8) or (bytes[34].toInt() and 0xFF),
            )
        }

    // 读取本地音频文件头部若干字节：文件路径优先，否则经 ContentResolver 打开
    private inline fun readHeader(
        context: Context,
        track: MusicTrack,
        size: Int,
        parse: (ByteArray) -> ContainerFormat?,
    ): ContainerFormat? {
        val input = if (track.path.isNotBlank()) {
            runCatching { FileInputStream(track.path) }.getOrNull()
        } else if (track.audioUri.startsWith("content:") || track.audioUri.startsWith("file:")) {
            runCatching { context.contentResolver.openInputStream(Uri.parse(track.audioUri)) }.getOrNull()
        } else {
            null
        }
        val bytes = ByteArray(size)
        val read = if (input != null) {
            runCatching { input.use { it.read(bytes) } }.getOrNull() ?: 0
        } else {
            0
        }
        if (read < size) return null
        val format = parse(bytes) ?: return null
        return format.takeIf { it.sampleRate > 0 && it.channels > 0 && it.bitDepth > 0 }
    }
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
    val scheme = runCatching { Uri.parse(track.audioUri).scheme }.getOrNull()
    return if (scheme == "http" || scheme == "https") {
        context.getString(R.string.library_analysis_online)
    } else {
        context.getString(R.string.library_analysis_other)
    }
}
