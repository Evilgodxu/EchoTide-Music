package com.yichao.evilgodxu.data.music.clip

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import com.yichao.evilgodxu.data.music.model.MusicTrack

/**
 * 以系统分享面板分享曲目的本地音频文件，返回是否成功拉起。
 *
 * 在线曲目没有本地文件，返回 false 由调用方按不可用处理。
 */
internal fun shareTrack(context: Context, track: MusicTrack, chooserTitle: String): Boolean {
    val uri = localTrackUri(context, track) ?: return false
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = trackAudioMimeType(track)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, track.title)
        putExtra(Intent.EXTRA_TITLE, "${track.title} - ${track.artist}")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(sendIntent, chooserTitle)
    // LocalContext 是本地化包装 context，非 Activity 时需加 NEW_TASK
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(chooser) }.isSuccess
}

// 按文件扩展名推断音频 MIME，推断不出时退化为 audio/*（分享面板仍可枚举音频类应用）
private fun trackAudioMimeType(track: MusicTrack): String {
    val name = track.path.ifBlank { track.audioUri }
    val extension = name.substringBefore('?').substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "audio/*"
}
