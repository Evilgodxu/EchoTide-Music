package com.yichao.evilgodxu.data.music

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.yichao.evilgodxu.data.music.api.stableIdFromString
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 本地音乐扫描器（基于 MediaStore）：无共享可变状态、纯函数集合，以 object 单例形态提供。
// 封面不在扫描期产出：显示端统一读系统略缩图（随媒体扫描生成），应用不落盘封面缓存
object MusicScanner {

    // 内嵌封面提取的解码限幅：与内嵌原图可能的最大显示场景对齐（折叠屏/平板横屏）
    private const val EMBEDDED_COVER_MAX_EDGE = 2048

    // 内嵌封面提取结果。必须区分「文件读不出」与「文件正常但没有内嵌封面」：
    // 前者才值得换另一条取数路径重试；后者读的是同一文件的同一段标签，重试结果必然相同
    private sealed interface EmbeddedArt {
        data class Found(val bitmap: Bitmap) : EmbeddedArt
        data object Absent : EmbeddedArt
        data object Unavailable : EmbeddedArt
    }

    suspend fun fromUri(context: Context, uri: Uri): MusicTrack? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: context.getString(R.string.music_scanner_external_music)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: context.getString(R.string.music_scanner_unknown_artist)
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
                    val title = cursor.getString(titleIdx)?.takeIf { it.isNotBlank() }
                        ?: path.substringAfterLast('/').substringBeforeLast('.').ifBlank { context.getString(R.string.music_scanner_unknown_song) }
                    val artist = if (artistIdx >= 0) {
                        cursor.getString(artistIdx)?.takeIf { it.isNotBlank() } ?: context.getString(R.string.music_scanner_unknown_artist)
                    } else context.getString(R.string.music_scanner_unknown_artist)
                    val duration = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L
                    val albumId = if (albumIdIdx >= 0) cursor.getLong(albumIdIdx) else 0L
                    val albumName = if (albumNameIdx >= 0) cursor.getString(albumNameIdx).orEmpty() else ""
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

    private fun extractEmbeddedArt(context: Context, audioUri: Uri): EmbeddedArt {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, audioUri)
            retriever.embeddedPicture
                ?.let { MusicMetadataCache.decodeSampledBitmap(it, EMBEDDED_COVER_MAX_EDGE) }
                ?.let { EmbeddedArt.Found(it) }
                ?: EmbeddedArt.Absent
        } catch (e: Exception) {
            CrashLogManager.logException("MusicScanner", "提取内嵌封面失败: $audioUri", e)
            EmbeddedArt.Unavailable
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                CrashLogManager.logException("MusicScanner", "释放元数据读取器失败", e)
            }
        }
    }

    private fun extractEmbeddedArt(path: String): EmbeddedArt {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.embeddedPicture
                ?.let { MusicMetadataCache.decodeSampledBitmap(it, EMBEDDED_COVER_MAX_EDGE) }
                ?.let { EmbeddedArt.Found(it) }
                ?: EmbeddedArt.Absent
        } catch (e: Exception) {
            CrashLogManager.logException("MusicScanner", "提取内嵌封面失败: $path", e)
            EmbeddedArt.Unavailable
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                CrashLogManager.logException("MusicScanner", "释放元数据读取器失败", e)
            }
        }
    }

    // 读取本地音频内嵌封面原图（按内嵌封面限幅采样）：本地文件路径优先，其次 content/file URI；
    // 纯在线流无内嵌封面返回 null。
    // 保留能力，当前无调用方：封面显示统一走系统略缩图，内嵌提取不再承担任何兜底职责
    internal fun loadEmbeddedCover(context: Context, audioUri: Uri, path: String): Bitmap? {
        if (path.isNotBlank()) {
            when (val art = extractEmbeddedArt(path)) {
                is EmbeddedArt.Found -> return art.bitmap
                // 同一文件已能正常读出且无内嵌封面，换 content URI 再读结果不变，不再重复打开
                EmbeddedArt.Absent -> return null
                EmbeddedArt.Unavailable -> Unit
            }
        }
        val scheme = audioUri.scheme
        if (scheme != "content" && scheme != "file") return null
        return (extractEmbeddedArt(context, audioUri) as? EmbeddedArt.Found)?.bitmap
    }
}

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
    val uri = Uri.parse(audioUri)
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
    Uri.parse(audioUri)
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
