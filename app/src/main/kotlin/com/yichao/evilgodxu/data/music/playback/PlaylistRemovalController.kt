package com.yichao.evilgodxu.data.music.playback

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.metadata.MusicMetadataCache
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 曲目移除：从播放队列删除、清理未播放的在线流、剔除失访的外部条目，以及彻底删除音频源。
 *
 * 只处理「曲目从库与队列中消失」这一条链路：队列收缩、当前项递补、
 * 歌单引用同步。播放推进本身由调用方与播放会话承担。
 */
internal class PlaylistRemovalController(private val state: MusicPlaybackState) {

    // advanceToNext：删除的是当前曲目时，自动递补原列表顺序中的下一首，避免播放器内容空白
    fun remove(trackId: Long, advanceToNext: Boolean = false) {
        if (state.playlist.none { it.id == trackId }) return
        val removedCurrent = state.currentTrack?.id == trackId
        // 记录删除前是否正在播放，决定递补后是继续播放还是仅切换显示
        val wasPlaying = state.mediaController?.isPlaying == true || state.isPlaying
        // 原列表顺序中删除曲目之后的下一首（环形取），列表仅剩自身时无递补
        val nextTrack = if (removedCurrent) {
            val oldIndex = state.playlist.indexOfFirst { it.id == trackId }
            state.playlist.getOrNull((oldIndex + 1) % state.playlist.size)?.takeIf { it.id != trackId }
        } else null
        state.playlist = state.playlist.filterNot { it.id == trackId }
        state.playNext.remove(trackId)
        if (removedCurrent) {
            val nextIndex = nextTrack?.let { track -> state.playlist.indexOfFirst { t -> t.id == track.id } } ?: -1
            if (advanceToNext && nextIndex >= 0) {
                // 先停止旧播放，避免继续播已被删除的音频源
                state.mediaController?.stop()
                // 曲目由本处先行写入，类型必须与它同帧记下：随后的 playTrackAt 因目标已是当前曲目
                // 而不再补记，本处漏记就会沿用上一次的类型
                state.beginTrackSwitch(TrackSwitchKind.Next)
                state.currentIndex = nextIndex
                state.currentTrack = state.playlist[nextIndex]
                state.isPlaying = false
                state.isPrepared = false
                state.currentPosition = 0L
                state.duration = 0L
                state.errorMsg = null
                state.playbackScope.launch {
                    playTrackAt(
                        state.appContext,
                        state,
                        nextIndex,
                        autoPlay = wasPlaying,
                        clearQueue = false,
                        switchKind = TrackSwitchKind.Next,
                    )
                }
            } else {
                state.mediaController?.stop()
                state.currentTrack = null
                state.currentIndex = -1
                state.isPlaying = false
                state.isPrepared = false
                state.currentPosition = 0L
                state.duration = 0L
            }
        } else {
            state.currentIndex = state.playlist.indexOfFirst { it.id == state.currentTrack?.id }
        }
        state.persistPlaylist()
    }

    // 彻底删除歌曲：移除音频源文件，并同步库、播放队列与歌单引用。
    // 歌词缓存随曲目一并删除，但只删本应用写的那一份（见 MusicMetadataCache.deleteLyrics）
    suspend fun deleteSongPermanently(context: Context, track: MusicTrack) {
        val deleted = withContext(Dispatchers.IO) { deleteAudioSource(context, track) }
        // 源文件没删掉时不能把收藏/歌单当成功删除处理：下次扫描会把歌重新带回来，
        // 用户整理过的关联却已经先丢了
        if (!deleted) {
            CrashLogManager.logException(
                "PlaylistRemovalController",
                "彻底删除音频源失败，保留用户关联: 歌曲=${track.title}, uri=${track.audioUri}, 路径=${track.path}",
            )
            return
        }
        state.defaultPlaylist = state.defaultPlaylist.filterNot { it.id == track.id }
        remove(track.id, advanceToNext = true)
        state.likedIds = state.likedIds - track.id
        state.removeFromRecentPlayed(track.id)
        state.playlistStore.removeTrackFromAll(track.id)
        deleteLyricCache(context, track)
    }

    // 歌词缓存按「标题 - 艺术家」命名，同名的两首歌共用同一份文件：
    // 队列或默认歌单里还有曲目在用它时不动，避免删一首歌连带清掉另一首的歌词
    private suspend fun deleteLyricCache(context: Context, track: MusicTrack) {
        val location = track.lyricCachePath
        if (location.isBlank()) return
        val stillUsed = (state.playlist.asSequence() + state.defaultPlaylist.asSequence())
            .any { it.lyricCachePath == location }
        if (stillUsed) return
        withContext(Dispatchers.IO) { MusicMetadataCache.deleteLyrics(context, location) }
    }

