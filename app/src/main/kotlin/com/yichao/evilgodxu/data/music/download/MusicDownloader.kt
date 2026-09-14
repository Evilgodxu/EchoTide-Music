package com.yichao.evilgodxu.data.music.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yichao.evilgodxu.data.cache.CacheInventory
import com.yichao.evilgodxu.data.music.api.MusicHttpClient
import com.yichao.evilgodxu.data.music.api.MusicQuality
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.metadata.SystemThumbnailCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.analysis.isLosslessFormatName
import com.yichao.evilgodxu.data.music.analysis.TrackAudioInfoReader
import com.yichao.evilgodxu.data.music.panel.resolvePlayUrlByQuality
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.swapCurrentSourceToUri
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request

// 在线歌曲缓存下载：流式下载到公共下载目录的媒体集合条目，完成后重定向播放源。
// coverDeferred 为在线封面下载任务：内嵌元数据前先等待其就绪，并取回下载到的原图字节用于内嵌；
// lyricDeferred 为在线歌词任务：返回增强 LRC 文本，与标题/艺术家/封面在同一次重写中写入，
// 使歌词不再只存在于歌词缓存文件（该文件丢失后无法从网络自动恢复）
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
    // 缓存进行中的曲目切歌后仍保留在播放列表，等待下载完成将索引指向本地文件
    playbackState.cacheInProgressIds.add(trackId)
    try {
        // 按实际 URL 后缀推断格式，避免高音质文件误存为 mp3
        val extension = url.substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in AUDIO_EXTENSIONS } ?: "mp3"
        val fileName = "${sanitizeFileName(result.title)} - ${sanitizeFileName(result.artist)}.$extension"

        val existingUri = findExistingDownload(context, fileName)
        if (existingUri != null) {
            withContext(Dispatchers.Main) {
                // 复用已有缓存：仅把播放列表索引指向本地文件，当前播放仍保持在线流
                updateTrackAudioUri(playbackState, trackId, existingUri)
            }
            // 等待在线封面下载就绪后再内嵌：让下载到的封面原图与标题/艺术家一并写入缓存文件，
            // 写入触发的媒体扫描会为该文件生成系统封面略缩图
            embedCachedMetadata(context, playbackState, trackId, coverDeferred?.await(), lyricDeferred.awaitLyrics())
            // 补全歌词并回收无引用的歌词缓存
            metadataEnricher.enrichAndCleanup(context, playbackState)
            // 复用旧缓存同样登记本地音频库并刷新，避免旧缓存文件从未入库
            registerCachedFileAsLocal(context, playbackState, trackId, existingUri, playlistRefresher)
            return
        }

        // 流式下载到应用缓存临时文件，再写入 MediaStore，避免整曲驻留内存
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

            // 试听片段（≤30 秒）不缓存，保持在线播放
            if (isTrialAudioFile(tempFile)) return

            // 下载集合用 Downloads + RELATIVE_PATH 写入公共下载目录，无写权限时 insert 直接失败，维持在线播放
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, audioMimeType(extension))
                put(MediaStore.Downloads.RELATIVE_PATH, audioCachedRelativePath())
            }
            val uri = context.contentResolver.insert(collection, contentValues)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    tempFile.inputStream().use { input -> input.copyTo(os, STREAM_BUFFER_SIZE) }
                }
                audioUri = uri.toString()
            }
        } finally {
            tempFile.delete()
        }
        if (audioUri == null) return

        withContext(Dispatchers.Main) {
            updateTrackAudioUri(playbackState, trackId, audioUri)
        }
        // 等待在线封面下载协程结束再内嵌：确保封面原图字节已就绪，
        // 消除“封面未就绪即触发写入导致元数据整体丢失”的时序竞态
        // 缓存完成时播放源仍是在线流，文件未被播放占用，可安全整文件重写；
        // 将标题/艺术家与下载到的封面原图一次写入本地文件，刷新后不再丢失元数据
        embedCachedMetadata(context, playbackState, trackId, coverDeferred?.await(), lyricDeferred.awaitLyrics())
        // 下载完成：补全歌词并回收无引用的歌词缓存
        metadataEnricher.enrichAndCleanup(context, playbackState)
        // 缓存完成：登记本地音频库并刷新播放列表，建立本地索引
        registerCachedFileAsLocal(context, playbackState, trackId, audioUri, playlistRefresher)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicDownloader", "缓存下载文件失败", e)
    } finally {
        playbackState.cacheInProgressIds.remove(trackId)
    }
}

