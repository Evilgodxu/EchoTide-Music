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

/** 曲目移除：删队列项、清理在线流与失访条目、彻底删音频源 */
internal class PlaylistRemovalController(private val state: MusicPlaybackState) {

    // advanceToNext：删当前曲目时递补下一首
    fun remove(trackId: Long, advanceToNext: Boolean = false) {
        if (state.playlist.none { it.id == trackId }) return
        val removedCurrent = state.currentTrack?.id == trackId
        // 记录删除前是否在播，决定递补后是否续播
        val wasPlaying = state.mediaController?.isPlaying == true || state.isPlaying
        // 环形取删除项之后的一首
        val nextTrack = if (removedCurrent) {
            val oldIndex = state.playlist.indexOfFirst { it.id == trackId }
            state.playlist.getOrNull((oldIndex + 1) % state.playlist.size)?.takeIf { it.id != trackId }
        } else null
        state.playlist = state.playlist.filterNot { it.id == trackId }
        state.playNext.remove(trackId)
        state.syncPlayNextHold()
        if (removedCurrent) {
            val nextIndex = nextTrack?.let { track -> state.playlist.indexOfFirst { t -> t.id == track.id } } ?: -1
            if (advanceToNext && nextIndex >= 0) {
                // 先停旧播放，避免播已删源
                state.mediaController?.stop()
                // 同帧记切换类型，漏记会沿用旧类型
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

    // 彻底删除：移除音频源并同步库、队列与歌单
    suspend fun deleteSongPermanently(context: Context, track: MusicTrack) {
        val deleted = withContext(Dispatchers.IO) { deleteAudioSource(context, track) }
        // 源文件未删掉则不处理关联，避免整理结果先丢
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

    // 歌词缓存按「标题-艺术家」共用，仍有人用时不动
    private suspend fun deleteLyricCache(context: Context, track: MusicTrack) {
        val location = track.lyricCachePath
        if (location.isBlank()) return
        val stillUsed = (state.playlist.asSequence() + state.defaultPlaylist.asSequence())
            .any { it.lyricCachePath == location }
        if (stillUsed) return
        withContext(Dispatchers.IO) { MusicMetadataCache.deleteLyrics(context, location) }
    }

    // 探测访问权限属 I/O，放 IO 线程
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

    // 清理未播在线曲，保留已缓存与缓存中
    fun cleanupIdleOnlineTracks() {
        // 以控制器实际播放项为准
        val activeId = state.mediaController?.currentMediaItem?.mediaId?.toLongOrNull() ?: state.currentTrack?.id
        val kept = state.playlist.filter { track ->
            track.id == activeId || track.id in state.cacheInProgressIds || !isOnlineStreaming(track)
        }
        if (kept.size == state.playlist.size) return
        state.playlist = kept
        state.currentIndex = kept.indexOfFirst { it.id == activeId }
        state.persistPlaylist()
        // 列表收缩后重新校正当前曲目
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

    // 在线流：无本地路径且为 http(s)
    private fun isOnlineStreaming(track: MusicTrack): Boolean {
        if (track.path.isNotBlank()) return false
        val scheme = runCatching { track.audioUri.toUri().scheme }.getOrNull()
        return scheme == "http" || scheme == "https"
    }

    // 删除音频源，返回是否成功
    private fun deleteAudioSource(context: Context, track: MusicTrack): Boolean =
        deleteAudioSourceByRef(context, track.audioUri, track.path)

    // 先经 MediaStore 删，失败再直删
    private fun deleteAudioSourceByRef(context: Context, audioUri: String, path: String?): Boolean {
        val uri = audioUri.takeIf { it.isNotBlank() }?.toUri()
        // 在线流无本地文件，直接视为成功
        if (uri?.scheme == "http" || uri?.scheme == "https") return true
        val deletedViaResolver = uri != null &&
            runCatching { context.contentResolver.delete(uri, null, null) }.getOrDefault(0) > 0
        if (deletedViaResolver) return true
        if (path.isNullOrBlank()) return false
        val deletedFile = runCatching { File(path).delete() }.getOrDefault(false)
        if (deletedFile) {
            // 触发媒体扫描，避免歌曲重现
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        }
        return deletedFile
    }
}
