package com.yichao.evilgodxu.data.music.playback

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.MusicScanner
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.normalizedAudioUri
import com.yichao.evilgodxu.data.music.resolveLocalPath
import com.yichao.evilgodxu.log.CrashLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 外部音频的后台播放装载。
 *
 * 应用被外部以「打开/分享」唤醒时，进程可能是刚起来的，播放会话尚未就绪，故先补齐会话
 * （恢复歌单、播放列表为空时扫描、恢复当前曲目），再把传入的 URI 并入歌单并起播。全程不建立界面，
 * 迷你播放器由调用方按需显示。
 *
 * 曲库变更监听在此登记：后台播放期间新增的曲目也应进入歌单，与首页在线时的刷新口径一致。
 */
class ExternalPlaybackLoader(
    private val context: Context,
    private val playbackState: MusicPlaybackState,
    private val playlistRefresher: PlaylistRefresher,
    private val metadataEnricher: MetadataEnricher,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private var initialization: Deferred<Unit>? = null
    private val externalTrackMutex = Mutex()
    private var pendingExternalUri: Uri? = null
    private var mediaObserverRegistered = false
    private var refreshJob: Job? = null
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // 系统扫描期间会密集回调，合并为一次延迟刷新
            refreshJob?.cancel()
            refreshJob = scope.launch {
                delay(MEDIA_REFRESH_DEBOUNCE_MS)
                refreshPlaylist()
            }
        }
    }

    /**
     * 播放外部传入的音频。
     *
     * 重复调用以最后一次为准：先到的装载在持有锁后发现自己已被顶替就直接退出，
     * 不会把旧 URI 的曲目切到前台。
     */
    fun play(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            CrashLogManager.logException("ExternalPlaybackLoader", "获取外部音频持久访问权限失败", e)
            // 外部应用可能只授予临时读取权限，仍需继续播放当前 URI
        }
        pendingExternalUri = uri
        prepare()
        scope.launch {
            externalTrackMutex.withLock {
                initialization?.await()
                if (pendingExternalUri != uri) return@withLock
                val track = MusicScanner.fromUri(context, uri) ?: return@withLock
                val targetUri = track.audioUri.toUri().normalizeScheme()
                val targetPath = resolveLocalPath(context, targetUri.toString())
                val targetIndex = withContext(Dispatchers.Main) {
                    val existingIndex = playbackState.playlist.indexOfFirst {
                        isSameAudioTrack(it, targetUri, targetPath)
                    }
                    if (existingIndex >= 0) {
                        existingIndex
                    } else {
                        playbackState.playlist =
                            deduplicateTracks(playbackState.playlist + track)
                        playbackState.playlist.indexOfFirst {
                            isSameAudioTrack(it, targetUri, targetPath)
                        }
                    }
                }
                if (targetIndex < 0) return@withLock
                playbackState.persistPlaylist()
                withContext(Dispatchers.Main) {
                    // 外部音频起播没有先后关系可读，类型按选曲播放记录：曲目状态由本处
                    // 先行写入，类型必须与它同帧，之后的 playTrackAt 因目标已是当前曲目而不再补记
                    playbackState.beginTrackSwitch(TrackSwitchKind.Select)
                    playbackState.currentIndex = targetIndex
                    playbackState.currentTrack = playbackState.playlist[targetIndex]
                }
                withContext(Dispatchers.Main) {
                    playTrackAt(context, playbackState, targetIndex)
                }
                pendingExternalUri = null
            }
        }
    }

    // 释放：停止后台任务并摘掉曲库监听
    fun release() {
        refreshJob?.cancel()
        if (mediaObserverRegistered) {
            context.contentResolver.unregisterContentObserver(mediaObserver)
            mediaObserverRegistered = false
        }
        scope.cancel()
    }

    // 预热播放会话；已预热时直接返回，重复调用无副作用
    private fun prepare() {
        if (initialization != null) return
        initialization = scope.async {
            playbackState.restoreSavedState(context)
            // MediaController 仅限主线程访问，须在 Main 线程读取其状态
            val playerActive = withContext(Dispatchers.Main) { playbackState.isPlayerActive }
            if (!playerActive) {
                playbackState.removeUnavailableExternalTracks(context)
            }
            if (playbackState.playlist.isEmpty()) {
                scanAndPlay()
            } else {
                withContext(Dispatchers.Main) {
                    restoreCurrentTrack()
                    // 后台预读当前曲目歌词：迷你播放器显示歌词时已就绪
                    playbackState.requestMetadata(playbackState.currentTrack)
                }
                // 封面/歌词全量补全延迟执行，避免抢占外部音频起播所需的解码 IO
                scope.launch {
                    delay(METADATA_ENRICH_DELAY_MS)
                    metadataEnricher.enrichAndCleanup(context, playbackState)
                }
            }
            withContext(Dispatchers.Main) {
                playbackState.syncPlaybackState()
                playbackState.updatePosition()
            }
            registerMediaObserver()
        }
    }

    private suspend fun scanAndPlay() {
        playlistRefresher.refresh(context, playbackState, restoreCurrent = true) {
            scope.launch { metadataEnricher.enrichAndCleanup(context, playbackState) }
        }
    }

    private suspend fun refreshPlaylist() {
        playlistRefresher.refresh(context, playbackState, restoreCurrent = false) {
            scope.launch { metadataEnricher.enrichAndCleanup(context, playbackState) }
        }
    }

    private fun registerMediaObserver() {
        if (mediaObserverRegistered) return
        context.contentResolver.registerContentObserver(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver
        )
        mediaObserverRegistered = true
    }

    private fun restoreCurrentTrack() {
        if (playbackState.playlist.isEmpty() || playbackState.currentTrack != null) return
        val savedUri = playbackState.pendingSavedUri
        val index = savedUri?.let { uri -> playbackState.playlist.indexOfFirst { it.audioUri == uri } }
            ?.takeIf { it >= 0 }
            ?: 0
        playbackState.currentIndex = index
        playbackState.currentTrack = playbackState.playlist[index]
    }

    private fun deduplicateTracks(tracks: List<MusicTrack>): List<MusicTrack> =
        tracks.distinctBy { normalizedAudioUri(it.audioUri) }

    private fun isSameAudioTrack(track: MusicTrack, targetUri: Uri, targetPath: String?): Boolean =
        normalizedAudioUri(track.audioUri) == normalizedAudioUri(targetUri.toString()) ||
            (targetPath != null && track.path.isNotBlank() && track.path == targetPath)

    private companion object {
        // 曲库变更刷新去抖：系统扫描期间回调密集，合并成一次
        const val MEDIA_REFRESH_DEBOUNCE_MS = 300L
        // 封面/歌词全量补全前的延迟：先让外部音频起播
        const val METADATA_ENRICH_DELAY_MS = 3_000L
    }
}