// 缓存完成后把文件登记进本地音频库并刷新播放列表，建立本地索引；
// 当前播放的缓存曲目刷新后按真实路径重新定位到迁移条目，避免换 ID 后与播放列表脱节
private suspend fun registerCachedFileAsLocal(
    context: Context,
    playbackState: MusicPlaybackState,
    trackId: Long,
    audioUri: String,
    playlistRefresher: PlaylistRefresher,
) {
    val path = queryMediaPath(context, Uri.parse(audioUri)) ?: return
    // 等待扫描完成再刷新，确保 MusicScanner 能读到新条目
    withTimeoutOrNull(SCAN_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ ->
                cont.resume(Unit)
            }
        }
    }
    playlistRefresher.refresh(context, playbackState, restoreCurrent = true)
    withContext(Dispatchers.Main) {
        val current = playbackState.currentTrack ?: return@withContext
        if (current.id != trackId) return@withContext
        val migratedIndex = playbackState.playlist.indexOfFirst { it.path == path }
        if (migratedIndex >= 0 && playbackState.playlist[migratedIndex].id != trackId) {
            playbackState.currentIndex = migratedIndex
            playbackState.currentTrack = playbackState.playlist[migratedIndex]
        }
        // 缓存完成后以本地文件补齐当前曲目信息条，避免在线播放期间空白
        playbackState.refreshTrackFormatInfoFromLocal(context)
    }
}

internal fun sanitizeFileName(name: String): String {
    return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        .take(80)
        .trim()
}

// 在线歌曲缓存的下载集合相对路径：目录名取自缓存元数据与台账，与统计端共用同一份事实
private fun audioCachedRelativePath(): String =
    "${Environment.DIRECTORY_DOWNLOADS}/${MusicMetadataCache.CACHE_DIR_NAME}/${CacheInventory.AUDIO_DIR_NAME}"

// 歌单同步批量下载：把在线曲目下载到公共下载目录并写入标题/艺术家/封面，
// 返回库内文件名（含扩展名）供刷新后按路径匹配入库；已存在或试听片段返回 null
internal suspend fun downloadTrackToLibrary(
    context: Context,
    result: NeteaseSongSearchResult,
    url: String,
    coverBytes: ByteArray? = null,
): String? = withContext(Dispatchers.IO) {
    try {
        val extension = url.substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in AUDIO_EXTENSIONS } ?: "mp3"
        val fileName = "${sanitizeFileName(result.title)} - ${sanitizeFileName(result.artist)}.$extension"
        if (findExistingDownload(context, fileName) != null) return@withContext fileName
        cleanupStaleTempFiles(context)
        val tempFile = File.createTempFile("download", ".$extension", context.cacheDir)
        try {
            val request = Request.Builder().url(url).build()
            MusicHttpClient.client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body.byteStream().use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output, STREAM_BUFFER_SIZE) }
                }
            }
            // 试听片段（≤30 秒）不缓存入库，与播放缓存规则一致
            if (isTrialAudioFile(tempFile)) return@withContext null
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, audioMimeType(extension))
                put(MediaStore.Downloads.RELATIVE_PATH, audioCachedRelativePath())
            }
            val uri = context.contentResolver.insert(collection, contentValues)
            if (uri == null) return@withContext null
            context.contentResolver.openOutputStream(uri)?.use { os ->
                tempFile.inputStream().use { input -> input.copyTo(os, STREAM_BUFFER_SIZE) }
            }
            // 只写本次同步下载的文件：写入标题/艺术家/封面并触发媒体库扫描
            val path = queryMediaPath(context, uri)
            if (path != null) {
                runCatching {
                    MusicMetadataWriter.writeMetadataToSource(
                        context,
                        MusicTrack(
                            id = 0L,
                            path = path,
                            audioUri = uri.toString(),
                            title = result.title,
                            artist = result.artist,
                            duration = 0L,
                            albumId = 0L,
                        ),
                        result.title,
                        result.artist,
                        coverBytes,
                    )
                }
                MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
            }
            fileName
        } finally {
            tempFile.delete()
        }
    } catch (e: Exception) {
        CrashLogManager.logException("MusicDownloader", "歌单同步下载失败: 歌曲=${result.title}", e)
        null
    }
}

