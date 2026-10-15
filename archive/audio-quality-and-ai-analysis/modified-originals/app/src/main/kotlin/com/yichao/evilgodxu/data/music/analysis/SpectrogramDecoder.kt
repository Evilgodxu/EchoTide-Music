package com.yichao.evilgodxu.data.music.analysis

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

// 全曲解码产物：渲染矩阵与判定摘要出自同一次解码。两者都可能为 null——
// 前者表示音频不可解码/无有效帧，后者表示未累计到任何 FFT 块
internal class FullSpectrumDecode(
    val spectrogram: Spectrogram?,
    val summary: SpectralDecoder.DecodeSummary?,
)

// 全曲时频分析：把整首音频解码为 PCM，逐窗做短时傅里叶变换，
// 产出可直接渲染成频谱图的强度矩阵；同一份 PCM 同时喂入 SpectralDecoder.Accumulator，
// 产出覆盖整曲的判定摘要。与 SpectralDecoder 的分工在于取样范围与产出形态——
// 后者只取 3 段探测窗求平均功率谱，本解码器覆盖全曲并保留逐帧频谱，故不复用其解码循环，
// 但两者共用同一份判定摘要累加器，判据输入的刻度完全一致。
internal object SpectrogramDecoder {

    // 分析参数：2048 点 FFT，44.1k 下约 21.5Hz/桶；跳步取半窗保 50% 重叠，
    // 既满足 Hann 窗的相邻帧连续性，也让变换覆盖全部采样而非抽样
    private const val FFT_SIZE = 2048
    private const val HOP_SIZE = FFT_SIZE / 2
    private const val HALF_SPECTRUM = FFT_SIZE / 2 + 1
    // 频率方向输出行数：与半谱桶数同阶，逐行只落到一两个桶上，
    // 即保留 FFT 本身的频率分辨率而不做有损归并——竖屏下图被纵向拉伸，行数不足会显出台阶
    private const val FREQ_ROWS = 1024
    // 分析帧数上限：达到上限即两两合并，使内存与输出规模在任意时长下都有上界
    private const val MAX_FRAMES = 2048
    private const val CODEC_TIMEOUT_US = 10_000L
    // 进度上报档数：按容器时长的百分比分档回调，避免逐缓冲上报引发无谓重组
    private const val PROGRESS_STEPS = 50

    // 解码并分析整首音频；进度取已解码采样数相对容器时长的比例，协程取消时即时释放解码器。
    // 无法解出任何有效帧时两项产物均为 null，由调用方按失败处理
    suspend fun decode(
        track: MusicTrack,
        onProgress: (Float) -> Unit = {},
    ): FullSpectrumDecode {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(track.path)
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0) return FullSpectrumDecode(null, null)
            val mediaFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = mediaFormat.getString(MediaFormat.KEY_MIME) ?: return FullSpectrumDecode(null, null)
            val sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
            val channels = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
            if (sampleRate <= 0 || channels <= 0) return FullSpectrumDecode(null, null)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(mediaFormat, null, null, 0)
            decoder.start()

            // 预期采样数取容器时长优先、曲目时长兜底：仅作进度分母，
            // 分析帧数由跳步与帧数上限决定，不依赖时长是否准确
            val containerDurationUs = mediaFormat.getLong(MediaFormat.KEY_DURATION, 0L)
            val durationUs =
                if (containerDurationUs > 0L) containerDurationUs else track.duration * 1000L
            val expectedSamples = (durationUs * sampleRate / 1_000_000L).coerceAtLeast(1L)

