package com.yichao.evilgodxu.data.music

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.api.stableIdFromString
import com.yichao.evilgodxu.data.music.metadata.EmbeddedTagBackfill
import com.yichao.evilgodxu.data.music.metadata.MusicEmbeddedTagReader
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 本地音乐扫描器，无共享状态；封面按需取，不在扫描期产出
object MusicScanner {

    suspend fun fromUri(context: Context, uri: Uri): MusicTrack? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val unknownArtist = context.getString(R.string.music_scanner_unknown_artist)
            val segment = uri.lastPathSegment.orEmpty()
            var title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: segment.ifBlank { context.getString(R.string.music_scanner_external_music) }
            var artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: unknownArtist
            // 平台读不到标签时改读文件内嵌标签
            if (EmbeddedTagBackfill.isPlatformTagMissing(title, artist, segment.substringBeforeLast('.'), unknownArtist)) {
                MusicEmbeddedTagReader.read(context, "", uri.toString())?.let { embedded ->
                    title = embedded.title?.takeIf { it.isNotBlank() } ?: title
                    artist = embedded.artist?.takeIf { it.isNotBlank() } ?: artist
                }
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // 64 位稳定哈希生成 id，降低碰撞
            val hash = stableIdFromString(uri.toString())
            val id = if (hash == Long.MIN_VALUE) Long.MAX_VALUE else -kotlin.math.abs(hash)
            val trackId = if (id == 0L) -1L else id
            MusicTrack(
                id = trackId,
                path = "",
                audioUri = uri.toString(),
                title = title,
                artist = artist,
                duration = duration,
                albumId = 0L,
            )
        } catch (e: Exception) {
            CrashLogManager.logException("MusicScanner", "读取外部音频元数据失败", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                CrashLogManager.logException("MusicScanner", "释放元数据读取器失败", e)
            }
        }
    }

    // 从 MediaStore 读基础元数据；不逐曲打开文件
    suspend fun scan(context: Context): List<MusicTrack> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<MusicTrack>()
        val contentResolver = context.contentResolver
        // 空库可能是缺授权或确无条目，统计剔除量留痕
        var rawRows = 0
        var droppedByPath = 0
        var droppedByExtension = 0
        var queryFailed = false
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.IS_MUSIC,
            )
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                MUSIC_SELECTION,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                rawRows = cursor.count
                val unknownArtist = context.getString(R.string.music_scanner_unknown_artist)
                val idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val durationIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val albumIdIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val albumNameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val modifiedIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                if (idIdx < 0 || titleIdx < 0) return@use
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val path = if (dataIdx >= 0) cursor.getString(dataIdx).orEmpty() else ""
                    if (isNonMusicPath(path)) {
                        droppedByPath++
                        continue
                    }
                    // 无法装载即无法播放，直接排除
                    if (!hasSupportedAudioExtension(path)) {
                        droppedByExtension++
                        continue
                    }
                    val audioUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val fileName = path.substringAfterLast('/').substringBeforeLast('.')
                    val title = cursor.getString(titleIdx)?.takeIf { it.isNotBlank() }
                        ?: fileName.ifBlank { context.getString(R.string.music_scanner_unknown_song) }
                    val artist = if (artistIdx >= 0) {
                        cursor.getString(artistIdx)?.takeIf { it.isNotBlank() } ?: unknownArtist
                    } else unknownArtist
                    val duration = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L
                    val albumId = if (albumIdIdx >= 0) cursor.getLong(albumIdIdx) else 0L
                    val albumName = if (albumNameIdx >= 0) cursor.getString(albumNameIdx).orEmpty() else ""
                    // DATE_MODIFIED 为秒，转毫秒供排序
                    val modifiedMs = if (modifiedIdx >= 0) cursor.getLong(modifiedIdx) * 1000L else 0L
                    tracks.add(
                        MusicTrack(
                            id = id,
                            path = path,
                            audioUri = audioUri.toString(),
                            title = title,
                            artist = artist,
                            duration = duration,
                            albumId = albumId,
                            albumName = albumName,
                            fileModifiedMs = modifiedMs,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            queryFailed = true
            CrashLogManager.logException("MusicScanner", "扫描本地音乐失败", e)
        }
        // 仅查询未失败时记录，避免重复
        if (!queryFailed && tracks.isEmpty()) {
            CrashLogManager.logException(
                "MusicScanner",
                "本地曲库扫描结果为空：设备 ${Build.MANUFACTURER} ${Build.MODEL}（API ${Build.VERSION.SDK_INT}）" +
                    "，音乐访问${if (isAudioReadGranted(context)) "已授予" else "未授予"}" +
                    "，库内音频 ${countAudioRows(context)} 首，符合口径 $rawRows 首" +
                    "，路径剔除 $droppedByPath 首，扩展名剔除 $droppedByExtension 首",
            )
        }
        tracks
    }

    /**
     * 曲库签名：入库曲目数 + MediaStore 代次
     * 计数须与 scan 同口径，否则冷启动反复全扫
     */
    suspend fun signature(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            val count = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA),
                MUSIC_SELECTION,
                null,
                null,
            )?.use { cursor ->
                val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                var accepted = 0
                while (cursor.moveToNext()) {
                    val path = if (dataIdx >= 0) cursor.getString(dataIdx).orEmpty() else ""
                    if (isNonMusicPath(path) || !hasSupportedAudioExtension(path)) continue
                    accepted++
                }
                accepted
            } ?: return@runCatching null
            // 所有外部卷代次之和，含 SD 卡
            val generation = MediaStore.getExternalVolumeNames(context)
                .sumOf { volume -> MediaStore.getGeneration(context, volume) }
            "$count:$generation"
        }.getOrNull()
    }
}