// 查询已写入媒体库条目的真实文件路径
private fun queryMediaPath(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0).takeIf { it.isNotBlank() } else null
    }
}.getOrNull()

// 支持的音频扩展名，用于按实际 URL 推断缓存格式
private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "m4a", "wav", "aac", "opus")

// 流式复制音频数据的读缓冲大小
private const val STREAM_BUFFER_SIZE = 64 * 1024

// 等待媒体扫描完成的上限：超时后仍继续刷新，新条目由后续媒体变更刷新兜底
private const val SCAN_TIMEOUT_MS = 10_000L

// 下载临时文件前缀由缓存台账统一登记（CacheInventory.TEMP_FILE_PREFIXES）：
// 冷启动回收按同前缀整批清理，本文件只负责清理本次进程内的异常残留
// 视为遗留的临时文件存在时长：进程被杀时 finally 不一定执行，超时未删即判定为异常中断残留
private const val TEMP_STALE_MS = 30 * 60 * 1000L
// 临时文件清理节流：下载密集场景避免每次下载都遍历缓存目录
private const val TEMP_CLEANUP_INTERVAL_MS = 60 * 1000L

@Volatile
private var lastTempCleanupAt = 0L

// 清理异常退出遗留的下载临时文件，避免缓存目录被半截文件长期占用
private fun cleanupStaleTempFiles(context: Context) {
    val now = System.currentTimeMillis()
    if (now - lastTempCleanupAt < TEMP_CLEANUP_INTERVAL_MS) return
    lastTempCleanupAt = now
    runCatching {
        context.cacheDir.listFiles { f -> CacheInventory.TEMP_FILE_PREFIXES.any { f.name.startsWith(it) } }
            ?.forEach { f -> if (now - f.lastModified() > TEMP_STALE_MS) f.delete() }
    }
}

// 探测音频时长是否 ≤30 秒的试听片段
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

// 校验文件确为无损音频格式，读不到格式或非无损一律视为升级失败
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

