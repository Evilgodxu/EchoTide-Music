package com.yichao.evilgodxu.data.music.playback.usb

import java.io.Closeable
import java.nio.ByteBuffer

/**
 * 原生等时输出的绑定。
 *
 * 之所以存在这一层：Java 的 USB 主机 API 不支持等时传输（`UsbRequest` 的类注释只提批量与中断端点），
 * 而 USB 音频的数据流走的正是等时端点。故最后一公里由自写的 usbfs 层完成——只依赖 NDK 自带的平台头
 * `linux/usbdevice_fs.h`，不含任何第三方库。
 *
 * 库名带应用名而非直接叫 usb 系：`libusb*` 这个前缀会让人读成 libusb——而本工程恰恰是**刻意不用它**
 * （见 docs/注意事项.md），名字上跟着混只会让后来者认错实现来源。
 */
internal object UsbIsoNative {
    init {
        System.loadLibrary("echotide_usb_iso")
    }

    /** 建流；返回句柄，失败返回 0（原因由原生层写入 logcat） */
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
        ringBytes: Int,
    ): Long

    /** 阻塞写入；返回接纳的字节数，流已停止或设备已拔出时为负 */
    external fun write(handle: Long, pcm: ByteBuffer, offset: Int, bytes: Int): Int

    external fun drain(handle: Long)

    /** 暂停/恢复送流；暂停期间环内数据保留，见 [UsbIsoStream::setPaused] 的说明 */
    external fun setPaused(handle: Long, paused: Boolean)

    external fun stop(handle: Long)

    external fun close(handle: Long)

    /** `[已装入帧数, 已送出帧数, 欠载次数, 传输错误数, 是否已断开]` */
    external fun stats(handle: Long): LongArray
}

/**
 * 一路等时输出：把 PCM 直接交给 USB 解码器的等时端点。
 *
 * 建流前设备必须已被 [UsbExclusiveDac] 接管、且已按 [UsbAudioOutputFormat] 完成就绪（采样率与备用设置）——
 * 等时端点只存在于非零的备用设置上，未选定前那条端点在设备侧并不存在。
 *
 * 线程约定：**所有进入原生层的调用都在播放线程上**（`write`/`drain`/`setPaused`/`stop`/`stats`），
 * 而 `close` 是句柄的终点——它与上述调用同处一条线程，故不需要锁。这条约定是硬要求而非风格问题：
 * 句柄就是原生对象的指针，`close` 之后任何一次进入原生层的调用都是 use-after-free。
 * 界面侧要把延迟读出来，得走 [UsbExclusiveAudioSink.latencyReading] 的缓存快照，不能直接来这里取。
 *
 * 输入必须是**直接缓冲**，且数据**已按设备子槽排布**（见 [packToSubslot]）：原生层只按
 * `声道数 × 子槽字节数` 切包投递，不做任何样本格式转换。打包留在 Kotlin 是因为它是可判定的纯变换，
 * 能在 JVM 上逐字节核对，不必把正确性押在真机试听上。
 */
internal class UsbIsoOutput private constructor(
    private val handle: Long,
    /** 每帧字节数：声道数 × 子槽字节数，用于把字节数换算成帧数 */
    private val frameBytes: Int,
    val sampleRate: Int,
    val channels: Int,
    val subslotBytes: Int,
    /** 环形缓冲可容纳的帧数：供渲染器估计休眠上界 */
    val bufferFrames: Int,
) : Closeable {

    /** 按易变发布：关停在播放线程上发生，而别的线程可能正拿这个实例读容量这类不变量 */
    @Volatile
    private var closed = false

    /**
     * 阻塞写入若干帧，缓冲位置随之推进。返回接纳的帧数（可能少于 [frames]，甚至为 0）；为负表示本次输出
     * 已终止（停止或设备拔出）。
     *
     * 只接受直接缓冲：数据要交给原生层零拷贝写入，堆上缓冲得先复制一份，而直出链路上每一点额外
     * 拷贝都落在音频路径上。
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
     * 暂停或恢复送流。
     *
     * 暂停期间本层停止把环里的数据交给设备，改用静音填充：环里攒着的正是"已写出而未播出"的部分，
     * 取走它会把待播量抹平，媒体3 据此判输出已空并把播放状态打回缓冲中。
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

    /** 已由主机侧实际送出的帧数：播放位置只能取自它，见 [Stats.framesPlayed] */
    fun playedFrames(): Long = if (closed) 0L else UsbIsoNative.stats(handle)[1]

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        UsbIsoNative.close(handle)
    }

    data class Stats(
        /** 已装入 URB 的帧数 */
        val framesSent: Long,
        /** 已由主机侧送出的帧数；与 [framesSent] 差着在飞 URB 那一整段 */
        val framesPlayed: Long,
        val underruns: Long,
        val transferErrors: Long,
        val disconnected: Boolean,
    )

    companion object {
        /** 每个 URB 承载的服务间隔数 */
        private const val PACKETS_PER_URB = 8

        /** 在飞的 URB 个数：留够余量，调度抖动时才不至于断流 */
        private const val URB_COUNT = 8

        /** 环形缓冲时长占比：够吸收一次调度停顿，又不至于把延迟做大 */
        private const val RING_SECONDS = 0.1

        /**
         * 按 [format] 与 [sampleRate] 建立等时输出。
         *
         * 失败返回 null：独占是可选的一路，建不起输出应当退回，而不是把播放带走。
         */
        fun create(deviceFd: Int, format: UsbAudioOutputFormat, sampleRate: Int): UsbIsoOutput? {
            if (format.formatTag != PCM_FORMAT_TAG) return null
            val frameBytes = format.frameBytes
            val ringBytes = (frameBytes * sampleRate * RING_SECONDS).toInt()
                .coerceAtLeast(frameBytes * PACKETS_PER_URB * URB_COUNT)
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
                urbCount = URB_COUNT,
                ringBytes = ringBytes,
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
