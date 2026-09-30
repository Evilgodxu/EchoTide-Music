package com.yichao.evilgodxu.data.music

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.api.stableIdFromString
import com.yichao.evilgodxu.data.music.metadata.MusicEmbeddedTagReader
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 本地音乐扫描器（基于 MediaStore）：无共享可变状态、纯函数集合，以 object 单例形态提供。
// 封面不在扫描期产出：显示端按需取系统略缩图或文件内嵌封面（仅当前曲目的一张缩略图落盘，见 CurrentCoverCache）
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
            // 外部音频同样可能是平台读不到标签的容器（WAV 等），此时标题取自路径末段，改读文件内嵌标签
            if (isPlatformTagMissing(title, artist, segment.substringBeforeLast('.'), unknownArtist)) {
                MusicEmbeddedTagReader.read(context, "", uri.toString())?.let { embedded ->
                    title = embedded.title?.takeIf { it.isNotBlank() } ?: title
                    artist = embedded.artist?.takeIf { it.isNotBlank() } ?: artist
                }
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // 用 64 位稳定哈希生成外部音频 id，降低不同 URI 的碰撞概率
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

    // 扫描设备本地音乐文件，过滤时长 >= 30 秒的音频
    // 仅从 MediaStore 游标读取基础元数据，封面延迟加载，不阻塞扫描
    suspend fun scan(context: Context): List<MusicTrack> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<MusicTrack>()
        val contentResolver = context.contentResolver
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
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                    "${MediaStore.Audio.Media.DURATION} >= 30000"
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val unknownArtist = context.getString(R.string.music_scanner_unknown_artist)
                val idIdx = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleIdx = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val dataIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val durationIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val albumIdIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val albumNameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val modifiedIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                if (idIdx < 0 || titleIdx < 0) return@withContext tracks
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val path = if (dataIdx >= 0) cursor.getString(dataIdx).orEmpty() else ""
                    // 跳过 APK 抽取目录下的资源包音频（音效/环境声等），避免被误判为音乐
                    if (isNonMusicPath(path)) continue
                    val audioUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    val fileName = path.substringAfterLast('/').substringBeforeLast('.')
                    var title = cursor.getString(titleIdx)?.takeIf { it.isNotBlank() }
                        ?: fileName.ifBlank { context.getString(R.string.music_scanner_unknown_song) }
                    var artist = if (artistIdx >= 0) {
                        cursor.getString(artistIdx)?.takeIf { it.isNotBlank() } ?: unknownArtist
                    } else unknownArtist
                    val duration = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L
                    val albumId = if (albumIdIdx >= 0) cursor.getLong(albumIdIdx) else 0L
                    var albumName = if (albumNameIdx >= 0) cursor.getString(albumNameIdx).orEmpty() else ""
                    // 平台没读出标签的曲目（标题仍是文件名、艺术家缺失）改读音频内嵌标签。
                    // WAV 等容器的标签平台提取器读不到，此时 MediaStore 只留文件名派生的标题；
                    // 标题已由平台读出时不再读文件，避免为全库逐曲多一次 IO
                    if (isPlatformTagMissing(title, artist, fileName, unknownArtist)) {
                        MusicEmbeddedTagReader.read(context, path, audioUri.toString())?.let { embedded ->
                            title = embedded.title?.takeIf { it.isNotBlank() } ?: title
                            artist = embedded.artist?.takeIf { it.isNotBlank() } ?: artist
                            albumName = embedded.album?.takeIf { it.isNotBlank() } ?: albumName
                        }
                    }
                    // DATE_MODIFIED 以秒为单位，统一转为毫秒供排序使用
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
            CrashLogManager.logException("MusicScanner", "扫描本地音乐失败", e)
        }
        tracks
    }
}

// 平台是否未读出文件内标签：标题仍等于文件名、艺术家为空或平台/本应用的未知占位值。
// MediaProvider 对读不到标签的音频按文件名填 TITLE、按 <unknown> 填 ARTIST，
// 这类曲目的标签很可能写在平台提取器读不到的位置，值得再读一次音频文件本身
private fun isPlatformTagMissing(
    title: String,
    artist: String,
    fileName: String,
    unknownArtist: String,
): Boolean = title == fileName ||
    artist.isBlank() ||
    artist == unknownArtist ||
    artist == MediaStore.UNKNOWN_STRING

// 音频文件路径片段标记：命中即视为非音乐的应用程序资源/解压包音频（如游戏资源包音效）
private val NON_MUSIC_PATH_MARKERS = listOf(
    "/resource_packs/", // 游戏资源包（材质/声音包）
    "/apks/",           // APK 反编译/解压目录
)

// 判断是否为非音乐的应用程序资源音频
private fun isNonMusicPath(path: String): Boolean =
    path.isNotBlank() && NON_MUSIC_PATH_MARKERS.any { marker -> path.contains(marker, ignoreCase = true) }

// 解析音频 URI 对应的本地文件真实路径，用于跨 URI 形态识别同一文件：
// 同一文件可能以 MediaStore 行、SAF 文档 URI、直路文件路径等多种形态出现
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

// 归一化音频 URI：统一 scheme 大小写并去除查询参数/片段，作为兜底去重键
internal fun normalizedAudioUri(audioUri: String): String =
    audioUri.toUri()
        .normalizeScheme()
        .buildUpon()
        .clearQuery()
        .fragment(null)
        .build()
        .toString()

// 曲目去重键：优先真实文件路径，跨 SAF/MediaStore/直路路径等 URI 形态识别同一文件；
// 无本地路径时回退归一化 URI
internal fun trackIdentityKey(context: Context, track: MusicTrack): String {
    val realPath = track.path.takeIf { it.isNotBlank() }
        ?: resolveLocalPath(context, track.audioUri)
    return realPath?.let { File(it).absolutePath } ?: normalizedAudioUri(track.audioUri)
}
