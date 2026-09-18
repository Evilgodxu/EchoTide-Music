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

// 默认铃声用途：决定要写入的铃声类型，以及复制来源时的落点目录
internal enum class RingtoneUsage { RINGTONE, ALARM }

// 安装结果：写入系统设置权限缺失是唯一需要用户介入的失败，单独区分以便授权后自动重试
internal sealed interface RingtoneInstallResult {
    data object Success : RingtoneInstallResult
    data object WriteSettingsRequired : RingtoneInstallResult
    data object Failed : RingtoneInstallResult
}

/**
 * 把整首曲目设为系统默认来电铃声或闹钟铃声。
 *
 * 媒体库入库的曲目直接沿用自身 URI —— 系统读得到，也不额外占一份磁盘；
 * 由外部应用传入的音频只有本应用持有读授权，系统读不到，复制一份到公共铃声目录再设置。
 */
internal suspend fun setTrackAsDefaultSound(
    context: Context,
    track: MusicTrack,
    usage: RingtoneUsage,
): RingtoneInstallResult = withContext(Dispatchers.IO) {
    val trackUri = localTrackUri(context, track) ?: return@withContext RingtoneInstallResult.Failed
    // 写默认铃声走 Settings 写入，需 WRITE_SETTINGS 授权；缺失时先引导授权
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
        // 授权刚被撤销：按失败上报，不打断界面流程
        CrashLogManager.logException(TAG, "设为默认铃声失败：${track.title}", applied.exceptionOrNull())
        return@withContext RingtoneInstallResult.Failed
    }
    RingtoneInstallResult.Success
}

// 复制音频到公共铃声/闹钟目录并登记媒体类型，返回系统读得到的媒体库 URI
private fun publishForSystem(context: Context, source: Uri, title: String, usage: RingtoneUsage): Uri? {
    val resolver = context.contentResolver
    val mime = resolver.getType(source) ?: DEFAULT_AUDIO_MIME
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: DEFAULT_AUDIO_EXTENSION
    val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val displayName = "${sanitizeName(title)}${usage.nameSuffix()}.$extension"
    val relativePath = usage.relativePath()
    deleteExistingEntry(resolver, collection, displayName, relativePath)

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
    // 写入失败时条目会留在媒体库里成为空文件，就地回收
    if (!written) {
        runCatching { resolver.delete(uri, null, null) }
        return null
    }
    return uri
}

// 清掉同目录下的同名旧条目：同一首歌反复设置只保留最后一次结果，避免铃声列表堆积
private fun deleteExistingEntry(
    resolver: ContentResolver,
    collection: Uri,
    displayName: String,
    relativePath: String,
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
            // 公共目录里可能有用户自己的同名文件，只在同目录内回收
            if (cursor.getString(pathColumn)?.trimEnd('/') != relativePath.trimEnd('/')) continue
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

private fun RingtoneUsage.relativePath(): String =
    if (this == RingtoneUsage.RINGTONE) Environment.DIRECTORY_RINGTONES else Environment.DIRECTORY_ALARMS

// 同名音频按用途分别追加后缀，避免同一首歌的铃声与闹钟产物互相覆盖
private fun RingtoneUsage.nameSuffix(): String =
    if (this == RingtoneUsage.RINGTONE) RINGTONE_SUFFIX else ALARM_SUFFIX

// 文件名中不可用字符统一替换，避免公共目录写入失败
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