// 查询公共下载目录下是否已存在同名缓存文件，命中时复用其 Uri
internal suspend fun findExistingDownload(
    context: Context,
    fileName: String,
): String? = withContext(Dispatchers.IO) {
    try {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Downloads.RELATIVE_PATH} = ?"
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

// 缓存完成后把曲目的播放地址指向本地文件
internal fun updateTrackAudioUri(
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
    playbackState.persistPlaylist()
}

// 缓存完成后把在线播放时的标题/艺术家与封面原图写入本地文件。
// 封面用本次下载到的原图字节：写入后系统媒体扫描会为该文件生成封面略缩图，
// 显示端（列表/面板/首页）读系统略缩图，系统媒体面板经 artworkUri 取同一份，应用不落盘封面缓存
// 在线歌词内嵌的等待上界：超过即放弃本次内嵌，避免拖慢音频文件的元数据写入
private const val LYRIC_EMBED_WAIT_MS = 8_000L

/**
 * 等待在线歌词任务并取回增强 LRC 文本。歌词是内嵌的附加项：等待设上界，
 * 超时或失败都只放弃本次内嵌 —— 歌词自身的缓存落盘由该任务独立完成，不受影响；
 * 绝不能让音频文件的标题/封面写入被歌词请求无限期拖住。
 */
private suspend fun Deferred<String?>?.awaitLyrics(): String? =
    this?.let { runCatching { withTimeoutOrNull(LYRIC_EMBED_WAIT_MS) { it.await() } }.getOrNull() }

private suspend fun embedCachedMetadata(
    context: Context,
    playbackState: MusicPlaybackState,
    trackId: Long,
    coverOriginal: ByteArray?,
    lyrics: String?,
) {
    val track = playbackState.playlist.firstOrNull { it.id == trackId } ?: return
    if (coverOriginal == null) {
        CrashLogManager.logException(
            "MusicDownloader",
            "缓存完成时在线封面尚未就绪，仅写入标题/艺术家: 歌曲=${track.title} - ${track.artist}",)
    }
    val ok = MusicMetadataWriter.writeMetadataToSource(context, track, track.title, track.artist, coverOriginal, lyrics)
    if (!ok) {
        CrashLogManager.logException(
            "MusicDownloader",
            "内嵌缓存元数据失败: 歌曲=${track.title} - ${track.artist}, uri=${track.audioUri}, 封面=${coverOriginal?.size ?: 0}B",
        )
    }
}

// 本地曲目按用户确认的在线候选升级为无损：解析无损直链并下载，新文件替换旧文件后按原进度直接续播。
// 流程刻意保持线性：下载 → 索引转向新文件 → 写元数据 → 起播 → 删旧文件，不再做播放状态接替与延迟删除
internal suspend fun upgradeTrackToLossless(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    candidate: NeteaseSongSearchResult,
): Boolean {
    if (!track.isLocalAudioSource) return false
    val url = resolvePlayUrlByQuality(context, candidate, MusicQuality.LOSSLESS) ?: return false
    val newUri = downloadLosslessToDownloads(context, candidate, url) ?: return false
    // 无损升级只换音频文件、封面不变：先承接旧文件的系统略缩图与背景取色结果到新 URI，
    // 避免 audioUri 切换后封面闪占位符、背景回落默认色（新文件系统略缩图需等媒体扫描就绪）
    SystemThumbnailCache.remap(track.audioUri, newUri)
    playbackState.remapGradientUri(track.audioUri, newUri)
    val newPath = queryMediaPath(context, Uri.parse(newUri)).orEmpty()
    // 升级前的播放进度：新文件起播时还原到同一位置
    val resumePosition = withContext(Dispatchers.Main) {
        playbackState.mediaController?.currentPosition?.coerceAtLeast(0L) ?: 0L
    }
    // 索引转向新文件：同时更新本地路径，使曲目身份指向新的无损文件
    val upgradedIndex = withContext(Dispatchers.Main) {
        val idx = playbackState.playlist.indexOfFirst { it.id == track.id }
        if (idx < 0) return@withContext -1
        val updated = playbackState.playlist[idx].copy(audioUri = newUri, path = newPath)
        val list = playbackState.playlist.toMutableList()
        list[idx] = updated
        playbackState.playlist = list
        if (playbackState.currentTrack?.id == track.id) {
            playbackState.currentTrack = updated
        }
        playbackState.persistPlaylist()
        idx
    }
    // 写入标题/艺术家；封面沿用旧文件内嵌原图。
    // 先写元数据再起播：避免新文件在播放中被重写导致无声与进度回退
    embedUpgradeMetadata(context, playbackState, track, candidate)
    // 替换后按原进度直接续播：换源不重建播放队列，避免起播被中断
    withContext(Dispatchers.Main) {
        if (upgradedIndex >= 0 && playbackState.currentTrack?.id == track.id) {
            swapCurrentSourceToUri(playbackState, upgradedIndex, resumePosition)
        }
    }
    // 立即持久化新 URI 与续播位置：换源不经过切歌回调，若等周期性存储（3 秒节流）写入，
    // 用户在窗口内退出会把旧 URI 落盘，重启后旧文件已删除导致曲目不可用、进度丢失
    playbackState.persistState()
    // 播放源已切到新文件，旧文件不再需要，直接删除
    deleteOldAudioFile(context, track, newUri)
    // 触发媒体扫描：新文件入库，旧文件条目同步移除
    if (newPath.isNotBlank()) {
        MediaScannerConnection.scanFile(context, arrayOf(newPath), null, null)
    }
    return true
}

// 写入升级后新文件的标题/艺术家：封面沿用旧文件内嵌的原图，
// 旧文件无内嵌封面时不主动下载，留空交由占位符显示
private suspend fun embedUpgradeMetadata(
    context: Context,
    playbackState: MusicPlaybackState,
    track: MusicTrack,
    candidate: NeteaseSongSearchResult,
) {
    val updated = playbackState.playlist.firstOrNull { it.id == track.id } ?: track
    val coverBytes = extractEmbeddedCover(context, track)
    try {
        MusicMetadataWriter.writeMetadataToSource(context, updated, candidate.title, candidate.artist, coverBytes)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicDownloader", "内嵌无损升级元数据失败: 歌曲=${candidate.title}", e)
    }
}

// 提取旧本地文件内嵌的封面原图字节：文件路径优先，其次本地 content/file URI；无内嵌或读取失败返回 null
private suspend fun extractEmbeddedCover(context: Context, track: MusicTrack): ByteArray? = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        if (track.path.isNotBlank()) {
            retriever.setDataSource(track.path)
        } else {
            val uri = Uri.parse(track.audioUri)
            if (uri.scheme != "content" && uri.scheme != "file") return@withContext null
            retriever.setDataSource(context, uri)
        }
        retriever.embeddedPicture
    } catch (e: Exception) {
        CrashLogManager.logException("MusicDownloader", "提取旧文件内嵌封面失败: 歌曲=${track.title}", e)
        null
    } finally {
        runCatching { retriever.release() }
    }
}

