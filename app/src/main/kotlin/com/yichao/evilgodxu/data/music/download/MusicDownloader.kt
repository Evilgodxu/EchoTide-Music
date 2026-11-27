package com.yichao.evilgodxu.data.music.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.api.LOSSLESS_TIERS
import com.yichao.evilgodxu.data.music.api.MusicHttpClient
import com.yichao.evilgodxu.data.music.api.enrichTrackDetail
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.metadata.CoverUpdate
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataEditor
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.metadata.TagField
import com.yichao.evilgodxu.data.music.metadata.TrackTagFields
import com.yichao.evilgodxu.data.music.metadata.cachedTagFields
import com.yichao.evilgodxu.data.music.metadata.SystemThumbnailCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.analysis.isLosslessFormatName
import com.yichao.evilgodxu.data.music.analysis.TrackAudioInfoReader
import com.yichao.evilgodxu.data.music.panel.resolvePlayUrlByQuality
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind
import com.yichao.evilgodxu.data.music.playback.replaceLoadedTrackSource
import com.yichao.evilgodxu.data.music.playback.swapCurrentSourceToUri
import com.yichao.evilgodxu.data.persistence.StoragePaths
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

// 在线缓存下载：流式写入公共下载目录，完成后重定向播放源
// 封面与歌词任务入参，供同次内嵌
internal suspend fun cacheToDownloads(
    context: Context,
    result: NeteaseSongSearchResult,
    url: String,
    trackId: Long,
    playbackState: MusicPlaybackState,
    metadataEnricher: MetadataEnricher,
    playlistRefresher: PlaylistRefresher,
    coverDeferred: Deferred<ByteArray?>? = null,
    lyricDeferred: Deferred<String?>? = null,
) {
    // 切歌后缓存仍继续，完成后把索引指向本地文件
    try {
        // 按 URL 后缀推断格式，避免误存为 mp3
        val extension = url.substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in AUDIO_EXTENSIONS } ?: "mp3"
        val fileName = audioFileName(result, extension)

        val existingUri = findExistingDownload(context, fileName)
        if (existingUri != null) {
            withContext(Dispatchers.Main) {
                // 复用缓存，仅指向本地文件，播放仍在线
                updateTrackAudioUri(playbackState, trackId, existingUri)
            }
            // 等封面就绪再内嵌，扫描时生成系统缩略图
            embedCachedMetadata(context, playbackState, trackId, result, coverDeferred?.await(), lyricDeferred.awaitLyrics())
            // 补全歌词并回收无引用缓存
            metadataEnricher.enrichAndCleanup(context, playbackState)
            // 旧缓存也要入库刷新，避免从未入库
            registerCachedFileAsLocal(context, playbackState, trackId, existingUri, playlistRefresher)
            return
        }

        // 先落临时文件再写入 MediaStore，不驻留内存
        cleanupStaleTempFiles(context)
        val tempFile = File.createTempFile("download", ".$extension", context.cacheDir)
        var audioUri: String? = null
        try {
            val request = Request.Builder().url(url).build()
            MusicHttpClient.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                resp.body.byteStream().use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output, STREAM_BUFFER_SIZE) }
                }
            }

            // ≤30s 试听片段不缓存，保持在线播放
            if (isTrialAudioFile(tempFile)) return

            // 写入期间标 pending，半截条目不会被命中
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, audioMimeType(extension))
                put(MediaStore.Downloads.RELATIVE_PATH, audioCachedRelativePath())
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, contentValues)
            if (uri != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        tempFile.inputStream().use { input -> input.copyTo(os, STREAM_BUFFER_SIZE) }
                    } ?: throw IllegalStateException("无法打开下载条目输出流: $uri")
                    // 完整写入后才发布，失败不留空缓存
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null,
                    )
                    audioUri = uri.toString()
                } catch (e: Exception) {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    throw e
                }
            }
        } finally {
            tempFile.delete()
        }
        if (audioUri == null) return

        withContext(Dispatchers.Main) {
            updateTrackAudioUri(playbackState, trackId, audioUri)
        }
        // 等封面再内嵌；此时源仍在线，可整文件重写
        embedCachedMetadata(context, playbackState, trackId, result, coverDeferred?.await(), lyricDeferred.awaitLyrics())
        metadataEnricher.enrichAndCleanup(context, playbackState)
        registerCachedFileAsLocal(context, playbackState, trackId, audioUri, playlistRefresher)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicDownloader", "缓存下载文件失败", e)
    }
}

