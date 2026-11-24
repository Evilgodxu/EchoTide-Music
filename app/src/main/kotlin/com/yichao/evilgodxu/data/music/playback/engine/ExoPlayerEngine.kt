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
 * 媒体3 引擎：全部 ExoPlayer 依赖收在这一处。
 *
 * 播放器的装配（渲染器、媒体源工厂、音频属性、唤醒锁、轨道选择）与事件转译都在这里完成，
 * 服务侧只看得到 [PlaybackEngine]。
 */
@OptIn(UnstableApi::class)
internal class ExoPlayerEngine(
    context: Context,
    audioSink: AudioSink,
) : PlaybackEngine {

    private val listeners = mutableListOf<EngineListener>()

    // 三类装配件都给全（媒体3 为这类裁剪留的构造器）：默认渲染器工厂与默认媒体源工厂
    // 因此不再被引用，视频/字幕/图片渲染器与用不到的容器解析器都可被 R8 裁掉
    private val player: ExoPlayer = ExoPlayer.Builder(
        context,
        AudioOnlyRenderersFactory(context, audioSink),
        AudioMediaSourceFactory(DefaultDataSource.Factory(context), AudioExtractorsFactory()),
    )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                // 空间化是平台唯一可能默认开启的后处理（虚拟环绕/头部追踪），音乐播放追求
                // 原样保真，显式禁用；音效类（均衡器等）本应用从不挂载，aux 效果默认即禁用
                .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER)
                .build(),
            // 焦点由服务自行申请与释放，播放器内置处理关闭
            false,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            // 熄屏保活：在线音源经网络拉流，需同时持有 CPU 唤醒锁与 WLAN 锁（WAKE_MODE_NETWORK）。
            // 仅 WAKE_MODE_LOCAL 时 WLAN 锁不启用，熄屏后 Wi-Fi 进入省电、缓冲停滞，
            // 长时间无进展会被系统回收播放进程
            setWakeMode(C.WAKE_MODE_NETWORK)
            // 纯音频播放：显式禁用视频轨道。默认渲染器按视频在前的顺序建组，含内嵌视频轨的文件
            // （MV、带画面的 MP4）会选中视频轨并解码——既白耗解码与播放线程，也会让轨道信息读到视频格式
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                listeners.forEach { it.onPlayWhenReadyChanged(playWhenReady) }
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
        // 记录平台实际使用的解码器实现名，供音频信息展示。
        // 解码器可跨曲复用，复用时不重复回调，故该值始终对应当前渲染器正在使用的解码器
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

    override fun addListener(listener: EngineListener) {
        listeners += listener
    }

    /**
     * 只取音频组自身的格式，不能取首个「有选中项」的组——渲染器按视频在前的顺序建组，
     * 组内含视频轨时会先命中视频格式；组内优先取被选中的轨，多音轨时选中项未必在 0 号。
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
