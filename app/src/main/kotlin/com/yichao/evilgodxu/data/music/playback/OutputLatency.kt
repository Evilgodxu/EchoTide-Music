package com.yichao.evilgodxu.data.music.playback

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build

// 帧位计数 32 位：回绕后差值须按模取才得真实距离
private const val FRAME_COUNT_MODULUS = 1L shl 32
private const val FRAME_COUNT_MASK = FRAME_COUNT_MODULUS - 1

// 可信上界：超过即平台误报，宁缺不用
private const val MAX_PLAUSIBLE_LATENCY_MS = 5_000f

// 写入帧位可读的最低 API 级别，低于则不出全链路延迟
private const val API_WRITTEN_FRAMES = 37

/** 输出链路实测延迟：轨后一段加轨道驻留，容量另列 */
data class OutputLatencyReading(
    /** 音频轨授予容量（帧）；平台按粒度对齐，读数须留容差 */
    val trackBufferFrames: Int,
    /** 音频轨缓冲容量对应的时长（毫秒） */
    val trackBufferMs: Float,
    /** 轨后一段（毫秒），未推进为 null */
    val afterTrackMs: Float?,
    /** 轨道驻留（毫秒），取不到写入帧位为 null */
    val trackResidentMs: Float?,
)

/**
 * 延迟实测：时间戳推现在，与播放头及写入帧位相比得两段。
 * 平台无按设备查询延迟的接口；取不到不产出。
 */
internal object OutputLatency {

    /** 读一次延迟；未推进只给容量 */
    fun read(track: AudioTrack?, writtenFrames: (() -> Long?)?): OutputLatencyReading? {
        if (track == null) return null
        if (track.state != AudioTrack.STATE_INITIALIZED) return null
        val sampleRate = track.sampleRate.takeIf { it > 0 } ?: return null
        val bufferFrames = track.bufferSizeInFrames.takeIf { it > 0 } ?: return null
        val advanced = advancedLatencyMs(track, sampleRate, bufferFrames, writtenFrames)
        return OutputLatencyReading(
            trackBufferFrames = bufferFrames,
            trackBufferMs = framesToMs(bufferFrames, sampleRate),
            afterTrackMs = advanced?.afterTrackMs,
            trackResidentMs = advanced?.trackResidentMs,
        )
    }

    private data class AdvancedLatency(
        val afterTrackMs: Float,
        val trackResidentMs: Float?,
    )

    /** 推进中的两段延迟；未推进或时间戳不可用时返回 null */
    private fun advancedLatencyMs(
        track: AudioTrack,
        sampleRate: Int,
        bufferFrames: Int,
        writtenFrames: (() -> Long?)?,
    ): AdvancedLatency? {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return null
        val timestamp = AudioTimestamp()
        if (!track.getTimestamp(timestamp)) return null
        val elapsedNanos = System.nanoTime() - timestamp.nanoTime
        if (elapsedNanos < 0) return null
        // 时间戳帧位加推移即当前呈现帧位
        val presentedFrames =
            (timestamp.framePosition + elapsedNanos * sampleRate / NANOS_PER_SECOND) and FRAME_COUNT_MASK
        val headFrames = track.playbackHeadPosition.toLong() and FRAME_COUNT_MASK
        val afterTrackFrames = frameDistance(headFrames, presentedFrames)
        val afterTrackMs = afterTrackFrames.toMs(sampleRate) ?: return null
        val residentMs = writtenResidueFrames(track, headFrames, bufferFrames, writtenFrames)
            ?.toMs(sampleRate)
        return AdvancedLatency(afterTrackMs, residentMs)
    }

    /** 音频轨驻留帧数；优先应用记账，否则平台接口，超容量即弃 */
    private fun writtenResidueFrames(
        track: AudioTrack,
        headFrames: Long,
        bufferFrames: Int,
        writtenFrames: (() -> Long?)?,
    ): Long? {
        val written = writtenFrames?.invoke() ?: platformWrittenFrames(track) ?: return null
        if (written <= 0) return null
        val residue = (written - headFrames) and FRAME_COUNT_MASK
        return residue.takeIf { it <= bufferFrames.toLong() }
    }

    // 平台写入帧位，API 37 起可读
    private fun platformWrittenFrames(track: AudioTrack): Long? {
        if (Build.VERSION.SDK_INT < API_WRITTEN_FRAMES) return null
        return runCatching { track.writtenFramesCount }.getOrNull()
    }

    // 帧位差按模取
    private fun frameDistance(later: Long, earlier: Long): Long =
        ((later - earlier) and FRAME_COUNT_MASK)

    // 超可信上界即误报，不产出
    private fun Long.toMs(sampleRate: Int): Float? =
        (this * MILLIS_PER_SECOND / sampleRate).takeIf { it in 0f..MAX_PLAUSIBLE_LATENCY_MS }

    // 容量换算不设上界
    private fun framesToMs(frames: Int, sampleRate: Int): Float = frames * MILLIS_PER_SECOND / sampleRate

    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val MILLIS_PER_SECOND = 1_000f
}

/** 延迟读数滑动平均；取不到不入窗，窗口随采样推移 */
internal class OutputLatencySampler(private val window: Int = AVERAGE_WINDOW) {

    private val afterTrackSamples = ArrayDeque<Float>()
    private val residentSamples = ArrayDeque<Float>()

    /** 采一次取窗口均值；写入入口由外部传入 */
    fun sample(track: AudioTrack?, writtenFrames: (() -> Long?)?): OutputLatencyReading? =
        OutputLatency.read(track, writtenFrames)?.let(::averaged)

    /** 采一次给定读数（独占路径） */
    fun sampleProvided(reading: OutputLatencyReading): OutputLatencyReading = averaged(reading)

    /** 读数入窗并给出均值；无可平均分量则原样返回 */
    private fun averaged(reading: OutputLatencyReading): OutputLatencyReading {
        reading.afterTrackMs?.let { push(afterTrackSamples, it) }
        reading.trackResidentMs?.let { push(residentSamples, it) }
        val averagedAfterTrackMs = afterTrackSamples.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val averagedResidentMs = residentSamples.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        return reading.copy(
            afterTrackMs = averagedAfterTrackMs,
            trackResidentMs = averagedResidentMs,
        )
    }

    // 入窗并挤掉溢出
    private fun push(samples: ArrayDeque<Float>, value: Float) {
        samples.addLast(value)
        if (samples.size > window) samples.removeFirst()
    }

    companion object {
        /** 采样间隔 1s；均值时长由它与窗口共同决定 */
        const val SAMPLE_INTERVAL_MS = 1_000L

        /** 滑动平均的窗口长度 */
        const val AVERAGE_WINDOW = 5
    }
}
