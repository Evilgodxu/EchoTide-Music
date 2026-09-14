package com.yichao.evilgodxu.service

import android.content.Intent
import android.view.KeyEvent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yichao.evilgodxu.App
import com.yichao.evilgodxu.data.music.analysis.TrackAudioInfoReader
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.data.music.playback.AudioSignalPathFormat
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    // 系统创建的服务无法构造注入，经 Application 取共享单例
    private val stateHolder: MusicPanelStateHolder
        get() = (application as App).stateHolder
    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    /** 焦点丢失前是否正在播放：恢复焦点后据此自动续播 */
    private var resumeAfterFocusLoss = false
    private val audioFocusHandler = Handler(Looper.getMainLooper())

    /** 其他应用抢占焦点时的响应：一律暂停，恢复后按需续播，避免压低音量后不恢复导致的静音 */
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeAfterFocusLoss) {
                    resumeAfterFocusLoss = false
                    player.setPlayWhenReady(true)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterFocusLoss = false
                player.pause()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeAfterFocusLoss = player.isPlaying
                player.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        // 变速/变调交给 AudioTrack 原生处理，避免 Sonic 软件变速在低速时产生噪声
        val audioSink = DefaultAudioSink.Builder(this)
            .setEnableAudioOutputPlaybackParameters(true)
            .build()
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParameters: Boolean,
            ): AudioSink = audioSink
        }
        player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) requestAudioFocus()
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val format = tracks.groups.firstOrNull { it.isSelected }?.getTrackFormat(0)
                val state = stateHolder.state
                val currentTrack = state.currentTrack
                // 每次轨道切换后按解码格式更新信号路径状态
                val fileFormat = format?.let { f ->
                    currentTrack?.path
                        ?.substringAfterLast('.', "")
                        ?.takeIf { it.isNotBlank() }
                        ?.uppercase()
                        ?.let { if (it == "MPEG") "MP3" else it }
                        ?: TrackAudioInfoReader.mimeToFormatName(f.sampleMimeType)
                }
                if (format != null) {
                    val sampleRate = format.sampleRate.takeIf { it > 0 } ?: 48000
                    val decodedChannels = format.channelCount.takeIf { it > 0 } ?: 2
                    // 优先沿用源格式预读的位深/声道，保证播放前后展示一致不跳变；
                    // 位深是源文件属性，不以解码输出位深推算（高解析度曲目解码常以浮点输出）
                    val sourceFormat = state.audioSignalPathFormat
                        .takeIf { state.audioSignalPathTrackId == currentTrack?.id }
                    val bitDepth = sourceFormat?.bitDepth
                        ?: currentTrack?.takeIf { it.isLocalAudioSource }
                            ?.let { TrackAudioInfoReader.readContainerFormat(applicationContext, it)?.bitDepth }
                        ?: 16
                    val channels = sourceFormat?.channels ?: decodedChannels
                    state.audioSignalPathFormat = AudioSignalPathFormat(
                        format = fileFormat ?: "PCM",
                        sampleRate = sampleRate,
                        outputRate = sampleRate,
                        bitDepth = bitDepth,
                        channels = channels,
                        // Format.bitrate 单位为 bps，统一换算为 kbps；VBR 曲目 bitrate 未知时回退 averageBitrate
                        bitrate = maxOf(format.bitrate, format.averageBitrate)
                            .takeIf { it > 0 }
                            ?.let { it / 1000 } ?: 0,
                    )
                    state.audioSignalPathTrackId = currentTrack?.id
                }
                // 解码头未给出比特率时（FLAC/VBR 常见），异步读取真实比特率并回填；
                // 仅本地音频源读取文件元数据，在线曲目等缓存完成后由 refreshTrackFormatInfoFromLocal 补齐
                if (state.audioSignalPathFormat?.bitrate == 0) {
                    val track = currentTrack
                    state.playbackScope.launch(Dispatchers.IO) {
                        if (track != null && track.isLocalAudioSource && state.audioSignalPathTrackId == track.id) {
                            TrackAudioInfoReader.readBitrateKbps(applicationContext, track)?.let { bitrate ->
                                if (state.audioSignalPathFormat?.bitrate == 0) {
                                    state.audioSignalPathFormat = state.audioSignalPathFormat?.copy(bitrate = bitrate)
                                }
                            }
                        }
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (mediaItem != null) {
                    player.playWhenReady = true
                }
            }
        })
        mediaSession = MediaSession.Builder(this, SkipProxyPlayer(player))
            .setCallback(sessionCallback)
            .build()
    }

    /** 拦截系统媒体面板和耳机/蓝牙媒体键的上一首/下一首操作 */
    private val sessionCallback = object : MediaSession.Callback {
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                when (keyEvent.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                        handlePreviousTrack()
                        return true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                        handleNextTrack()
                        return true
                    }
                }
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }
    }

    /**
     * 包装 ExoPlayer，把系统媒体面板/通知栏的上一首/下一首操作
     * 映射到应用自己的切歌逻辑，避免默认行为中「回退到当前曲目开头」。
     */
    private inner class SkipProxyPlayer(player: Player) : ForwardingPlayer(player) {
        override fun seekToPrevious() {
            handlePreviousTrack()
        }

        override fun seekToPreviousMediaItem() {
            handlePreviousTrack()
        }

        override fun seekToNext() {
            handleNextTrack()
        }

        override fun seekToNextMediaItem() {
            handleNextTrack()
        }
    }

    private fun handlePreviousTrack() {
        val state = stateHolder.state
        if (state.currentTrack != null && state.playlist.isNotEmpty()) {
            val prev = state.previousIndex()
            if (prev >= 0) {
                state.playbackScope.launch {
                    playTrackAt(this@MusicPlaybackService, state, prev)
                }
            }
        }
    }

    private fun handleNextTrack() {
        val state = stateHolder.state
        if (state.currentTrack != null && state.playlist.isNotEmpty()) {
            val next = state.nextIndex()
            if (next >= 0) {
                state.playbackScope.launch {
                    playTrackAt(this@MusicPlaybackService, state, next)
                }
            }
        }
    }

    private fun requestAudioFocus() {
        val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusListener, audioFocusHandler)
            .build()
            .also { audioFocusRequest = it }
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { request ->
            audioManager.abandonAudioFocusRequest(request)
            audioFocusRequest = null
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)
        if (!player.isPlaying && player.playbackState == Player.STATE_IDLE) {
            stopSelf()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) {
            stopSelf()
        }
    }

    fun stopPlayback() {
        abandonAudioFocus()
        player.stop()
        mediaSession?.release()
        mediaSession = null
        stopSelf()
    }

    override fun onDestroy() {
        abandonAudioFocus()
        mediaSession?.release()
        mediaSession = null
        player.release()
        super.onDestroy()
    }
}
