package com.yichao.evilgodxu.data.music.analysis

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/**
 * 逐帧音频能量包络：高潮判定的音频侧依据。
 *
 * 与频谱图（[Spectrogram]）是两种口径，不要混用 —— 频谱图给的是渲染用的时频强度矩阵（2048 点 FFT、
 * 上千频行），本类只要一条搬得动的响度曲线与一条高频占比曲线，故不做 FFT：逐帧累积均方根即响度，
 * 累积相邻样本一阶差分绝对值即高频占比的代理量（信号越亮，相邻样本跳变越大）。
 * 这样整曲分析只多一次线性扫描，代价落在解码本身。
 *
 * 数组长度即帧数，第 i 帧对应 [i · frameMs, (i + 1) · frameMs)。两条曲线都已按全曲峰值归一化到 0..1：
 * 响度取相对峰值的分贝映射（[LOUDNESS_RANGE_DB] 为下限），高频占比按自身峰值线性缩放 ——
 * 于是同一套阈值对轻响不同的曲目都成立，不必按曲目调参。
 */
class EnergyEnvelope(
    val loudness: FloatArray,
    val brightness: FloatArray,
    val frameMs: Long,
) {

    val frameCount: Int get() = loudness.size

    val durationMs: Long get() = frameCount * frameMs

    /** 时间落到第几帧；越界一律钳到端点，调用方不必先判范围 */
    fun frameAt(ms: Long): Int =
        (ms / frameMs).toInt().coerceIn(0, (frameCount - 1).coerceAtLeast(0))
}

/**
 * 整曲能量包络解码：MediaCodec 解出 PCM，逐样本累积成逐帧响度与高频占比。
 *
 * 无歌词或歌词无时间轴时，这是定位高潮的唯一依据；有歌词时用来在多候选之间择优（副歌通常更响、更亮）。
 * 与 [SpectrogramDecoder] 一样只在后台扫描路径调用，不参与播放。解码失败（格式不支持、源不可读）
 * 一律返回 null，调用方据此退回纯歌词结果或整曲播放，绝不因为音频读不出就误判「没有副歌」。
 */
internal object EnergyEnvelopeDecoder {

    // 分析帧长：100ms 足以分辨副歌的进出，又让 4 分钟的曲子只有约两千四百帧
    private const val FRAME_MS = 100
    // 响度映射下限：相对峰值 −60dB 记作 0，峰值记作 1
    private const val LOUDNESS_RANGE_DB = 60f
    private const val CODEC_TIMEOUT_US = 10_000L
    // 输入缓冲只做非阻塞轮询：填满解码器内部队列即转去取输出，节流交给输出端的等待
    private const val INPUT_POLL_TIMEOUT_US = 0L
    private const val PROGRESS_STEPS = 50