            val collector = FrameCollector()
            // 判定摘要与渲染矩阵共用同一份 PCM：判定走 4096 点网格，渲染走 2048 点，
            // 故两条累加各自独立，但都来自这一次解码，不再为出判定另开一路解码器
            val accumulator = SpectralDecoder.Accumulator(sampleRate, channels)
            val info = MediaCodec.BufferInfo()
            var pcmEncoding = PcmFormat.ENCODING_16BIT
            var inputEos = false
            var outputEos = false
            var decodedSamples = 0L
            var progressStep = -1
            while (!outputEos) {
                // 分析进行中保持可取消：离开页面即中止，解码器由外层 finally 释放
                coroutineContext.ensureActive()
                // 喂入输入：连读提取器样本直至片尾
                if (!inputEos) {
                    val inIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val sampleSize = extractor.sampleSize
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEos = true
                        } else {
                            val inputBuffer = decoder.getInputBuffer(inIndex)!!
                            val size = extractor.readSampleData(inputBuffer, 0)
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                // 取出输出：逐缓冲解交织单声道并送入滑动窗
                when (val outIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 24bit FLAC 在部分设备按 24bit/32bit 输出，字节宽必须跟随编码而非固定 16 位
                        pcmEncoding = PcmFormat.encodingOf(decoder.outputFormat)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outIndex >= 0) {
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val outputBuffer =
                            if (isConfig || info.size <= 0) null
                            else decoder.getOutputBuffer(outIndex)
                        if (outputBuffer != null) {
                            decodedSamples += collector.feedBuffer(
                                outputBuffer, info.offset, info.size, channels, pcmEncoding,
                            )
                            accumulator.feed(outputBuffer, info.offset, info.size, pcmEncoding)
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
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                    }
                }
            }
            FullSpectrumDecode(
                spectrogram = collector.build(sampleRate),
                summary = accumulator.summary(),
            )
        } catch (e: CancellationException) {
            // 协程取消（如退出页面）属正常流程：不记日志，重新抛出
            throw e
        } catch (e: Exception) {
            CrashLogManager.logException("SpectrogramDecoder", "频谱图解码失败", e)
            FullSpectrumDecode(null, null)
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    // 逐窗累积器：跨输出缓冲维持一个滑动窗，填满即变换并把半谱功率归并成一行；
    // 逐帧行集合在收尾时统一归一化，避免编码细节渗入解码循环
    private class FrameCollector {

        private val window = Fft.hannWindow(FFT_SIZE)
        private val pending = FloatArray(FFT_SIZE)
        private val re = FloatArray(FFT_SIZE)
        private val im = FloatArray(FFT_SIZE)
        private val rowPower = FloatArray(FREQ_ROWS)
        private val frames = ArrayList<FloatArray>()
        private var filled = 0

        // 解交织单声道并逐样本入窗，返回本次消费的采样帧数（非分析帧数）
        fun feedBuffer(
            buffer: ByteBuffer,
            offset: Int,
            size: Int,
            channels: Int,
            encoding: Int,
        ): Long {
            val bytesPerSample = PcmFormat.bytesPerSample(encoding)
            val frameCount = size / (channels * bytesPerSample).coerceAtLeast(1)
            if (frameCount <= 0) return 0L
            val view = buffer.duplicate()
            view.order(ByteOrder.LITTLE_ENDIAN)
            var cursor = offset
            for (i in 0 until frameCount) {
                var acc = 0f
                for (c in 0 until channels) {
                    acc += PcmFormat.read(view, cursor, encoding)
                    cursor += bytesPerSample
                }
                push(acc / channels)
            }
            return frameCount.toLong()
        }

        // 单样本入窗：填满一窗即分析一帧，随后前移半个窗保留重叠
        private fun push(sample: Float) {
            pending[filled++] = sample
            if (filled < FFT_SIZE) return
            analyseWindow()
            System.arraycopy(pending, HOP_SIZE, pending, 0, FFT_SIZE - HOP_SIZE)
            filled = FFT_SIZE - HOP_SIZE
        }

        // 当前窗加窗变换后归并半谱：频率等分到各行，行内取平均功率，
        // 使各行的桶数差异不转化为亮度偏置
        private fun analyseWindow() {
            for (i in 0 until FFT_SIZE) {
                re[i] = pending[i] * window[i]
                im[i] = 0f
            }
            Fft.transform(re, im)
            var bin = 0
            for (row in 0 until FREQ_ROWS) {
                // 行末桶号向上取整，保证半谱桶被完整覆盖、无遗漏
                val end = ((row + 1) * HALF_SPECTRUM + FREQ_ROWS - 1) / FREQ_ROWS
                var acc = 0f
                var count = 0
                while (bin < end && bin < HALF_SPECTRUM) {
                    acc += re[bin] * re[bin] + im[bin] * im[bin]
                    bin++
                    count++
                }
                rowPower[row] = if (count > 0) acc / count else 0f
            }
            store(rowPower.copyOf())
        }

        // 收帧：达到上限即把相邻帧两两合并，腾出一半容量。
        // 长曲目据此逐次减半时间分辨率，换得帧集合规模恒定，不随播放时长增长
        private fun store(frame: FloatArray) {
            if (frames.size >= MAX_FRAMES) {
                var write = 0
                var read = 0
                while (read + 1 < frames.size) {
                    val merged = frames[read]
                    val next = frames[read + 1]
                    for (row in 0 until FREQ_ROWS) merged[row] = (merged[row] + next[row]) * 0.5f
                    frames[write++] = merged
                    read += 2
                }
                // 帧数为奇数时末尾落单的一帧直接保留
                if (read < frames.size) frames[write++] = frames[read]
                while (frames.size > write) frames.removeAt(frames.size - 1)
            }
            frames.add(frame)
        }

        // 归一化并产出渲染用矩阵：以全局峰值功率为 0dB 参考换算 dB 后映射到 0..1，
        // 低幅细节不会被线性映射压成同一色。无有效帧时返回 null
        fun build(sampleRate: Int): Spectrogram? {
            if (frames.isEmpty()) return null
            var peak = 0f
            for (frame in frames) {
                for (power in frame) if (power > peak) peak = power
            }
            if (peak <= 0f) return null
            val columns = frames.size
            val values = FloatArray(columns * FREQ_ROWS)
            val floorDb = -SPECTROGRAM_DYNAMIC_RANGE_DB
            for (column in 0 until columns) {
                val frame = frames[column]
                val base = column * FREQ_ROWS
                for (row in 0 until FREQ_ROWS) {
                    val db = 10f * log10((frame[row] / peak).coerceAtLeast(1e-9f))
                    values[base + row] = ((db - floorDb) / -floorDb).coerceIn(0f, 1f)
                }
            }
            return Spectrogram(
                values = values,
                columns = columns,
                rows = FREQ_ROWS,
                sampleRate = sampleRate,
            )
        }
    }
}
