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
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.metadata.CoverUpdate
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataEditor
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataWriter
import com.yichao.evilgodxu.data.music.metadata.TagField
import com.yichao.evilgodxu.data.music.metadata.TrackTagFields
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
    // 缓存进行中的曲目切歌后仍保留在播放列表，等待下载完成将索引指向本地文件。
    // 去重由调用方在启动前完成：同一曲目只允许一个缓存任务占用该标记
    try {
        // 按实际 URL 后缀推断格式，避免高音质文件误存为 mp3
        val extension = url.substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it in AUDIO_EXTENSIONS } ?: "mp3"
        val fileName = audioFileName(result, extension)

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

            // 下载集合用 Downloads + RELATIVE_PATH 写入公共下载目录，无写权限时 insert 直接失败，维持在线播放。
            // 写入期间标为 pending：复制中断留下的半截条目不会被后续请求当作可用缓存命中
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
                    // 完整写入后才发布：发布前的条目对查询不可见，失败回收也不会留下空缓存
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
        // 等封面任务结束再内嵌：封面未就绪即写入会让元数据整体丢失；
        // 此时播放源仍是在线流，文件未被占用，可安全整文件重写
        embedCachedMetadata(context, playbackState, trackId, coverDeferred?.await(), lyricDeferred.awaitLyrics())
        metadataEnricher.enrichAndCleanup(context, playbackState)
        registerCachedFileAsLocal(context, playbackState, trackId, audioUri, playlistRefresher)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicDownloader", "缓存下载文件失败", e)
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
    val path = queryMediaPath(context, audioUri.toUri()) ?: return
    scanAndAwait(context, path)
    playlistRefresher.refresh(context, playbackState, restoreCurrent = true)
    withContext(Dispatchers.Main) {
        val current = playbackState.currentTrack ?: return@withContext
        if (current.id != trackId) return@withContext
        val migratedIndex = playbackState.playlist.indexOfFirst { it.path == path }
        if (migratedIndex >= 0 && playbackState.playlist[migratedIndex].id != trackId) {
            // 迁移到本地曲目后标识变了，但画面同源：类型按内容同源记下，直接替换封面与文案，
            // 不做横移——两张同源的封面错位反而会显出一道接缝
            playbackState.beginTrackSwitch(TrackSwitchKind.SameContent)
            playbackState.currentIndex = migratedIndex
            playbackState.currentTrack = playbackState.playlist[migratedIndex]
        }
        // 缓存完成后以本地文件补齐当前曲目信息条，避免在线播放期间空白
        playbackState.refreshTrackFormatInfoFromLocal(context)
    }
    // 曲库新增了这首歌：通知状态层补扫新曲目的副歌片段（纯本地，不联网）
    withContext(Dispatchers.Main) {
        playbackState.onLibraryChanged()
    }
}

internal fun sanitizeFileName(name: String): String {
    return name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        .take(80)
        .trim()
}

// 缓存落点带平台与稳定曲目 ID：同名/同歌手的 A/B 与 A:B 清洗后会得到同一文件名，
// 仅凭标题与歌手无法区分实际曲目，复用到的可能完全是另一首
private fun audioFileName(result: NeteaseSongSearchResult, extension: String): String =
    "${sanitizeFileName(result.title)} - ${sanitizeFileName(result.artist)}" +
        " [${result.source.key}_${result.id}].$extension"

// 在线歌曲缓存的下载集合相对路径：目录名取自缓存元数据与台账，与统计端共用同一份事实
private fun audioCachedRelativePath(): String =
    "${Environment.DIRECTORY_DOWNLOADS}/${MusicMetadataCache.CACHE_DIR_NAME}/${StoragePaths.AUDIO_DIR_NAME}"

// 触发媒体扫描并等待完成：扫描经回调异步返回，不等待就刷新曲库，MusicScanner 读不到新条目
// （IS_MUSIC/DURATION 由扫描写入，缺失时会被媒体库查询过滤），调用方会因此少歌
private suspend fun scanAndAwait(context: Context, path: String) {
    withTimeoutOrNull(SCAN_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ ->
                cont.resume(Unit)
            }
        }
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

// 下载临时文件前缀由共享路径事实统一登记（StoragePaths.TEMP_FILE_PREFIXES）：
// 冷启动回收按同前缀整批清理，本文件只负责清理本次进程内的异常残留
// 视为遗留的临时文件存在时长：进程被杀时 finally 不一定执行，超时未删即判定为异常中断残留
private const val TEMP_STALE_MS = 30 * 60 * 1000L
// 临时文件清理节流：下载密集场景避免每次下载都遍历缓存目录
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

// 读不到格式或非无损一律视为升级失败
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
        // 只认已发布的条目：写入中断留下的 pending 条目内容不完整，不能当作缓存
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
    playbackState.persistPlaylist()
}

