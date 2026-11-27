package com.yichao.evilgodxu.data.music.playback

import android.media.AudioDeviceInfo
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/** AudioTrack 封装：写入、位置、欠载与异步释放 */
internal class AudioTrackOutput(
    val audioTrack: AudioTrack,
    sampleRate: Int,
    val bufferSizeBytes: Int,
) {

    class WriteException(val errorCode: Int) : Exception("AudioTrack 写入失败：$errorCode")

    private val actualSampleRate: Int = audioTrack.sampleRate.takeIf { it > 0 } ?: sampleRate

    /** 播放头按无符号 32 位读，会回绕 */
    private var lastRawHeadFrames = 0L

    /** 回绕/重置的补齐基准 */
    private var rawHeadBase = 0L

    /** 单调不减的已消费帧数 */
    private var consumedFrames = 0L

    /** 重建后首读作零点 */
    private var headNeedsBaseline = true

    private var stopped = false
    private var stoppedAtUs = 0L
    private var stoppedAtNanos = 0L
    private var endOfStreamUs = Long.MAX_VALUE

    private var released = false

    /** 轨道是否可用，状态须为 INITIALIZED */
    val isUsable: Boolean
        get() = !released && audioTrack.state == AudioTrack.STATE_INITIALIZED

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
            // 读数回退即回绕/重置，补齐基准
            if (raw < lastRawHeadFrames) rawHeadBase += lastRawHeadFrames - raw
            lastRawHeadFrames = raw
            consumedFrames = max(consumedFrames, raw + rawHeadBase)
            return consumedFrames
        }

    /** 设备侧已消费时长，收尾后按系统时钟模拟尾音 */
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

    /** 非阻塞写，返回接受字节数；负值即轨道失效 */
    fun write(buffer: ByteBuffer): Int {
        val written = audioTrack.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
        if (written < 0) throw WriteException(written)
        return written
    }

    /** 启动轨道，返回是否成功；失败由调用方重建 */
    fun start(): Boolean {
        if (!isUsable) return false
        // play 对未初始化轨道抛异常，兜住按失败回报
        if (!runCatching { audioTrack.play() }.isSuccess) return false
        stopped = false
        return true
    }

    fun pause() {
        if (!isUsable) return
        runCatching { audioTrack.pause() }
    }

    /** 标记流结束：记下停止位置并停轨 */
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

    /** 异步释放：延迟在共享线程关闭轨道 */
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
        /** 留出音量斜坡时间再释放 */
        const val RELEASE_DELAY_MS = 20L

        val releaseExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "EchoTide:AudioTrackRelease").apply { isDaemon = true }
        }
    }
}