// 下载无损文件到公共下载目录并返回内容 Uri；试听片段或写入失败返回 null
private suspend fun downloadLosslessToDownloads(
    context: Context,
    result: NeteaseSongSearchResult,
    url: String,
): String? = withContext(Dispatchers.IO) {
    try {
        // 按实际 URL 后缀推断格式，无损直链通常为 flac
        val extension = url.substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in AUDIO_EXTENSIONS } ?: "flac"
        val fileName = "${sanitizeFileName(result.title)} - ${sanitizeFileName(result.artist)}.$extension"
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
            // 无损直链可能返回试听片段（≤30 秒），不入库不升级
            if (isTrialAudioFile(tempFile)) return@withContext null
            // 校验确为无损格式，避免平台未提供无损时以有损文件顶替
            if (!isLosslessAudioFile(tempFile)) return@withContext null
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, audioMimeType(extension))
                put(MediaStore.Downloads.RELATIVE_PATH, audioCachedRelativePath())
            }
            val uri = context.contentResolver.insert(collection, contentValues)
            if (uri == null) return@withContext null
            context.contentResolver.openOutputStream(uri)?.use { os ->
                tempFile.inputStream().use { input -> input.copyTo(os, STREAM_BUFFER_SIZE) }
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

// 删除升级前的旧本地文件：经 MediaStore 删除并清理媒体条目，失败时直删路径并触发媒体扫描。
// 起播后再删：播放源已切到新文件，旧文件即使仍被播放器持有句柄也不影响新文件播放
private suspend fun deleteOldAudioFile(context: Context, track: MusicTrack, newUri: String) {
    if (track.audioUri == newUri) return
    val scheme = runCatching { Uri.parse(track.audioUri).scheme }.getOrNull()
    if (scheme != "content" && scheme != "file") return
    withContext(Dispatchers.IO) {
        runCatching {
            Uri.parse(track.audioUri).let { context.contentResolver.delete(it, null, null) }
        }
        track.path.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { File(path).delete() }
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        }
    }
}
