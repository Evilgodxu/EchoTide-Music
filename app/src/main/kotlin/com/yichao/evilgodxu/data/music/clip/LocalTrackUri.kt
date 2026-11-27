package com.yichao.evilgodxu.data.music.clip

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.model.MusicTrack
import java.io.File

/** 本地音频 URI；路径经 FileProvider
 *  无本地文件返回 null */
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
