package com.yichao.evilgodxu.data.music.playback.usb

import com.yichao.evilgodxu.data.music.playback.PlaybackBufferPolicy
import java.io.Closeable
import java.nio.ByteBuffer

/**
 * 自写 usbfs 等时输出。
 * Java USB API 不支持等时传输。
 */
internal object UsbIsoNative {
    init {
        System.loadLibrary("echotide_usb_iso")
    }

    /** 建流；返回句柄，失败返回 0 */
    external fun open(
        fd: Int,
        outEndpoint: Int,
        frameBytes: Int,
        maxPacketBytes: Int,
        feedbackEndpoint: Int,
        feedbackMaxPacketBytes: Int,
        sampleRate: Int,
        interval: Int,
        packetsPerUrb: Int,
        urbCount: Int,
        inFlightTargetMs: Int,
        ringBytes: Int,
        leadSilenceFrames: Int,
    ): Long

    /** 阻塞写入，返回接纳字节数；为负表示输出已终止 */
    external fun write(handle: Long, pcm: ByteBuffer, offset: Int, bytes: Int): Int

    external fun drain(handle: Long)

    /** 作废环内数据并清零帧计数，不动端点 */
    external fun flushRing(handle: Long)

    /** 暂停/恢复送流，暂停期间环内数据保留 */
    external fun setPaused(handle: Long, paused: Boolean)

    external fun stop(handle: Long)

    external fun close(handle: Long)

    /** 已装入帧数、已送出帧数、欠载、错误、是否断开 */
    external fun stats(handle: Long): LongArray
}

/**
 * 一路等时输出，直送 USB 解码器。
 *
 * 建流前须已接管设备并选好备用设置。
 * 原生调用都在播放线程，close 后不得再调用。
 */
internal class UsbIsoOutput private constructor(
    private val handle: Long,
    /** 每帧字节 = 声道数 × 子槽字节数 */
    private val frameBytes: Int,
    val sampleRate: Int,
    val channels: Int,
    val subslotBytes: Int,
    /** 环形缓冲帧数，供估计休眠上界 */
    val bufferFrames: Int,
) : Closeable {

    /** 关停在播放线程，其他线程可能正读容量 */
    @Volatile
    private var closed = false

    /**
     * 阻塞写入若干帧，返回接纳帧数，负值表示已终止。
     * 只接受直接缓冲，避免额外拷贝。
     */
    fun write(pcm: ByteBuffer, frames: Int): Int {
        if (closed) return -1
        if (frames <= 0) return 0
        val bytes = frames * frameBytes
        val accepted = UsbIsoNative.write(handle, pcm, pcm.position(), bytes)
        if (accepted < 0) return -1
        pcm.position(pcm.position() + accepted)
        return accepted / frameBytes
    }

    /** 等到已写入的数据全部送出设备 */
    fun drain() {
        if (!closed) UsbIsoNative.drain(handle)
    }

    /**
     * 作废环内未送出的数据并清零帧计数，端点不动。
     * 换曲只丢本层未交出的一段，免重开
     * 在飞 URB 仍会送出，调用方的记账清零须同时发生。
     */
    fun flushRing() {
        if (!closed) UsbIsoNative.flushRing(handle)
    }

    /**
     * 暂停期间改走静音填充。
     * 取走环里未播出的部分会让媒体3 判空。
     */
    fun setPaused(paused: Boolean) {
        if (!closed) UsbIsoNative.setPaused(handle, paused)
    }

    fun stop() {
        if (!closed) UsbIsoNative.stop(handle)
    }

    fun stats(): Stats {
        if (closed) return Stats(0, 0, 0, 0, true)
        val values = UsbIsoNative.stats(handle)
        return Stats(
            framesSent = values[0],
            framesPlayed = values[1],
            underruns = values[2],
            transferErrors = values[3],
            disconnected = values[4] != 0L,
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        UsbIsoNative.close(handle)
    }

    data class Stats(
        /** 已装入 URB 的帧数 */
        val framesSent: Long,
        /** 已由主机侧送出的帧数，与 framesSent 差在飞 URB 一段 */
        val framesPlayed: Long,
        val underruns: Long,
        val transferErrors: Long,
        val disconnected: Boolean,
    )

    companion object {
        /** 每个 URB 承载的服务间隔数 */
        private const val PACKETS_PER_URB = 8

        /** 在飞 URB 下限；实际个数由原生层按目标时长抬高 */
        private const val MIN_URB_COUNT = 8

        /** 在飞段目标 64ms，用于吸收线程调度误期（如息屏解锁） */
        private const val IN_FLIGHT_TARGET_MS = 64

        /**
         * 建流后先送 10ms 静音，让设备时钟稳定。
         * 静音期不计欠载与已送出。
         */
        private const val LEAD_SILENCE_MS = 10

        /**
         * 按格式与采样率建流，失败返回 null。
         * 建流即送静音；投不进 URB 也算失败。
         */
        fun create(deviceFd: Int, format: UsbAudioOutputFormat, sampleRate: Int): UsbIsoOutput? {
            if (format.formatTag != PCM_FORMAT_TAG) return null
            val frameBytes = format.frameBytes
            // 环长与直出同口径，不短于在飞段
            val ringFrames = maxOf(
                PlaybackBufferPolicy.targetBufferFrames(sampleRate),
                sampleRate * IN_FLIGHT_TARGET_MS / 1000,
            )
            val ringBytes = frameBytes * ringFrames
            val handle = UsbIsoNative.open(
                fd = deviceFd,
                outEndpoint = format.endpointAddress,
                frameBytes = frameBytes,
                maxPacketBytes = format.maxPacketBytes,
                feedbackEndpoint = format.feedbackEndpoint,
                feedbackMaxPacketBytes = format.feedbackMaxPacketBytes,
                sampleRate = sampleRate,
                interval = format.interval,
                packetsPerUrb = PACKETS_PER_URB,
                urbCount = MIN_URB_COUNT,
                inFlightTargetMs = IN_FLIGHT_TARGET_MS,
                ringBytes = ringBytes,
                leadSilenceFrames = sampleRate * LEAD_SILENCE_MS / 1000,
            )
            if (handle == 0L) return null
            return UsbIsoOutput(
                handle = handle,
                frameBytes = frameBytes,
                sampleRate = sampleRate,
                channels = format.channels,
                subslotBytes = format.subslotBytes,
                bufferFrames = ringBytes / frameBytes,
            )
        }

        /** 格式标签中表示 PCM（Type I）的取值 */
        private const val PCM_FORMAT_TAG = 1
    }
}