// 入库并刷新列表；按真实路径重新定位迁移条目
private suspend fun registerCachedFileAsLocal(
    context: Context,
    playbackState: MusicPlaybackState,
    trackId: Long,
    audioUri: String,
    playlistRefresher: PlaylistRefresher,
) {
    val path = queryMediaPath(context, audioUri.toUri()) ?: return
    scanAndAwait(context, path)
    playlistRefresher.refresh(context, playbackState, restoreCurrent = true)
    withContext(Dispatchers.Main) {
        val current = playbackState.currentTrack ?: return@withContext
        if (current.id != trackId) return@withContext
        val migratedIndex = playbackState.playlist.indexOfFirst { it.path == path }
        if (migratedIndex >= 0 && playbackState.playlist[migratedIndex].id != trackId) {
            // 内容同源，直接替换不做横移，避免接缝
            playbackState.beginTrackSwitch(TrackSwitchKind.SameContent)
            playbackState.currentIndex = migratedIndex
            playbackState.currentTrack = playbackState.playlist[migratedIndex]
        }
        // 用本地文件补齐信息条，避免空白
        playbackState.refreshTrackFormatInfoFromLocal(context)
    }
    // 曲库新增该曲，通知补扫副歌片段
    withContext(Dispatchers.Main) {
        playbackState.onLibraryChanged()
    }
}

internal fun sanitizeFileName(name: String): String {
    return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        .take(80)
        .trim()
}

// 文件名带平台与曲目 ID，避免同名不同曲复用
private fun audioFileName(result: NeteaseSongSearchResult, extension: String): String =
    "${sanitizeFileName(result.title)} - ${sanitizeFileName(result.artist)}" +
        " [${result.source.key}_${result.id}].$extension"

// 缓存相对路径，与统计端共用同一份事实
private fun audioCachedRelativePath(): String =
    "${Environment.DIRECTORY_DOWNLOADS}/${MusicMetadataCache.CACHE_DIR_NAME}/${StoragePaths.AUDIO_DIR_NAME}"

// 扫描后等完成，否则 IS_MUSIC 缺失会少歌
private suspend fun scanAndAwait(context: Context, path: String) {
    withTimeoutOrNull(SCAN_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ ->
                cont.resume(Unit)
            }
        }
    }
}

// 查询媒体库条目的真实路径
private fun queryMediaPath(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0).takeIf { it.isNotBlank() } else null
    }
}.getOrNull()

// 支持的扩展名，按 URL 推断格式
private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "m4a", "wav", "aac", "opus")

// 流式复制缓冲大小
private const val STREAM_BUFFER_SIZE = 64 * 1024

// 扫描等待上限，超时由后续刷新兜底
private const val SCAN_TIMEOUT_MS = 10_000L

// 临时文件前缀统一登记；冷启动按前缀回收
// 超 30 分钟未删即视为异常残留
private const val TEMP_STALE_MS = 30 * 60 * 1000L
// 清理节流 60s，避免频繁遍历目录
private const val TEMP_CLEANUP_INTERVAL_MS = 60 * 1000L

@Volatile
private var lastTempCleanupAt = 0L

// 避免缓存目录被半截文件长期占用
private fun cleanupStaleTempFiles(context: Context) {
    val now = System.currentTimeMillis()
    if (now - lastTempCleanupAt < TEMP_CLEANUP_INTERVAL_MS) return
    lastTempCleanupAt = now
    runCatching {
        context.cacheDir.listFiles { f -> StoragePaths.TEMP_FILE_PREFIXES.any { f.name.startsWith(it) } }
            ?.forEach { f -> if (now - f.lastModified() > TEMP_STALE_MS) f.delete() }
    }
}

