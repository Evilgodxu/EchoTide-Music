package com.yichao.evilgodxu.data.music.playback.engine

import android.media.AudioDeviceInfo
import androidx.media3.common.Player

/**
 * 播放引擎边界。
 *
 * 服务侧只依赖本接口：传输控制、状态事件、音频轨信息与输出路由钉定；具体引擎如何把源、解码与
 * 输出装配起来不外露。当前的 [ExoPlayerEngine] 只是把媒体3 的播放器收在一处，替换引擎时不必
 * 再动服务与输出层之外的代码。
 *
 * 线程约束与媒体3 播放器一致：全部调用只在主线程发起，回调也落在主线程。
 */
internal interface PlaybackEngine {
    /**
     * 供媒体会话使用的播放器视图。
     *
     * 会话与音量包装都建立在它之上，故当前实现必须给出一个可用的 [Player]；会话替换后该出口随之删除。
     */
    val sessionPlayer: Player

    /** 起播意图：暂停后仍为真表示会话尚未结束，后台服务须保留等待续播 */
    val playWhenReady: Boolean

    /** 当前是否真的在出声：起播意图成立且输出已就绪 */
    val isPlaying: Boolean

    val playbackState: EnginePlaybackState

    /** 播放的原生音频属性：直出据此对齐混音器属性与输出路由 */
    val platformAudioAttributes: android.media.AudioAttributes

    fun setPlayWhenReady(playWhenReady: Boolean)

    fun pause()

    fun stop()

    fun release()

    /** 把输出路由钉定到指定设备；null 解除钉定 */
    fun setPreferredAudioDevice(device: AudioDeviceInfo?)

    fun addListener(listener: EngineListener)
}

/** 引擎的播放状态，语义与媒体3 的同名状态一一对应 */
internal enum class EnginePlaybackState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
}

/**
 * 当前音频轨的只读快照。
 *
 * 只描述「这一条音频轨是否会被解码、以什么格式解码」，不含视频/字幕等组：本应用是纯音频播放，
 * 视频轨在引擎构造时就已禁用。
 */
internal data class EngineAudioTrackInfo(
    /** 取快照时播放器所在的项（mediaId），用于把格式记到正确的曲目名下 */
    val currentToken: String?,
    /** 音频组内的轨道数 */
    val trackCount: Int,
    /** 组内被选中（会被解码）的那一条的格式；null 表示整组没有选中项 */
    val selectedFormat: EngineAudioFormat?,
    /** 组内 0 号轨的编码：无选中项时用来告诉用户是哪种格式没有解码器 */
    val firstTrackMimeType: String?,
)

/** 解码输入的格式取值；缺失项按媒体3 的约定为 0/NO_VALUE，由展示层跳过 */
internal data class EngineAudioFormat(
    val mimeType: String?,
    val sampleRate: Int,
    val channelCount: Int,
    val bitrate: Int,
    val averageBitrate: Int,
)

/**
 * 引擎事件。
 *
 * 全部带默认空实现：调用方只覆写自己关心的事件，不必为空实现留样板。
 */
internal interface EngineListener {
    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {}

    fun onPlaybackStateChanged(state: EnginePlaybackState) {}

    fun onError(errorCodeName: String, message: String?, cause: Throwable?) {}

    /** 音频轨组变化；null 表示播放器里已没有音频组 */
    fun onTracksChanged(info: EngineAudioTrackInfo?) {}

    fun onAudioDecoderInitialized(decoderName: String) {}
}