// 把在线播放时的标题/艺术家与封面原图写入本地文件。封面用本次下载到的原图字节：
// 写入触发的媒体扫描会为该文件生成系统略缩图，显示端与系统媒体面板都读那一份
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
    // 无损档内先试 Hi-Res，其次普通无损：回退必须覆盖「下载并验证出有效无损文件」，
    // 只判断 URL 是否解析成功时，Hi-Res 返回坏直链会让本可用的普通无损也轮不到
    var newUri: String? = null
    for (tier in LOSSLESS_TIERS) {
        val candidateUrl = resolvePlayUrlByQuality(context, candidate, tier) ?: continue
        newUri = downloadLosslessToDownloads(context, candidate, candidateUrl)
        if (newUri != null) break
    }
    val downloadedUri = newUri ?: return false
    // 无损升级只换音频文件、封面不变：先承接旧文件的系统略缩图与背景取色结果到新 URI，
    // 避免 audioUri 切换后封面闪占位符、背景回落默认色（新文件系统略缩图需等媒体扫描就绪）
    SystemThumbnailCache.remap(track.audioUri, downloadedUri)
    playbackState.remapBackgroundColorUri(track.audioUri, downloadedUri)
    val newPath = queryMediaPath(context, downloadedUri.toUri()).orEmpty()
    // 升级前的播放进度：新文件起播时还原到同一位置
    val resumePosition = withContext(Dispatchers.Main) {
        playbackState.mediaController?.currentPosition?.coerceAtLeast(0L) ?: 0L
    }
    // 索引转向新文件：同时更新本地路径，使曲目身份指向新的无损文件
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
        playbackState.persistPlaylist()
        idx
    }
    // 写入标题/艺术家；封面沿用旧文件内嵌原图。
    // 先写元数据再起播：避免新文件在播放中被重写导致无声与进度回退
    embedUpgradeMetadata(context, playbackState, track, candidate)
    // 替换后按原进度直接续播。用户已切到别的曲目时，目标项仍可能留在播放器队列里，
    // 也必须把那一项的 URI 一并换掉，否则后续自然播放仍指向即将删除的旧文件
    val updatedTrack = withContext(Dispatchers.Main) {
        playbackState.playlist.firstOrNull { it.id == track.id }
    }
    val sourceReplaced = withContext(Dispatchers.Main) {
        val currentMediaId = playbackState.mediaController?.currentMediaItem?.mediaId
        val target = updatedTrack ?: track.copy(audioUri = downloadedUri, path = newPath)
        when {
            upgradedIndex >= 0 && currentMediaId == track.id.toString() ->
                swapCurrentSourceToUri(playbackState, upgradedIndex, resumePosition)
            // 状态层已没有该曲目时，播放器队列里仍可能留着它：交给同一替换入口处理，
            // 当前项若仍指向旧源会返回 false，随后保留旧文件
            else -> replaceLoadedTrackSource(playbackState, target)
        }
    }
    // 立即持久化新 URI 与续播位置：换源不经过切歌回调，若等周期性存储（3 秒节流）写入，
    // 用户在窗口内退出会把旧 URI 落盘，重启后旧文件已删除导致曲目不可用、进度丢失
    playbackState.persistState()
    // 换源后重读新文件的源格式刷新音频信息条：换源是同一曲目换 URI，不触发切歌回调，
    // 且 ExoPlayer 复用同 mediaId 的已选轨道时未必派发 onTracksChanged，需主动补齐
    withContext(Dispatchers.Main) {
        playbackState.refreshIdleTrackFormatInfo(context)
    }
    // 只有播放器里的相关引用都换成新文件后才删旧文件；换源失败时旧源仍被引用，删掉就会让队列指向空地址
    if (sourceReplaced) {
        deleteOldAudioFile(context, track, downloadedUri)
        // 触发媒体扫描：新文件入库，旧文件条目同步移除
        if (newPath.isNotBlank()) scanAndAwait(context, newPath)
        // 新文件的 MediaStore ID 与旧 ID 不同：把收藏、自定义歌单等用户关联迁到新 ID，
        // 否则删旧条目后这些关联会随旧 ID 一起失忆
        if (newPath.isNotBlank()) {
            findAudioMediaId(context, newPath)?.takeIf { it != track.id }?.let { newId ->
                playbackState.migrateTrackId(context, track.id, newId)
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

// 按真实路径查 MediaStore 音频条目 ID：升级写入下载集合后，扫描会另建一条音频库记录
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

// 写入升级后新文件的标签：以旧文件既有标签为底，仅用在线候选覆盖标题与艺术家，
// 用户手填的歌词、专辑、编号、作曲、ReplayGain 等随新文件保留；
// 封面沿用旧文件内嵌原图，旧文件无内嵌封面时不主动下载，交由占位符显示
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
        }
        val coverUpdate = coverBytes?.let { CoverUpdate.Set(it) } ?: CoverUpdate.Keep
        MusicMetadataWriter.writeFields(context, updated, fields, coverUpdate)
    } catch (e: Exception) {
        CrashLogManager.logException("MusicDownloader", "内嵌无损升级元数据失败: 歌曲=${candidate.title}", e)
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
            // 无损直链可能返回试听片段（≤30 秒），不入库不升级
            if (isTrialAudioFile(tempFile)) return@withContext null
            // 校验确为无损格式，避免平台未提供无损时以有损文件顶替
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
                // 完整写入后才发布；失败时回收新条目，绝不把半截文件当作升级结果
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

// 删除升级前的旧本地文件：经 MediaStore 删除并清理媒体条目，失败时直删路径并触发媒体扫描。
// 起播后再删：播放源已切到新文件，旧文件即使仍被播放器持有句柄也不影响新文件播放
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