private fun isTrialAudioFile(file: File): Boolean {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        durationMs in 1..30_000
    } catch (e: Exception) {
        false
    } finally {
        retriever.release()
    }
}

// 读不到格式或非无损即升级失败
private fun isLosslessAudioFile(file: File): Boolean {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
        val name = TrackAudioInfoReader.mimeToFormatName(mime)
        name != null && isLosslessFormatName(name)
    } catch (e: Exception) {
        false
    } finally {
        runCatching { retriever.release() }
    }
}

private fun audioMimeType(extension: String): String = when (extension) {
    "flac" -> "audio/flac"
    "ogg" -> "audio/ogg"
    "m4a" -> "audio/mp4"
    "wav" -> "audio/x-wav"
    "aac" -> "audio/aac"
    "opus" -> "audio/opus"
    else -> "audio/mpeg"
}

internal suspend fun findExistingDownload(
    context: Context,
    fileName: String,
): String? = withContext(Dispatchers.IO) {
    try {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        // 只认已发布条目，pending 内容不完整
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Downloads.RELATIVE_PATH} = ? AND " +
                "${MediaStore.Downloads.IS_PENDING} = 0"
        val args = arrayOf(fileName, Environment.DIRECTORY_DOWNLOADS + "/YiChao/Audio/")
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return@withContext Uri.withAppendedPath(collection, id.toString()).toString()
            }
        }
    } catch (e: Exception) {
        CrashLogManager.logException("MusicDownloader", "查询已下载文件失败", e)
    }
    null
}

internal suspend fun updateTrackAudioUri(
    playbackState: MusicPlaybackState,
    trackId: Long,
    audioUri: String,
) {
    val idx = playbackState.playlist.indexOfFirst { it.id == trackId }
    if (idx < 0) return
    val updated = playbackState.playlist[idx].copy(audioUri = audioUri)
    val list = playbackState.playlist.toMutableList()
    list[idx] = updated
    playbackState.playlist = list
    if (playbackState.currentTrack?.id == trackId) {
        playbackState.currentTrack = updated
    }
    // 有过滤时回写默认队列，否则退回旧 URI
    if (playbackState.queueFilter != null) {
        playbackState.defaultPlaylist = playbackState.defaultPlaylist.map { item ->
            if (item.id == trackId) updated else item
        }
    }
    playbackState.persistPlaylist()
}

// 写入标题/艺术家与封面原图，扫描生成系统缩略图
// 歌词内嵌等待上限 8s，避免拖慢写入
private const val LYRIC_EMBED_WAIT_MS = 8_000L

// 详情补全等待上限，超时用搜索字段
private const val DETAIL_WAIT_MS = 5_000L

/** 等待歌词并取增强 LRC；超时或失败只放弃内嵌 */
private suspend fun Deferred<String?>?.awaitLyrics(): String? =
    this?.let { runCatching { withTimeoutOrNull(LYRIC_EMBED_WAIT_MS) { it.await() } }.getOrNull() }

private suspend fun embedCachedMetadata(
    context: Context,
    playbackState: MusicPlaybackState,
    trackId: Long,
    result: NeteaseSongSearchResult,
    coverOriginal: ByteArray?,
    lyrics: String?,
) {
    val track = playbackState.playlist.firstOrNull { it.id == trackId } ?: return
    // 详情接口补全缺少字段，失败则按搜索字段写
    val detailed = withTimeoutOrNull(DETAIL_WAIT_MS) { enrichTrackDetail(result) } ?: result
    val fields = cachedTagFields(track, detailed, lyrics)
    val coverUpdate = coverOriginal?.let { CoverUpdate.Set(it) } ?: CoverUpdate.Keep
    val ok = MusicMetadataWriter.writeFields(context, track, fields, coverUpdate)
    if (!ok) {
        CrashLogManager.logException(
            "MusicDownloader",
            "内嵌缓存元数据失败: 歌曲=${track.title} - ${track.artist}, uri=${track.audioUri}, 封面=${coverOriginal?.size ?: 0}B",
        )
    }
}

