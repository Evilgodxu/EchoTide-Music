package com.yichao.evilgodxu.data.music.playback.engine

import android.media.AudioDeviceInfo
import androidx.media3.common.Player

/**
 * 播放引擎边界：服务侧只依赖本接口，装配细节不外露。
 * 线程约束同 media3：只在主线程发起与回调
 */
internal interface PlaybackEngine {
    /** 供媒体会话使用的播放器视图；会话与音量包装建立在它之上 */
    val sessionPlayer: Player

    /** 起播意图：暂停后仍为真，服务须留着等续播 */
    val playWhenReady: Boolean

    /** 是否真的在出声：意图成立且输出就绪 */
    val isPlaying: Boolean

    val playbackState: EnginePlaybackState

    /** 原生音频属性，直出据此对齐混音器与路由 */
    val platformAudioAttributes: android.media.AudioAttributes

    fun setPlayWhenReady(playWhenReady: Boolean)

    fun pause()

    fun stop()

    fun release()

    /** 钉定输出路由；null 解除 */
    fun setPreferredAudioDevice(device: AudioDeviceInfo?)

    /**
     * 曲末暂停：播完当前曲目停在曲末，不迈入下一项。
     * 插队队列非空时打开，否则会先迈入下一首才被顶掉。
     */
    fun setHoldAtItemEnd(hold: Boolean)

    fun addListener(listener: EngineListener)
}

/** 引擎播放状态，与媒体3 同名状态对应 */
internal enum class EnginePlaybackState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
}

/**
 * 当前音频轨只读快照：只谈音频轨能否解码、以何格式解码。
 * 视频轨已在引擎构造时禁用。
 */
internal data class EngineAudioTrackInfo(
    /** 取快照时的项 mediaId，用于把格式记到正确曲目下 */
    val currentToken: String?,
    /** 音频组内的轨道数 */
    val trackCount: Int,
    /** 被选中轨的格式；null 为整组无选中项 */
    val selectedFormat: EngineAudioFormat?,
    /** 0 号轨编码：无选中时告知是哪种格式无解码器 */
    val firstTrackMimeType: String?,
)

/** 解码输入格式；缺失项为 0/NO_VALUE，展示层跳过 */
internal data class EngineAudioFormat(
    val mimeType: String?,
    val sampleRate: Int,
    val channelCount: Int,
    val bitrate: Int,
    val averageBitrate: Int,
)

/** 引擎事件，全部带默认空实现，调用方只覆写关心的 */
internal interface EngineListener {
    fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: PlayWhenReadyChangeReason) {}

    fun onPlaybackStateChanged(state: EnginePlaybackState) {}

    fun onError(errorCodeName: String, message: String?, cause: Throwable?) {}

    /** 音频轨组变化；null 表示已无音频组 */
    fun onTracksChanged(info: EngineAudioTrackInfo?) {}

    fun onAudioDecoderInitialized(decoderName: String) {}
}

/** 起播意图变化原因：曲末暂停据此区别于用户暂停 */
internal enum class PlayWhenReadyChangeReason {
    USER,
    END_OF_MEDIA_ITEM,
    OTHER,
}
