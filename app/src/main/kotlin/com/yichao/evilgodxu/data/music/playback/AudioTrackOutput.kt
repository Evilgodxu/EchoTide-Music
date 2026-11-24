package com.yichao.evilgodxu.data.music.playback

import android.media.AudioDeviceInfo
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * 自研的 `AudioTrack` 输出封装。
 *
 * 只承担「写数据、报位置、报欠载、释放」四件事，格式决策、缓冲策略与编码转换都在调用方。
 * 位置取设备侧播放头：它会滞后，但不会像按系统时钟外推那样越过已写出的量，适合做排空判定与信息展示。
 *
 * 释放异步延后：`AudioTrack.release` 会等音频系统把输出降下来，放在播放线程上同步执行会表现为一次
 * 可闻的停顿；故先静音，再在共享线程上延迟释放。
 */
internal class AudioTrackOutput(
    val audioTrack: AudioTrack,
    sampleRate: Int,
    val bufferSizeBytes: Int,
) {

    class WriteException(val errorCode: Int) : Exception("AudioTrack 写入失败：$errorCode")

    private val actualSampleRate: Int = audioTrack.sampleRate.takeIf { it > 0 } ?: sampleRate

    /** 按无符号 32 位解释播放头：它是按帧计数的回绕值 */
    private var lastRawHeadFrames = 0L

    /** 回绕或平台重置时补齐的基准，使消费量保持连续 */
    private var rawHeadBase = 0L

    /** 单调不减的已消费帧数 */
    private var consumedFrames = 0L

    /** 重建后的首次读数即零点：轨道重建后播放头未必从 0 起算 */
    private var headNeedsBaseline = true

    private var stopped = false
    private var stoppedAtUs = 0L
    private var stoppedAtNanos = 0L
    private var endOfStreamUs = Long.MAX_VALUE

    private var released = false

    /** 已从设备侧消费掉的帧数，单调不减 */
    val consumedFrameCount: Long
        get() {
            if (stopped) return PcmConversion.durationUsToSampleCount(positionUs, actualSampleRate)
            val raw = audioTrack.playbackHeadPosition.toLong() and PcmConversion.UNSIGNED_INT_MASK
            if (headNeedsBaseline) {
                headNeedsBaseline = false
                lastRawHeadFrames = raw
                rawHeadBase = -raw
                consumedFrames = 0L
                return 0L
            }
            // 读数回退只可能来自回绕或平台重置：以旧读数补齐基准，消费量因此保持连续
            if (raw < lastRawHeadFrames) rawHeadBase += lastRawHeadFrames - raw
            lastRawHeadFrames = raw
            consumedFrames = max(consumedFrames, raw + rawHeadBase)
            return consumedFrames
        }

    /**
     * 设备侧已消费的时长。
     *
     * 收尾（[markEndOfStream]）之后播放头不再推进，故按停止那一刻的位置与系统时钟模拟尾音推进，
     * 上限为已写出的总时长；排空判定要等这段模拟走完才会收敛。
     */
    val positionUs: Long
        get() {
            if (released) return stoppedAtUs
            if (!stopped) {
                return framesToDurationUs(consumedFrameCount)
            }
            val elapsedUs = (System.nanoTime() - stoppedAtNanos) / 1_000L
            return min(stoppedAtUs + elapsedUs, endOfStreamUs)
        }

    val bufferSizeInFrames: Int
        get() = audioTrack.bufferSizeInFrames

    val underrunCount: Int
        get() = audioTrack.underrunCount

    /**
     * 写一块数据，返回音频轨实际接受的字节数。
     *
     * 非阻塞写：轨道满时返回 0，调用方保留余量、下次继续；返回负值即轨道已失效。
     */
    fun write(buffer: ByteBuffer): Int {
        val written = audioTrack.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        if (written < 0) throw WriteException(written)
        return written
    }

    fun play() {
        if (released) return
        stopped = false
        audioTrack.play()
    }

    fun pause() {
        if (released) return
        audioTrack.pause()
    }

    /**
     * 标记流已结束：记下停止位置并让系统停住轨道。
     *
     * 之后 [positionUs] 按系统时钟把剩余时长推完，调用方据此判定排空；真正的尾部由音频系统自己播完。
     */
    fun markEndOfStream(writtenFrames: Long) {
        if (stopped || released) return
        stoppedAtUs = framesToDurationUs(consumedFrameCount)
        stoppedAtNanos = System.nanoTime()
        endOfStreamUs = framesToDurationUs(writtenFrames)
        stopped = true
        runCatching { audioTrack.stop() }
    }

    fun flush() {
        if (released) return
        runCatching { audioTrack.flush() }
        resetPositionTracking()
    }

    fun setVolume(volume: Float) {
        if (released) return
        runCatching { audioTrack.setVolume(volume) }
    }

    fun setPreferredDevice(device: AudioDeviceInfo?) {
        if (released) return
        runCatching { audioTrack.setPreferredDevice(device) }
    }

    fun attachAuxEffect(effectId: Int) {
        if (released || effectId == 0) return
        runCatching { audioTrack.attachAuxEffect(effectId) }
    }

    fun setAuxEffectSendLevel(level: Float) {
        if (released) return
        runCatching { audioTrack.setAuxEffectSendLevel(level) }
    }

    /** 异步释放：先等音频系统把输出降下来，再在共享线程上关闭轨道 */
    fun release() {
        if (released) return
        released = true
        val track = audioTrack
        releaseExecutor.schedule(
            {
                runCatching { track.flush() }
                runCatching { track.release() }
            },
            RELEASE_DELAY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun resetPositionTracking() {
        lastRawHeadFrames = 0L
        rawHeadBase = 0L
        consumedFrames = 0L
        headNeedsBaseline = true
        stopped = false
        stoppedAtUs = 0L
        stoppedAtNanos = 0L
        endOfStreamUs = Long.MAX_VALUE
    }

    private fun framesToDurationUs(frames: Long): Long =
        PcmConversion.sampleCountToDurationUs(frames, actualSampleRate)

    private companion object {
        /** 留出一个音量斜坡的时间，避免释放时切掉正在衰减的尾部 */
        const val RELEASE_DELAY_MS = 20L

        val releaseExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "EchoTide:AudioTrackRelease").apply { isDaemon = true }
        }
    }
}