    // 文件访问探测属 I/O 操作，在 IO 线程执行避免阻塞主线程
    suspend fun removeUnavailableExternalTracks(context: Context) {
        val unavailableIds = withContext(Dispatchers.IO) {
            state.playlist
                .filter { track ->
                    track.path.isBlank() &&
                        track.audioUri.isNotBlank() &&
                        runCatching {
                            val scheme = track.audioUri.toUri().scheme
                            scheme != null && scheme !in listOf("http", "https")
                        }.getOrElse { false } &&
                        runCatching { track.audioUri.toUri().scheme == ContentResolver.SCHEME_CONTENT }.getOrElse { false } &&
                        !hasUriAccess(context, track.audioUri)
                }
                .map { it.id }
                .toSet()
        }
        if (unavailableIds.isEmpty()) return

        withContext(Dispatchers.Main) {
            val currentWasRemoved = state.currentTrack?.id in unavailableIds
            state.playlist = state.playlist.filterNot { it.id in unavailableIds }
            state.currentIndex = state.playlist.indexOfFirst { it.id == state.currentTrack?.id }
            if (currentWasRemoved) {
                state.mediaController?.stop()
                state.currentTrack = null
                state.currentIndex = -1
                state.isPlaying = false
                state.isPrepared = false
                state.currentPosition = 0L
                state.duration = 0L
                state.playbackStateStore.clear(context)
            }
            state.persistPlaylist()
        }
    }

    // 自动清理未在播放的纯在线流曲目；已缓存为本地文件或缓存进行中的曲目保留，保证离线播放不中断
    fun cleanupIdleOnlineTracks() {
        // 以控制器实际播放项为权威来源，避免 UI 状态与真实音频脱同步
        val activeId = state.mediaController?.currentMediaItem?.mediaId?.toLongOrNull() ?: state.currentTrack?.id
        val kept = state.playlist.filter { track ->
            track.id == activeId || track.id in state.cacheInProgressIds || !isOnlineStreaming(track)
        }
        if (kept.size == state.playlist.size) return
        state.playlist = kept
        state.currentIndex = kept.indexOfFirst { it.id == activeId }
        state.persistPlaylist()
        // 列表收缩后重新从控制器校正当前曲目，保证显示与实际播放一致
        state.syncPlaybackState()
    }

    private fun hasUriAccess(context: Context, audioUri: String): Boolean {
        val uri = audioUri.toUri()
        if (context.contentResolver.persistedUriPermissions.none {
                it.uri == uri && it.isReadPermission
            }) return false
        return try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: Exception) {
            CrashLogManager.logException("PlaylistRemovalController", "检查媒体访问权限失败", e)
            false
        }
    }

    // 判定是否为在线流媒体曲目：无本地路径且音频地址为 http(s)
    private fun isOnlineStreaming(track: MusicTrack): Boolean {
        if (track.path.isNotBlank()) return false
        val scheme = runCatching { track.audioUri.toUri().scheme }.getOrNull()
        return scheme == "http" || scheme == "https"
    }

    // 删除音频源文件：返回 false 表示两种删除方式都没能删掉文件
    private fun deleteAudioSource(context: Context, track: MusicTrack): Boolean =
        deleteAudioSourceByRef(context, track.audioUri, track.path)

    // 按 URI 与本地路径删除音频源文件：先经 MediaStore 删除（同时清理媒体条目），
    // 失败则直接删本地路径并通知媒体库同步
    private fun deleteAudioSourceByRef(context: Context, audioUri: String, path: String?): Boolean {
        val uri = audioUri.takeIf { it.isNotBlank() }?.toUri()
        // 纯在线流曲目无本地文件，无需文件级删除
        if (uri?.scheme == "http" || uri?.scheme == "https") return true
        val deletedViaResolver = uri != null &&
            runCatching { context.contentResolver.delete(uri, null, null) }.getOrDefault(0) > 0
        if (deletedViaResolver) return true
        if (path.isNullOrBlank()) return false
        val deletedFile = runCatching { File(path).delete() }.getOrDefault(false)
        if (deletedFile) {
            // 直删文件后触发媒体扫描，使 MediaStore 中该文件的条目失效，避免歌曲重新出现
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        }
        return deletedFile
    }
}