    suspend fun decode(
        context: Context,
        track: MusicTrack,
        onProgress: (Float) -> Unit = {},
    ): EnergyEnvelope? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            if (!setDataSource(context, track, extractor)) return null
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0) return null
            val mediaFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = mediaFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
            val channels = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
            if (sampleRate <= 0 || channels <= 0) return null

            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            codec.configure(mediaFormat, null, null, 0)
            codec.start()

            // 进度分母取容器时长优先、曲目时长兜底；帧数由实际解码样本决定，不依赖时长是否准确
            val containerDurationUs = mediaFormat.getLong(MediaFormat.KEY_DURATION, 0L)
            val durationUs =
                if (containerDurationUs > 0L) containerDurationUs else track.duration * 1000L
            val expectedSamples = (durationUs * sampleRate / 1_000_000L).coerceAtLeast(1L)
            val framesPerWindow = (sampleRate.toLong() * FRAME_MS / 1000L).coerceAtLeast(1L)

            val accumulator = FrameAccumulator(framesPerWindow, channels)
            val info = MediaCodec.BufferInfo()
            var pcmEncoding = PcmFormat.ENCODING_16BIT
            var inputEos = false
            var outputEos = false
            var decodedSamples = 0L
            var progressStep = -1
            while (!outputEos) {
                coroutineContext.ensureActive()
                if (!inputEos) {
                    while (true) {
                        val inIndex = codec.dequeueInputBuffer(INPUT_POLL_TIMEOUT_US)
                        if (inIndex < 0) break
                        val sampleSize = extractor.sampleSize
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEos = true
                            break
                        }
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inputBuffer, 0)
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // 24bit FLAC 在部分设备按 24bit/32bit 输出，字节宽须跟随编码而非固定 16 位
                        pcmEncoding = PcmFormat.encodingOf(codec.outputFormat)
                        continue
                    }
                    if (outIndex < 0) break
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val outputBuffer =
                        if (isConfig || info.size <= 0) null
                        else codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null) {
                        decodedSamples += accumulator.feed(
                            buffer = outputBuffer,
                            offset = info.offset,
                            size = info.size,
                            encoding = pcmEncoding,
                        )
                        val step = (decodedSamples * PROGRESS_STEPS / expectedSamples).toInt()
                        if (step != progressStep) {
                            progressStep = step
                            onProgress(
                                (decodedSamples.toDouble() / expectedSamples)
                                    .coerceIn(0.0, 1.0)
                                    .toFloat(),
                            )
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                        break
                    }
                }
            }
            accumulator.build()
        } catch (e: CancellationException) {
            // 协程取消（如退出扫描）属正常流程：不记日志，重新抛出
            throw e
        } catch (e: Exception) {
            CrashLogManager.logException("EnergyEnvelope", "音频能量包络解码失败", e)
            null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    // 数据源：本地路径优先，缺失时经 ContentResolver 打开——两种来源在本应用都算本地音频源
    private fun setDataSource(
        context: Context,
        track: MusicTrack,
        extractor: MediaExtractor,
    ): Boolean = runCatching {
        if (track.path.isNotBlank()) {
            extractor.setDataSource(track.path)
        } else {
            extractor.setDataSource(context, track.audioUri.toUri(), null)
        }
    }.isSuccess

    /**
     * 逐帧累积器：解码线程顺序喂入输出缓冲，本类把样本按 [framesPerWindow] 归帧。
     *
     * 解码输出缓冲与帧边界并不对齐（缓冲长度随解码器而定），故在这里按样本连续计数 ——
     * 缓冲之间不断帧，帧序才与整曲时间轴一致。响度用均方根、高频占比用相邻样本一阶差分绝对值均值；
     * 差分跨帧连续（保留上一帧的末样本），避免每帧首点被当作一次跳变而系统性抬高亮度。
     */
    private class FrameAccumulator(
        private val framesPerWindow: Long,
        private val channels: Int,
    ) {

        private val loudness = ArrayList<Float>()
        private val brightness = ArrayList<Float>()
        private var sumSquares = 0.0
        private var sumAbsDiff = 0.0
        private var diffCount = 0L
        private var windowCount = 0L
        private var previous = 0f
        private var hasPrevious = false

        // 消费一段解码输出，返回本次消费的采样帧数
        fun feed(buffer: ByteBuffer, offset: Int, size: Int, encoding: Int): Long {
            val bytesPerSample = PcmFormat.bytesPerSample(encoding)
            val frameBytes = (channels * bytesPerSample).coerceAtLeast(1)
            val frames = size / frameBytes
            if (frames <= 0) return 0L
            val view = buffer.duplicate()
            var cursor = offset
            for (i in 0 until frames) {
                var acc = 0f
                for (c in 0 until channels) {
                    acc += PcmFormat.read(view, cursor, encoding)
                    cursor += bytesPerSample
                }
                val sample = acc / channels
                sumSquares += sample.toDouble() * sample
                if (hasPrevious) {
                    sumAbsDiff += abs(sample - previous).toDouble()
                    diffCount++
                }
                previous = sample
                hasPrevious = true
                windowCount++
                if (windowCount == framesPerWindow) flushWindow()
            }
            return frames.toLong()
        }

        private fun flushWindow() {
            val rms = sqrt(sumSquares / windowCount).toFloat()
            val diff = (sumAbsDiff / diffCount.coerceAtLeast(1L)).toFloat()
            loudness += rms
            brightness += diff
            sumSquares = 0.0
            sumAbsDiff = 0.0
            diffCount = 0L
            windowCount = 0L
        }

        // 收尾归一化：响度按相对峰值的分贝映射，高频占比按自身峰值线性缩放。
        // 无任何有效帧、或全程静音（峰值为 0）时返回 null，由调用方按「音频不可用」处理
        fun build(): EnergyEnvelope? {
            val rawLoudness = loudness.toFloatArray()
            if (rawLoudness.isEmpty()) return null
            val peakLoudness = rawLoudness.max()
            if (peakLoudness <= 0f) return null
            val floorDb = -LOUDNESS_RANGE_DB
            for (i in rawLoudness.indices) {
                val db = 20f * log10((rawLoudness[i] / peakLoudness).coerceAtLeast(1e-6f))
                rawLoudness[i] = ((db - floorDb) / -floorDb).coerceIn(0f, 1f)
            }
            val rawBrightness = brightness.toFloatArray()
            val peakBrightness = rawBrightness.max().coerceAtLeast(1e-6f)
            for (i in rawBrightness.indices) {
                rawBrightness[i] = (rawBrightness[i] / peakBrightness).coerceIn(0f, 1f)
            }
            return EnergyEnvelope(
                loudness = rawLoudness,
                brightness = rawBrightness,
                frameMs = FRAME_MS.toLong(),
            )
        }
    }
}
