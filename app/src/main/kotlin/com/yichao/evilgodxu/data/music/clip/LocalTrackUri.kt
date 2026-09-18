package com.yichao.evilgodxu.data.music.clip

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.model.MusicTrack
import java.io.File

/**
 * 解析可供系统组件（分享目标、MediaExtractor）读取的本地音频 URI。
 *
 * 媒体库条目直接用其 content URI；仅有文件路径的条目经 FileProvider 授权 ——
 * 直接传 file:// 会被接收方以 FileUriExposedException 拒绝。
 * 在线曲目无本地文件，返回 null 由调用方按不可用处理。
 */
internal fun localTrackUri(context: Context, track: MusicTrack): Uri? {
    if (track.audioUri.startsWith("content:")) return track.audioUri.toUri()
    val rawPath = track.path.ifBlank { track.audioUri.removePrefix("file://").removePrefix("file:") }
    if (rawPath.isBlank()) return null
    val file = File(rawPath)
    if (!file.exists()) return null
    return runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}
