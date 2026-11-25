package com.yichao.evilgodxu.data.music.playback.usb

import com.yichao.evilgodxu.data.music.playback.PlaybackBufferPolicy
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
 * （见 docs/archive/注意事项.md），名字上跟着混只会让后来者认错实现来源。
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

    /** 阻塞写入；返回接纳的字节数，流已停止或设备已拔出时为负 */
    external fun write(handle: Long, pcm: ByteBuffer, offset: Int, bytes: Int): Int

    external fun drain(handle: Long)

    /** 作废环内数据并清零帧计数，流与端点保持不变；见 [UsbIsoStream::flushRing] */
    external fun flushRing(handle: Long)

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
     * 作废环内尚未送出的数据，并把两个帧计数清零；流本身与设备侧的端点保持不动。
     *
     * 换曲与跳转都走这里而不重建流：端点一关一开，设备要重新锁一次时钟，期间既失声又可能把内部缓冲
     * 重放一段（听感是回退）。环内作废只丢本层还没交出去的那一段，端点毫无感知。
     *
     * 已进入在飞 URB 的数据撤不回，仍会送出——它最多是一个在飞段的时长，故调用方的记账清零必须与
     * 本调用同时发生，否则"已写出 − 已送出"会出现负值。
     */
    fun flushRing() {
        if (!closed) UsbIsoNative.flushRing(handle)
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

        /**
         * 在飞 URB 个数的下限：整数个 URB 才构成流水线。
         *
         * 真实个数由原生层按下面的目标时长与实际包率抬高——高速下一个 URB 只有 1ms，拿固定个数当余量
         * 会在不同总线速度下差出八倍（见 [IN_FLIGHT_TARGET_MS]）。
         */
        private const val MIN_URB_COUNT = 8

        /**
         * 在飞段的目标时长（毫秒）：调度余量按时间给定，由原生层按实际包率折算成 URB 个数。
         *
         * 这一段是唯一能吸收**线程被抢占**的余量，环里存多少都不作数：回收线程不跑，环里的数据一个字节
         * 也送不出去，设备侧照样断流（欠载计数也不会响，它只认「取数据时环是空的」）。故本值只由一件事
         * 定：**要容忍多久的调度误期**。取 64ms 的根据是这一类现场的量级——从桌面启动一个第三方应用就
         * 足以让送流线程误期数十毫秒，而息屏解锁（SystemUI + 锁屏 + 桌面 + 显示唤醒同时上，CPU 还要从
         * 熄屏的降频与离线核状态爬升）比它更长。原先取的 16ms 是按「启动单个应用」标定的，盖不住后者。
         *
         * 顺带把两种总线速度取齐：高速下本值得出 64 个 URB（64ms），全速下一个 URB 本就长达 8 个服务
         * 间隔、按 [MIN_URB_COUNT] 已是不低于 64ms（bInterval 更长时更长）。原先高速 16ms、全速 64ms 的
         * 差别没有任何依据，只是折算方式带出来的。
         *
         * 代价落在**输出滞后**上，与缓冲时长的那一侧同源：这一段数据比"此刻在听的内容"更靠前，它不进面板
         * 的「缓冲驻留」（那一段只算环内存量），但曲末排空要等它送完（`drain()` 按本时长等），故曲目之间
         * 的衔接会跟着长出这一段。判据是"断续换不换得来"：一次可闻的断口比几十毫秒的衔接更值得避免。
         */
        private const val IN_FLIGHT_TARGET_MS = 64

        /**
         * 建流后先送出的静音时长（毫秒）。
         *
         * 端点按新的采样率与备用设置重开之后，设备要先稳定自己的时钟与内部缓冲，这段时间里它可能丢掉
         * 头几个包。建流时投出的在飞段本来就装着一整段零（[MIN_URB_COUNT] 与 [IN_FLIGHT_TARGET_MS]
         * 折算出的那 64ms 及以上），故本值不是那段余量的替代品，只是在它之上再补一小段：把「设备重开后
         * 的偏离期有多久」写成显式的一档，而不是让它取决于在飞段折算出来的时长。
         *
         * 静音期间原生层不取环、不计欠载、不计已送出，故播放位置与待播量都不受它影响；代价只是起播
         * 晚这么多，远小于一次可闻的爆音。
         */
        private const val LEAD_SILENCE_MS = 10

        /**
         * 按 [format] 与 [sampleRate] 建立等时输出。
         *
         * 建流即开始送出静音：前半段由在飞 URB 的初始投递给出（环空时整批补零），再叠上
         * [LEAD_SILENCE_MS] 那一小段显式的静音，设备按新档重开端点后的偏离期才落在零数据上。
         *
         * 失败返回 null：独占是可选的一路，建不起输出应当退回，而不是把播放带走。除参数不成立外，
         * **一个 URB 都投不进内核**也算建不起来——那说明端点不在当前的备用设置上（或设备已不可用），
         * 交出一个写不进去的流只会把失败推迟到第一次写出。见 [UsbExclusiveAudioSink] 的重建口径。
         */
        fun create(deviceFd: Int, format: UsbAudioOutputFormat, sampleRate: Int): UsbIsoOutput? {
            if (format.formatTag != PCM_FORMAT_TAG) return null
            val frameBytes = format.frameBytes
            // 环长取与直出路径同一个目标帧数（见 PlaybackBufferPolicy）：两条路的缓冲深度一致，
            // 同一台设备上换开关才不会换一种断流/滞后表现。另不小于在飞段的**目标**时长——环比在飞段
            // 还短时播放线程写不进、回收线程也腾不出，表现为周期性停顿。全速下的实际在飞段（bInterval=1
            // 时 64ms，更长间隔下更长）与高速下的目标值（64ms）都低于目标帧数（80ms 基准），故这一侧
            // 两者都盖得住
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