// 升级无损：下载→改索引→写标签→续播→删旧文件
internal suspend fun upgradeTrackToLossless(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    candidate: NeteaseSongSearchResult,
): Boolean {
    if (!track.isLocalAudioSource) return false
    // 先 Hi-Res 后普通无损，按下载验证结果回退
    var newUri: String? = null
    for (tier in LOSSLESS_TIERS) {
        val candidateUrl = resolvePlayUrlByQuality(playbackState.proxySourceStore, candidate, tier) ?: continue
        newUri = downloadLosslessToDownloads(context, candidate, candidateUrl)
        if (newUri != null) break
    }
    val downloadedUri = newUri ?: return false
    // 承接旧缩略图与背景色到新 URI，避免闪占位符
    SystemThumbnailCache.remap(track.audioUri, downloadedUri)
    playbackState.backgroundColors.remapUri(track.audioUri, downloadedUri)
    val newPath = queryMediaPath(context, downloadedUri.toUri()).orEmpty()
    // 记录进度，新文件起播时还原
    val resumePosition = withContext(Dispatchers.Main) {
        playbackState.mediaController?.currentPosition?.coerceAtLeast(0L) ?: 0L
    }
    // 索引指向新文件，同时更新本地路径
    val upgradedIndex = withContext(Dispatchers.Main) {
        val idx = playbackState.playlist.indexOfFirst { it.id == track.id }
        if (idx < 0) return@withContext -1
        val updated = playbackState.playlist[idx].copy(audioUri = downloadedUri, path = newPath)
        val list = playbackState.playlist.toMutableList()
        list[idx] = updated
        playbackState.playlist = list
        if (playbackState.currentTrack?.id == track.id) {
            playbackState.currentTrack = updated
        }
            // 有过滤时一并回写默认队列
        if (playbackState.queueFilter != null) {
            playbackState.defaultPlaylist = playbackState.defaultPlaylist.map { item ->
                if (item.id == track.id) updated else item
            }
        }
        playbackState.persistPlaylist()
        idx
    }
    // 先写元数据再起播，避免播放中被重写
    embedUpgradeMetadata(context, playbackState, track, candidate)
    // 换源续播；队列残留项也须换成新 URI
    val updatedTrack = withContext(Dispatchers.Main) {
        playbackState.playlist.firstOrNull { it.id == track.id }
    }
    val sourceReplaced = withContext(Dispatchers.Main) {
        val currentMediaId = playbackState.mediaController?.currentMediaItem?.mediaId
        val target = updatedTrack ?: track.copy(audioUri = downloadedUri, path = newPath)
        when {
            upgradedIndex >= 0 && currentMediaId == track.id.toString() ->
                swapCurrentSourceToUri(playbackState, upgradedIndex, resumePosition)
            // 状态层无此曲时仍替换队列项，失败则保留旧文件
            else -> replaceLoadedTrackSource(playbackState, target)
        }
    }
    // 立即持久化新 URI，否则窗口内退出会落盘旧值
    playbackState.persistState()
    // 主动刷新格式信息条，换源不触发切歌回调
    withContext(Dispatchers.Main) {
        playbackState.refreshIdleTrackFormatInfo(context)
    }
    // 引用全换新后才删旧文件，失败则保留
    if (sourceReplaced) {
        deleteOldAudioFile(context, track, downloadedUri)
        // 扫描入库新文件，移除旧条目
        if (newPath.isNotBlank()) scanAndAwait(context, newPath)
        // 迁移收藏与歌单关联到新 ID，否则随旧 ID 丢失
        if (newPath.isNotBlank()) {
            findAudioMediaId(context, newPath)?.takeIf { it != track.id }?.let { newId ->
                playbackState.migrateTrackId(track.id, newId)
            }
        }
    } else {
        CrashLogManager.logException(
            "MusicDownloader",
            "无损升级未完成播放源替换，保留旧文件: 歌曲=${track.title}, 旧URI=${track.audioUri}",
        )
    }
    return true
}

