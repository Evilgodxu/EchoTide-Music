package com.yichao.evilgodxu.data.music.playback

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build

// 帧位计数为 32 位：播放头与时间戳都会回绕，差值只能按模取，跨回绕时才得真实距离
private const val FRAME_COUNT_MODULUS = 1L shl 32
private const val FRAME_COUNT_MASK = FRAME_COUNT_MODULUS - 1

// 可信上界：超过该量级即平台误报，宁缺不用（媒体3 判时间戳无效取的是同一量级）
private const val MAX_PLAUSIBLE_LATENCY_MS = 5_000f

// 写入帧位可读的最低 API 级别：低于它拿不到「已写进音频轨」的位置，全链路延迟因此不产出
private const val API_WRITTEN_FRAMES = 37

/**
 * 输出链路的实测延迟读数。
 *
 * 链路按音频轨切成两段，两段各自可测，相加即全链路：
 * - [afterTrackMs]：数据**离开音频轨之后**的那一段——AudioFlinger 的混音缓冲、HAL 与设备传输、
 *   解码器自身的排队。它由音频轨把数据交出去起算，与音频轨容量无关，也是全链路能到的最低值
 *   （驻留为 0 时全链路就等于它）。这一段之内不再可拆：没有接口能把「设备传输」与「解码器自身缓冲」
 *   分开，只能靠换通路对照（同一时刻另测内置通路相减）；
 * - [trackResidentMs]：**音频轨自身的驻留**，即已写入而未交出的量。缓冲策略直接决定它，上限为
 *   [trackBufferFrames]；
 * - [fullChainMs]：从**写进音频轨**起算的全链路，恒等于上面两段之和。
 *
 * 容量与驻留并列，才能看出缓冲策略的效果与余量：容量是申请到的上限，驻留是此刻实际占用。
 */
internal data class OutputLatencyReading(
    /** 音频轨缓冲容量（帧） */
    val trackBufferFrames: Int,
    /** 音频轨缓冲容量对应的时长（毫秒） */
    val trackBufferMs: Float,
    /** 音频轨之后那一段（毫秒）；链路未推进或时间戳不可用时为 null */
    val afterTrackMs: Float?,
    /** 音频轨自身的驻留（毫秒）；取不到写入帧位时为 null */
    val trackResidentMs: Float?,
    /** 从写进音频轨起算的全链路（毫秒）；取不到写入帧位时为 null */
    val fullChainMs: Float?,
)

/**
 * 输出链路的延迟实测。
 *
 * 平台没有按设备查询延迟的公开接口：`AudioDeviceInfo` 不上报延迟，而「输出帧数 ÷ 采样率」只是 HAL
 * 单次突发的时长——它与当前走哪一路输出无关，挂 USB 解码器时依旧是那个最小值，USB 传输与解码器
 * 自身的排队恰恰落在这一段之外，故该值不能代表输出延迟。
 *
 * 真正含设备那一段的量只能从当前链路的音频轨上取。[AudioTrack.getTimestamp] 给出「第几帧在什么时刻
 * 呈现」，两边都以 Monotonic 时钟为基准，故可线性推到现在；再与同一时刻的两个帧位相比：
 *
 * - 与**播放头**（[AudioTrack.getPlaybackHeadPosition]）相比，得 [OutputLatencyReading.afterTrackMs]
 *   ——播放头是音频系统已经取走的位置，差值即「离开音频轨之后还要多久才发声」，取自公开接口且各系统
 *   稳定可用；
 * - 与**写入帧位**相比，得全链路——它是应用已经写进音频轨的位置，差值含音频轨自身的驻留。
 *   该帧位优先取自应用自己的输出实现（逐次记账音频轨实际接受的字节，任何 API 都有）；媒体3 的默认
 *   输出不暴露写入量，那两个变体只能退到平台接口 [AudioTrack.getWrittenFramesCount]（API 37 起可读），
 *   再取不到时就只产出前一段。
 *
 * 取不到时不产出对应值：不接受与当前路由无关的下界值顶替，也不拿上界冒充实测。
 */
internal object OutputLatency {

    /**
     * 读一次当前音频轨的延迟读数，轨道不可用时返回 null。
     *
     * 延迟只在链路推进时才谈得上：未推进时播放头与时间戳都不随时间前进，比对出来的差值无从对应到
     * 出站后的那一段，故两项延迟此时留空；容量是轨道的属性，不受推进与否影响，照常给出。
     *
     * [writtenFrames] 是「已写进音频轨的帧数」的取值入口，取不到时传 null，此时退到平台接口。
     */
    fun read(track: AudioTrack?, writtenFrames: (() -> Long?)?): OutputLatencyReading? {
        if (track == null) return null
        if (track.state != AudioTrack.STATE_INITIALIZED) return null
        val sampleRate = track.sampleRate.takeIf { it > 0 } ?: return null
        val bufferFrames = track.bufferSizeInFrames.takeIf { it > 0 } ?: return null
        val advanced = advancedLatencyMs(track, sampleRate, bufferFrames, writtenFrames)
        return OutputLatencyReading(
            trackBufferFrames = bufferFrames,
            trackBufferMs = bufferFrames * MILLIS_PER_SECOND / sampleRate,
            afterTrackMs = advanced?.afterTrackMs,
            trackResidentMs = advanced?.trackResidentMs,
            fullChainMs = advanced?.fullChainMs,
        )
    }