// 扫描与签名同源，避免反复重扫
private val MUSIC_SELECTION =
    "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"

// 命中即视为应用资源或解压包音频
private val NON_MUSIC_PATH_MARKERS = listOf(
    "/resource_packs/", // 游戏资源包
    "/apks/",           // APK 解压目录
)

private fun isNonMusicPath(path: String): Boolean =
    path.isNotBlank() && NON_MUSIC_PATH_MARKERS.any { marker -> path.contains(marker, ignoreCase = true) }

// 白名单外的容器无提取器，扫描期即排除
private val SUPPORTED_AUDIO_EXTENSIONS = setOf(
    "mp3", "mpeg", "flac", "wav", "wave",
    "m4a", "m4b", "mp4", "aac", "alac", "3gp",
    "ogg", "oga", "opus", "mka", "webm", "amr",
)

// 先取路径末段再取扩展名，避免目录名误判
private fun hasSupportedAudioExtension(path: String): Boolean =
    path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in SUPPORTED_AUDIO_EXTENSIONS

// 缺授权时返回空游标，需据此区分成因
private fun isAudioReadGranted(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED

// 不带口径的条目数，用于区分空库成因
private fun countAudioRows(context: Context): Int = runCatching {
    context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Audio.Media._ID),
        null,
        null,
        null,
    )?.use { it.count } ?: 0
}.getOrDefault(0)

// 解析真实文件路径，跨 URI 形态识别同一文件
internal fun resolveLocalPath(context: Context, audioUri: String): String? {
    if (audioUri.isBlank()) return null
    val uri = audioUri.toUri()
    if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
    if (uri.authority == "com.android.externalstorage.documents") {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val separator = documentId.indexOf(':')
        if (separator < 0) return null
        val volume = documentId.substring(0, separator)
        val relPath = documentId.substring(separator + 1)
        if (relPath.isBlank()) return null
        val root = if (volume == "primary") {
            context.getExternalFilesDir(null)?.absolutePath?.substringBefore("/Android/data")
        } else {
            "/storage/$volume"
        } ?: return null
        return File(root, relPath).absolutePath
    }
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.Audio.Media.DATA),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()
}

// 归一化 URI 作兜底去重键
internal fun normalizedAudioUri(audioUri: String): String =
    audioUri.toUri()
        .normalizeScheme()
        .buildUpon()
        .clearQuery()
        .fragment(null)
        .build()
        .toString()

// 去重键：优先真实路径，无路径回退归一化 URI
internal fun trackIdentityKey(context: Context, track: MusicTrack): String {
    val realPath = track.path.takeIf { it.isNotBlank() }
        ?: resolveLocalPath(context, track.audioUri)
    return realPath?.let { File(it).absolutePath } ?: normalizedAudioUri(track.audioUri)
}