// 按真实路径查音频条目 ID，扫描会另建记录
private fun findAudioMediaId(context: Context, path: String): Long? =
    runCatching {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DATA}=?",
            arrayOf(path),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }.getOrNull()

// 以旧标签为底，仅标题与艺术家用候选覆盖，其余只补空
private suspend fun embedUpgradeMetadata(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    candidate: NeteaseSongSearchResult,
) {
    val updated = playbackState.playlist.firstOrNull { it.id == track.id } ?: track
    try {
        val oldTags = withContext(Dispatchers.IO) { MusicMetadataEditor.read(context, track) }
        val coverBytes = MusicMetadataEditor.readCover(context, track)
        val fields = TrackTagFields.overlay(oldTags.fields) {
            put(TagField.TITLE, candidate.title)
            put(TagField.ARTIST, candidate.artist)
            oldTags.lyrics?.takeIf { it.isNotBlank() }?.let { put(TagField.LYRICS, it) }
            // 只填空位，已有值优先
            fun fillAbsent(field: TagField, value: String?) {
                if (oldTags.fields[field].isNullOrBlank()) put(field, value)
            }
            fillAbsent(TagField.ALBUM, candidate.album)
            fillAbsent(TagField.DATE, candidate.releaseDate)
            fillAbsent(TagField.TRACK_NUMBER, candidate.trackNumber?.toString())
            fillAbsent(TagField.DISC_NUMBER, candidate.discNumber?.toString())
            fillAbsent(TagField.COPYRIGHT, candidate.copyright)
        }
        val coverUpdate = coverBytes?.let { CoverUpdate.Set(it) } ?: CoverUpdate.Keep
        MusicMetadataWriter.writeFields(context, updated, fields, coverUpdate)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicDownloader", "内嵌无损升级元数据失败: 歌曲=${candidate.title}", e)
    }
}

// 下载无损到公共目录；试听或失败返回 null
private suspend fun downloadLosslessToDownloads(
    context: Context,
    result: NeteaseSongSearchResult,
    url: String,
): String? = withContext(Dispatchers.IO) {
    try {
        // 按 URL 后缀推断格式，默认 flac
        val extension = url.substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in AUDIO_EXTENSIONS } ?: "flac"
        val fileName = audioFileName(result, extension)
        cleanupStaleTempFiles(context)
        val tempFile = File.createTempFile("upgrade", ".$extension", context.cacheDir)
        try {
            val request = Request.Builder().url(url).build()
            MusicHttpClient.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body.byteStream().use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output, STREAM_BUFFER_SIZE) }
                }
            }
            // 试听片段不入库不升级
            if (isTrialAudioFile(tempFile)) return@withContext null
            // 校验确为无损，避免有损顶替
            if (!isLosslessAudioFile(tempFile)) return@withContext null
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, audioMimeType(extension))
                put(MediaStore.Downloads.RELATIVE_PATH, audioCachedRelativePath())
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(collection, contentValues)
                ?: return@withContext null
            try {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    tempFile.inputStream().use { input -> input.copyTo(os, STREAM_BUFFER_SIZE) }
                } ?: throw IllegalStateException("无法打开无损下载条目输出流: $uri")
                // 完整写入才发布，失败回收新条目
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null,
                    null,
                )
            } catch (e: Exception) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw e
            }
            uri.toString()
        } finally {
            tempFile.delete()
        }
    } catch (e: Exception) {
        CrashLogManager.logException("MusicDownloader", "无损升级下载失败: 歌曲=${result.title}", e)
        null
    }
}

// 删除旧文件并清理媒体条目；起播后再删
private suspend fun deleteOldAudioFile(context: Context, track: MusicTrack, newUri: String) {
    if (track.audioUri == newUri) return
    val scheme = runCatching { track.audioUri.toUri().scheme }.getOrNull()
    if (scheme != "content" && scheme != "file") return
    withContext(Dispatchers.IO) {
        runCatching {
            track.audioUri.toUri().let { context.contentResolver.delete(it, null, null) }
        }
        track.path.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { File(path).delete() }
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        }
    }
}