    private data class AdvancedLatency(
        val afterTrackMs: Float,
        val trackResidentMs: Float?,
        val fullChainMs: Float?,
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
        // 时间戳的帧位加上这段时间的推移，即此刻正在呈现的帧位
        val presentedFrames =
            (timestamp.framePosition + elapsedNanos * sampleRate / NANOS_PER_SECOND) and FRAME_COUNT_MASK
        val headFrames = track.playbackHeadPosition.toLong() and FRAME_COUNT_MASK
        val afterTrackFrames = frameDistance(headFrames, presentedFrames)
        val afterTrackMs = afterTrackFrames.toMs(sampleRate) ?: return null
        val residentMs = writtenResidueFrames(track, headFrames, bufferFrames, writtenFrames)
            ?.toMs(sampleRate)
        // 全链路按两段相加得出，不另做一次帧到毫秒的换算：面板上三行因此恒能对上
        val fullChainMs = residentMs
            ?.let { resident -> (afterTrackMs + resident).takeIf { it <= MAX_PLAUSIBLE_LATENCY_MS } }
        return AdvancedLatency(afterTrackMs, residentMs, fullChainMs)
    }

    /**
     * 已写进音频轨而尚未交出的帧数，即音频轨自身的驻留。
     *
     * 写入帧位有两个来源，优先取应用自己的输出实现：[writtenFrames] 由音频输出逐次记账音频轨实际
     * 接受的字节，任何 API 上都能精确给出；媒体3 的默认输出不暴露写入量，那两个变体只能退到平台接口
     * [AudioTrack.getWrittenFramesCount]（API 37 起可读）。两者都拿不到时为 null，全链路延迟随之不产出。
     *
     * 两个来源的基准都与播放头一致：写入帧位自音频轨创建起累计，而播放头会被冲刷与停止清零；媒体3 在
     * 每次冲刷时整个释放并重建音频轨（默认输出与 24 位输出都是如此），写入量也随之归零，故同一条音频轨
     * 内两者基准相同，差值才是真实的驻留。驻留不可能超过轨道容量，超过即说明基准已错位（如音频轨被停过），
     * 此时宁缺不用。
     *
     * 平台接口的取值只做观测，不该让它的异常影响播放。
     */
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

    // 平台侧提供的写入帧位：API 37 起可读，更低的版本返回 null
    private fun platformWrittenFrames(track: AudioTrack): Long? {
        if (Build.VERSION.SDK_INT < API_WRITTEN_FRAMES) return null
        return runCatching { track.writtenFramesCount }.getOrNull()
    }

    // 两个帧位之差按模取：两者都是回绕计数，跨回绕时才得真实距离
    private fun frameDistance(later: Long, earlier: Long): Long =
        ((later - earlier) and FRAME_COUNT_MASK)

    // 帧数换算时长：超出可信上界即判为平台误报，不产出该值
    private fun Long.toMs(sampleRate: Int): Float? =
        (this * MILLIS_PER_SECOND / sampleRate).takeIf { it in 0f..MAX_PLAUSIBLE_LATENCY_MS }

    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val MILLIS_PER_SECOND = 1_000f
}

/**
 * 输出延迟读数的滑动平均采样器。
 *
 * 平台明确说明「时间戳在短期内的相邻两次差异不具意义」，单次取值直接展示会把抖动当成变化，故按最近
 * [window] 次取值取平均——两段延迟各成一窗，全链路取两段均值之和。容量是音频轨的属性而非随时波动的
 * 观测量，不参与平均。
 *
 * 取不到延迟的采样不入窗：那时链路并未推进，掺入零值会把均值拉低。窗口随每次成功采样推移，旧样本
 * 自然被挤出，无需专门复位；换了一条音频轨则由调用方换一个采样器，历史样本不会跨轨混入。
 */
internal class OutputLatencySampler(private val window: Int = AVERAGE_WINDOW) {

    private val afterTrackSamples = ArrayDeque<Float>()
    private val residentSamples = ArrayDeque<Float>()

    /**
     * 采一次并给出窗口内的均值。
     *
     * [writtenFrames] 由调用方逐次传入而不在采样器内持有：它读的是当前生效链路的写入量，属外部状态，
     * 缓存下来会让窗口跨链路混入。
     */
    fun sample(track: AudioTrack?, writtenFrames: (() -> Long?)?): OutputLatencyReading? {
        val reading = OutputLatency.read(track, writtenFrames) ?: return null
        val afterTrackMs = reading.afterTrackMs ?: return reading
        push(afterTrackSamples, afterTrackMs)
        reading.trackResidentMs?.let { push(residentSamples, it) }
        val averagedAfterTrackMs = afterTrackSamples.average().toFloat()
        val averagedResidentMs = residentSamples.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        return reading.copy(
            afterTrackMs = averagedAfterTrackMs,
            trackResidentMs = averagedResidentMs,
            // 全链路取两段均值之和，而不是把每次读数各自求和后再平均：面板上三行因此恒能对上
            fullChainMs = averagedResidentMs?.let { averagedAfterTrackMs + it },
        )
    }

    // 入窗并挤掉溢出的一次：窗口只覆盖最近的一段播放
    private fun push(samples: ArrayDeque<Float>, value: Float) {
        samples.addLast(value)
        if (samples.size > window) samples.removeFirst()
    }

    companion object {
        /**
         * 采样间隔（毫秒）。
         *
         * 平台对时间戳的取值有明确提醒：相邻两次的短期差异不具意义，建议的间隔是十秒级。但读数要跟手，
         * 故取一秒——均值覆盖的时长由本值与 [AVERAGE_WINDOW] 共同决定，两者须一起看。
         */
        const val SAMPLE_INTERVAL_MS = 1_000L

        /** 滑动平均的窗口长度 */
        const val AVERAGE_WINDOW = 5
    }
}
