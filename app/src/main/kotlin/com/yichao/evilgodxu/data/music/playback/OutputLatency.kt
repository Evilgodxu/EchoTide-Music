package com.yichao.evilgodxu.data.music.playback

import android.media.AudioTimestamp
import android.media.AudioTrack

// 帧位计数为 32 位：播放头与时间戳都会回绕，差值只能按模取，跨回绕时才得真实距离
private const val FRAME_COUNT_MODULUS = 1L shl 32
private const val FRAME_COUNT_MASK = FRAME_COUNT_MODULUS - 1

// 可信上界：超过该量级即平台误报，宁缺不用（媒体3 判时间戳无效取的是同一量级）
private const val MAX_PLAUSIBLE_LATENCY_MS = 5_000f

/**
 * 输出链路的实际延迟。
 *
 * 平台没有按设备查询延迟的公开接口：`AudioDeviceInfo` 不上报延迟，而输出帧数与采样率相除只是
 * HAL 单次突发的时长——它与当前走哪一路输出无关，挂 USB 解码器时依旧是那个最小值，USB 传输与
 * 解码器自身的排队恰恰落在这一段之外，故该值不能代表输出延迟。
 *
 * 真正含设备那一段的量只能从当前链路的音频轨上取：`AudioTrack.getTimestamp()` 给出
 * 「第几帧在什么时刻呈现」，把它推到现在，与同一时刻刚挪出音频轨的那一帧（播放头）相比，
 * 差值换算成时长即「离开音频轨之后还要多久才发声」——是含设备与传输的真实推后量，且为公开接口、
 * 各系统稳定可用。
 *
 * 取不到时不产出条目：不接受与当前路由无关的下界值顶替。
 */
internal object OutputLatency {

    /**
     * 当前音频轨的输出延迟（毫秒），取不到时为 null。
     *
     * 只在轨道已建立且正在推进时才谈得上延迟：未推进时播放头与时间戳都不随时间前进，
     * 比对出来的差值无从对应到出站后的那一段。
     */
    fun measureMs(track: AudioTrack?): Float? {
        if (track == null) return null
        if (track.state != AudioTrack.STATE_INITIALIZED) return null
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return null
        return timestampLatencyMs(track)
    }

    /**
     * 按时间戳实测「离开音频轨到实际发声」的推后量。
     *
     * 时间戳的含义是「第 [AudioTimestamp.framePosition] 帧在 [AudioTimestamp.nanoTime] 时刻呈现」，
     * 两边都以 Monotonic 时钟为基准，故可线性推到现在：此刻正在呈现的帧等于该帧位加上这段时间的推移。
     * 此刻刚挪出音频轨的帧是播放头读数，二者之差即尚未发声的那部分。
     */
    private fun timestampLatencyMs(track: AudioTrack): Float? {
        val sampleRate = track.sampleRate.takeIf { it > 0 } ?: return null
        val timestamp = AudioTimestamp()
        if (!track.getTimestamp(timestamp)) return null
        val elapsedNanos = System.nanoTime() - timestamp.nanoTime
        if (elapsedNanos < 0) return null
        val presentedFrames =
            (timestamp.framePosition + elapsedNanos * sampleRate / NANOS_PER_SECOND) and FRAME_COUNT_MASK
        val headFrames = track.playbackHeadPosition.toLong() and FRAME_COUNT_MASK
        var outstandingFrames = headFrames - presentedFrames
        if (outstandingFrames < 0) outstandingFrames += FRAME_COUNT_MODULUS
        val latencyMs = outstandingFrames * MILLIS_PER_SECOND / sampleRate
        return latencyMs.takeIf { it in 0f..MAX_PLAUSIBLE_LATENCY_MS }
    }

    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val MILLIS_PER_SECOND = 1_000f
}
