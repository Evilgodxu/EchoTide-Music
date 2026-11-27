package com.yichao.evilgodxu.data.music.playback.engine

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.yichao.evilgodxu.data.music.playback.AudioExtractorsFactory
import com.yichao.evilgodxu.data.music.playback.AudioMediaSourceFactory
import com.yichao.evilgodxu.data.music.playback.AudioOnlyRenderersFactory

/**
 * media3 引擎：ExoPlayer 依赖收在此。
 * 服务侧只见 PlaybackEngine。
 */
@OptIn(UnstableApi::class)
internal class ExoPlayerEngine(
    context: Context,
    audioSink: AudioSink,
) : PlaybackEngine {

    private val listeners = mutableListOf<EngineListener>()

    // 装配件全给，默认工厂不再引用，
    // 视频/字幕渲染器与多余容器解析器可被 R8 裁掉
    private val player: ExoPlayer = ExoPlayer.Builder(
        context,
        AudioOnlyRenderersFactory(context, audioSink),
        AudioMediaSourceFactory(DefaultDataSource.Factory(context), AudioExtractorsFactory()),
    )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                // 空间化可能默认开启，破坏原样保真，显式禁用；
                // 均衡器等 aux 效果本应用不挂载，默认即禁用
                .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER)
                .build(),
            // 焦点由服务自行申请与释放，播放器内置处理关闭
            false,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            // 熄屏保活：在线流同时需要 CPU 与 WLAN 锁；
            // 仅 LOCAL 时 WLAN 锁不启用，熄屏后会被回收
            setWakeMode(C.WAKE_MODE_NETWORK)
            // 纯音频：禁用视频轨，否则含内嵌视频的文件会选它解码，
            // 既耗线程又让轨道信息读到视频格式
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                val engineReason = when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> PlayWhenReadyChangeReason.USER
                    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM ->
                        PlayWhenReadyChangeReason.END_OF_MEDIA_ITEM
                    else -> PlayWhenReadyChangeReason.OTHER
                }
                listeners.forEach { it.onPlayWhenReadyChanged(playWhenReady, engineReason) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                listeners.forEach { it.onPlaybackStateChanged(playbackState.toEngineState()) }
            }

            override fun onPlayerError(error: PlaybackException) {
                listeners.forEach { it.onError(error.errorCodeName, error.message, error) }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val info = audioTrackInfoOf(tracks)
                listeners.forEach { it.onTracksChanged(info) }
            }
        })
        // 记录平台实际使用的解码器名，供音频信息展示；
        // 解码器跨曲复用时不重复回调，故始终对应现役解码器
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                listeners.forEach { it.onAudioDecoderInitialized(decoderName) }
            }
        })
    }

    override val sessionPlayer: Player get() = player

    override val playWhenReady: Boolean get() = player.playWhenReady

    override val isPlaying: Boolean get() = player.isPlaying

    override val playbackState: EnginePlaybackState get() = player.playbackState.toEngineState()

    override val platformAudioAttributes: android.media.AudioAttributes
        get() = player.audioAttributes.platformAudioAttributes

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        player.playWhenReady = playWhenReady
    }

    override fun pause() {
        player.pause()
    }

    override fun stop() {
        player.stop()
    }

    override fun release() {
        player.release()
    }

    override fun setPreferredAudioDevice(device: AudioDeviceInfo?) {
        player.setPreferredAudioDevice(device)
    }

    override fun setHoldAtItemEnd(hold: Boolean) {
        player.setPauseAtEndOfMediaItems(hold)
    }

    override fun addListener(listener: EngineListener) {
        listeners += listener
    }

    /**
     * 只取音频组，不能取首个有选中项的组（视频组在前）。
     * 组内优先取选中轨，选中项未必在 0 号。
     */
    private fun audioTrackInfoOf(tracks: Tracks): EngineAudioTrackInfo? {
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO } ?: return null
        val selectedIndex = (0 until group.length).firstOrNull { group.isTrackSelected(it) }
        return EngineAudioTrackInfo(
            currentToken = player.currentMediaItem?.mediaId,
            trackCount = group.length,
            selectedFormat = selectedIndex?.let { group.getTrackFormat(it).toEngineFormat() },
            firstTrackMimeType = if (group.length > 0) group.getTrackFormat(0).sampleMimeType else null,
        )
    }
}

@OptIn(UnstableApi::class)
private fun Format.toEngineFormat(): EngineAudioFormat = EngineAudioFormat(
    mimeType = sampleMimeType,
    sampleRate = sampleRate,
    channelCount = channelCount,
    bitrate = bitrate,
    averageBitrate = averageBitrate,
)

private fun Int.toEngineState(): EnginePlaybackState = when (this) {
    Player.STATE_BUFFERING -> EnginePlaybackState.BUFFERING
    Player.STATE_READY -> EnginePlaybackState.READY
    Player.STATE_ENDED -> EnginePlaybackState.ENDED
    else -> EnginePlaybackState.IDLE
}
