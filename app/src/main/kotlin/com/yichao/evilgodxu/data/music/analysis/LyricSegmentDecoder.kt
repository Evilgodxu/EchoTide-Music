package com.yichao.evilgodxu.data.music.analysis

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.ensureActive

// 逐行音频段：左右声道已重采样到分析采样率，[startMs] 为首样本对应的绝对时间
internal class PcmSegment(
    val left: ShortArray,
    val right: ShortArray,
    val startMs: Long,
) {
    val frames: Int get() = left.size
}

/**
 * 逐行音频解码器：复用同一 MediaCodec 实例，按行起止 seek + flush 后只解码该行前后留少量
 * 上下文的音频段，解码缓冲与内存占用只与单行长度相关，与整轨时长无关。歌词行按时间递增
 * 处理，因此 seek 恒向前。解码结果重采样到分析采样率后交给对齐引擎。
 */
internal class LyricSegmentDecoder private constructor(
    private val extractor: MediaExtractor,
    private val decoder: MediaCodec,
    private val sourceRate: Int,
    private val channels: Int,
    private val targetRate: Int,
    private val durationMs: Long,
) {

    /**
     * 解码 [fromMs, toMs) 区间，返回重采样后的立体声片段；无有效样本时返回 null。
     *
     * 区间短于最小分析长度时向后延长，保证 STFT 有足够的完整窗。
     */
    suspend fun decode(fromMs: Long, toMs: Long): PcmSegment? {
        val start = fromMs.coerceAtLeast(0L)
        val limit = if (durationMs > 0) durationMs else Long.MAX_VALUE
        val end = min(max(toMs, start + MIN_WINDOW_MS), limit)
        if (end <= start) return null
        val startUs = start * 1000
        val endUs = end * 1000

        val sourceFrames = ((end - start) * sourceRate / 1000.0).roundToInt()
        if (sourceFrames <= 0) return null
        val left = FloatArray(sourceFrames)
        val right = FloatArray(sourceFrames)

        decoder.flush()
        // 向前多取一段：seek 落在同步样本上可能晚于目标起点，多解码的部分按时间戳丢弃
        extractor.seekTo(max(0L, startUs - PREROLL_US), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val info = MediaCodec.BufferInfo()
        var pcmEncoding = PcmFormat.ENCODING_16BIT
        var bytesPerSample = PcmFormat.bytesPerSample(pcmEncoding)
        var inputDone = false
        var outputDone = false
        var covered = false
        var written = 0
        while (!outputDone && !covered) {
            // 对齐可取消：关闭对话框后由上层取消协程，解码器由 release 释放
            coroutineContext.ensureActive()
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val sampleSize = extractor.sampleSize
                    // 已取到区间末尾或源已读完：补一个 EOS 让解码器吐出尾部数据后正常收尾
                    if (sampleSize < 0 || extractor.sampleTime > endUs) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                        val size = extractor.readSampleData(inputBuffer, 0)
                        decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    pcmEncoding = PcmFormat.encodingOf(decoder.outputFormat)
                    bytesPerSample = PcmFormat.bytesPerSample(pcmEncoding)
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outputIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (outputBuffer != null && !isConfig && info.size > 0) {
                        written += copySamples(
                            buffer = outputBuffer,
                            info = info,
                            bytesPerSample = bytesPerSample,
                            pcmEncoding = pcmEncoding,
                            startUs = startUs,
                            left = left,
                            right = right,
                        )
                        // 该缓冲已越过区间末尾：后续样本不再需要
                        if (info.presentationTimeUs >= endUs) covered = true
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        if (written <= 0) return null
        return PcmSegment(
            left = resample(left, sourceRate, targetRate),
            right = resample(right, sourceRate, targetRate),
            startMs = start,
        )
    }

    fun release() {
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { extractor.release() }
    }

    /**
     * 按输出缓冲的时间戳把样本落到目标区间：seek 可能落在起点之前，越界样本直接丢弃。
     * 首样本的绝对下标由缓冲时间戳换算，因此 seek 的落点偏差不影响时间轴对齐。
     */
    private fun copySamples(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        bytesPerSample: Int,
        pcmEncoding: Int,
        startUs: Long,
        left: FloatArray,
        right: FloatArray,
    ): Int {
        val stride = channels * bytesPerSample
        val frameCount = info.size / stride
        if (frameCount <= 0) return 0
        val firstIndex = ((info.presentationTimeUs - startUs) * sourceRate / 1_000_000.0).roundToLong()
        val view = buffer.duplicate()
        view.order(ByteOrder.LITTLE_ENDIAN)
        var cursor = info.offset
        var count = 0
        for (i in 0 until frameCount) {
            val index = firstIndex + i
            if (index < 0 || index >= left.size) {
                cursor += stride
                continue
            }
            var leftSample = 0f
            var rightSample = 0f
            for (c in 0 until channels) {
                val sample = PcmFormat.read(view, cursor, pcmEncoding)
                cursor += bytesPerSample
                if (c == 0) leftSample = sample else if (c == 1) rightSample = sample
            }
            // 单声道音源：左右同源，Mid/Side 分离会在侧能量判据上自动跳过
            if (channels == 1) rightSample = leftSample
            val at = index.toInt()
            left[at] = leftSample
            right[at] = rightSample
            count++
        }
        return count
    }

    /**
     * 重采样到分析采样率：降采样前先做长度 k 的移动平均低通抗混叠，再线性插值取目标点。
     * 边界按首尾样本填充，使片段边缘的滤波结果与整轨处理一致。
     */
    private fun resample(input: FloatArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate) return toShorts(input)
        val filtered = if (fromRate > toRate) {
            movingAverage(input, max(1, (fromRate.toDouble() / toRate).roundToInt()))
        } else {
            input
        }
        val outputCount = (filtered.size.toDouble() * toRate / fromRate).roundToInt()
        if (outputCount <= 1) return toShorts(filtered)
        val ratio = fromRate.toDouble() / toRate
        val last = filtered.size - 1
        val out = ShortArray(outputCount)
        for (i in 0 until outputCount) {
            val position = i * ratio
            val base = position.toInt().coerceAtMost(last)
            val next = (base + 1).coerceAtMost(last)
            val fraction = (position - base).toFloat()
            out[i] = toShort(filtered[base] * (1f - fraction) + filtered[next] * fraction)
        }
        return out
    }

    private fun movingAverage(input: FloatArray, kernel: Int): FloatArray {
        if (kernel <= 1) return input
        val half = kernel / 2
        val last = input.size - 1
        val weight = 1f / kernel
        return FloatArray(input.size) { i ->
            var sum = 0f
            for (j in 0 until kernel) sum += input[(i + j - half).coerceIn(0, last)] * weight
            sum
        }
    }

    private fun toShorts(input: FloatArray): ShortArray = ShortArray(input.size) { toShort(input[it]) }

    private fun toShort(value: Float): Short =
        (value * 32768f).coerceIn(-32768f, 32767f).toInt().toShort()

    companion object {
        private const val CODEC_TIMEOUT_US = 10_000L

        // seek 前移余量：吸收 seek 落在同步样本上的偏差
        private const val PREROLL_US = 1_000_000L

        // 最小解码区间：STFT 需要若干个完整窗才有意义（窗长 512 样本约 32ms，取四倍窗长）
        private const val MIN_WINDOW_MS = 128L

        fun open(context: Context, track: MusicTrack, targetRate: Int): LyricSegmentDecoder? {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            return try {
                if (track.path.isNotBlank()) {
                    extractor.setDataSource(track.path)
                } else {
                    extractor.setDataSource(context, Uri.parse(track.audioUri), null)
                }
                var trackIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        trackIndex = i
                        break
                    }
                }
                if (trackIndex < 0) {
                    extractor.release()
                    return null
                }
                val format = extractor.getTrackFormat(trackIndex)
                extractor.selectTrack(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
                val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
                if (mime == null || sampleRate <= 0 || channelCount <= 0) {
                    extractor.release()
                    return null
                }
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(format, null, null, 0)
                decoder.start()
                LyricSegmentDecoder(
                    extractor = extractor,
                    decoder = decoder,
                    sourceRate = sampleRate,
                    channels = channelCount,
                    targetRate = targetRate,
                    durationMs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION) / 1000
                    } else {
                        track.duration
                    },
                )
            } catch (e: Exception) {
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                runCatching { extractor.release() }
                CrashLogManager.logException(
                    "LyricSegmentDecoder",
                    "打开音频解码器失败: 歌曲=${track.title} 路径=${track.path}",
                    e,
                )
                null
            }
        }
    }
}
