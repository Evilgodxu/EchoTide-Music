package com.yichao.evilgodxu.data.music.clip

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "RingtoneInstaller"

// 铃声用途，决定类型与落点目录
internal enum class RingtoneUsage { RINGTONE, ALARM }

// 结果；缺写设置权限单独区分，可重试
internal sealed interface RingtoneInstallResult {
    data object Success : RingtoneInstallResult
    data object WriteSettingsRequired : RingtoneInstallResult
    data object Failed : RingtoneInstallResult
}

/**
 * 设置系统默认铃声或闹钟铃声
 * 媒体库条目直用原 URI，外部音频复制到公共目录
 */
internal suspend fun setTrackAsDefaultSound(
    context: Context,
    track: MusicTrack,
    usage: RingtoneUsage,
): RingtoneInstallResult = withContext(Dispatchers.IO) {
    val trackUri = localTrackUri(context, track) ?: return@withContext RingtoneInstallResult.Failed
    // 需 WRITE_SETTINGS，缺失先引导授权
    if (!Settings.System.canWrite(context)) return@withContext RingtoneInstallResult.WriteSettingsRequired

    val prepared = runCatching {
        if (trackUri.authority == MediaStore.AUTHORITY) {
            trackUri
        } else {
            publishForSystem(context, trackUri, track.title, usage)
        }
    }.getOrNull()
    if (prepared == null) {
        CrashLogManager.logException(TAG, "准备铃声来源失败：${track.title}")
        return@withContext RingtoneInstallResult.Failed
    }

    val applied = runCatching {
        RingtoneManager.setActualDefaultRingtoneUri(context, usage.systemType(), prepared)
    }
    if (applied.isFailure) {
        // 授权被撤销，按失败上报
        CrashLogManager.logException(TAG, "设为默认铃声失败：${track.title}", applied.exceptionOrNull())
        return@withContext RingtoneInstallResult.Failed
    }
    RingtoneInstallResult.Success
}

private fun publishForSystem(context: Context, source: Uri, title: String, usage: RingtoneUsage): Uri? {
    val resolver = context.contentResolver
    val mime = resolver.getType(source) ?: DEFAULT_AUDIO_MIME
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: DEFAULT_AUDIO_EXTENSION
    val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val displayName = "${sanitizeName(title)}${usage.nameSuffix()}.$extension"
    val relativePath = usage.relativePath()

    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
        put(MediaStore.Audio.Media.TITLE, title)
        put(MediaStore.Audio.Media.MIME_TYPE, mime)
        put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
        put(MediaStore.Audio.Media.IS_MUSIC, 0)
        put(MediaStore.Audio.Media.IS_RINGTONE, if (usage == RingtoneUsage.RINGTONE) 1 else 0)
        put(MediaStore.Audio.Media.IS_ALARM, if (usage == RingtoneUsage.ALARM) 1 else 0)
        put(MediaStore.Audio.Media.IS_NOTIFICATION, 0)
    }
    val uri = resolver.insert(collection, values) ?: return null
    val written = resolver.openOutputStream(uri)?.use { output ->
        resolver.openInputStream(source)?.use { input ->
            input.copyTo(output)
            true
        } ?: false
    } ?: false
    // 写入失败会留下空文件，就地回收
    if (!written) {
        runCatching { resolver.delete(uri, null, null) }
        return null
    }
    // 写入成功再清旧产物，只清本应用目录
    deleteExistingEntry(resolver, collection, displayName, relativePath, keepUri = uri)
    return uri
}

// 清同名旧条目，避免铃声列表堆积
private fun deleteExistingEntry(
    resolver: ContentResolver,
    collection: Uri,
    displayName: String,
    relativePath: String,
    keepUri: Uri,
) {
    resolver.query(
        collection,
        arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.RELATIVE_PATH),
        "${MediaStore.Audio.Media.DISPLAY_NAME}=?",
        arrayOf(displayName),
        null,
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
        while (cursor.moveToNext()) {
            // 目标目录专属本应用，同名即本应用产出
            if (cursor.getString(pathColumn)?.trimEnd('/') != relativePath.trimEnd('/')) continue
            val existing = collection.buildUpon().appendPath(cursor.getLong(idColumn).toString()).build()
            if (existing == keepUri) continue
            runCatching {
                resolver.delete(
                    collection.buildUpon().appendPath(cursor.getLong(idColumn).toString()).build(),
                    null,
                    null,
                )
            }
        }
    }
}

private fun RingtoneUsage.systemType(): Int =
    if (this == RingtoneUsage.RINGTONE) RingtoneManager.TYPE_RINGTONE else RingtoneManager.TYPE_ALARM

// 落专属子目录，不波及用户文件
private fun RingtoneUsage.relativePath(): String =
    if (this == RingtoneUsage.RINGTONE) "${Environment.DIRECTORY_RINGTONES}/EchoTide"
    else "${Environment.DIRECTORY_ALARMS}/EchoTide"

// 按用途加后缀，避免互相覆盖
private fun RingtoneUsage.nameSuffix(): String =
    if (this == RingtoneUsage.RINGTONE) RINGTONE_SUFFIX else ALARM_SUFFIX

// 替换非法字符，避免写入失败
private fun sanitizeName(title: String): String =
    title.ifBlank { DEFAULT_NAME }
        .map { if (it in FORBIDDEN_NAME_CHARS || it.code < 0x20) '_' else it }
        .joinToString("")
        .take(MAX_NAME_LENGTH)

private val FORBIDDEN_NAME_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')
private const val MAX_NAME_LENGTH = 40
private const val DEFAULT_NAME = "audio"
private const val RINGTONE_SUFFIX = " - ringtone"
private const val ALARM_SUFFIX = " - alarm"
private const val DEFAULT_AUDIO_MIME = "audio/mpeg"
private const val DEFAULT_AUDIO_EXTENSION = "mp3"
