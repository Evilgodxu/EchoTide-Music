package com.yichao.evilgodxu.data.music.analysis

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

// 稀疏窗口解码驱动：对候选音频轨解码 3 段探测窗（每窗 4 秒），PCM 交给 Accumulator 累计。
// 曲库分析据此做增量判定；全曲判定由 SpectrogramDecoder 复用同一 Accumulator 在同一份解码
// 上产出，两条路径的判据输入口径唯一，解码循环也各只有一处。
// 并发由调用方决定：批量分析在限并发调度器上推进（并发上限见 LibraryAnalysisRunner），
// 单曲入口仍逐曲执行；协程取消即时释放解码器。
internal object SpectralDecoder {

    // 判定网格：4096 点 FFT（44.1k 下约 10.8Hz/桶）。
    // 砖墙/升频/谐波梳的全部阈值按该分辨率标定，两条解码路径必须沿用同一尺寸
    const val FFT_SIZE = 4096

    // Welch 帧的滑动步长：半窗即 50% 重叠，帧间不留缝
    private const val HOP_SIZE = FFT_SIZE / 2
    private const val PROBE_DURATION_US = 4_000_000L
    private val PROBE_POSITIONS = floatArrayOf(0.15f, 0.45f, 0.75f)
    private const val CODEC_TIMEOUT_US = 10_000L

    // 升频死区探带：44.1k 源奈奎斯特（22050Hz）上方的窄带区间，逐 FFT 块记录带内总功率，
    // 供音质异常判定死区动态——真实母带内容随乐句起伏，重采样死区为常量；
    // 采样率不足以容纳探带（如 44.1k 原生文件）时不启用
    private const val PROBE_LO_HZ = 22150f
    private const val PROBE_HI_HZ = 22850f

    // 解码摘要：平均功率谱与判据所需全部参数，两识别器在其上提取各自特征
    data class DecodeSummary(
        val powerSum: FloatArray,
        val blocks: Int,
        val sampleRate: Int,
        val channels: Int,
        // 高通后左右声道长时间相关性；样本不足或声道非立体声时为 0（无证据）
        val stereoCorrelation: Float,
        val stereoCorrSamples: Long,
        // 升频死区探带逐帧功率：44.1k 源奈奎斯特上方窄带；采样率不足以容纳时不适用（空数组）
        val probe22050: FloatArray = FloatArray(0),
    )

    // 解码候选音频轨并累计平均功率谱与立体声相关性。
    // expectedMime 非空时仅解码该 mime（音质异常限定 FLAC）；为空时取首个可解码音频轨（AI 识别全格式）。
    // fallbackSampleRate/FallbackChannels 供提取器未给全参数时回退容器头解析值
    suspend fun decodeTrack(
        track: MusicTrack,
        expectedMime: String?,
        fallbackSampleRate: Int = 0,
        fallbackChannels: Int = 0,
    ): DecodeSummary? {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(track.path)
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                val matched = if (expectedMime != null) mime == expectedMime else mime.startsWith("audio/")
                if (matched) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0) return null
            val mediaFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = mediaFormat.getString(MediaFormat.KEY_MIME) ?: return null
            // 提取器未给出采样率/声道时回退容器头解析值
            val sr = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, fallbackSampleRate)
            val ch = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, fallbackChannels)
            if (sr <= 0 || ch <= 0) return null

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(mediaFormat, null, null, 0)
            decoder.start()

            val accumulator = Accumulator(sr, ch)
            val durationUs = track.duration * 1000L
            for (pos in PROBE_POSITIONS) {
                decoder.flush()
                extractor.seekTo((durationUs * pos).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                // 本窗起点与上一窗不连续：丢弃残留样本，避免拼出跨越接缝的伪帧
                accumulator.reset()
                decodeProbe(decoder, extractor, sr, accumulator)
            }
            // 三窗均未解出有效块时返回 null，由调用方按无法判定处理
            accumulator.summary()
        } catch (e: CancellationException) {
            // 协程取消（如关闭对话框）属正常流程：不记日志，重新抛出
            throw e
        } catch (e: Exception) {
            CrashLogManager.logException("SpectralDecoder", "频谱解码失败", e)
            null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    // 泵送一个探测窗的解码至本窗目标时长，PCM 全部交给累加器
    private suspend fun decodeProbe(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        sampleRate: Int,
        accumulator: Accumulator,
    ) {
        val info = MediaCodec.BufferInfo()
        var pcmEncoding = PcmFormat.ENCODING_16BIT
        val targetFrames = sampleRate * PROBE_DURATION_US / 1_000_000
        var decodedFrames = 0
        var extractorEos = false
        var outputEos = false
        while (!outputEos && decodedFrames < targetFrames) {
            // 校验进行中保持可取消：关闭对话框即中止，解码器由外层 finally 释放
            coroutineContext.ensureActive()
            // 喂入输入：连读提取器样本直至本窗目标时长
            if (!extractorEos) {
                val inIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inIndex >= 0) {
                    val sampleSize = extractor.sampleSize
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        extractorEos = true
                    } else {
                        val inputBuffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inputBuffer, 0)
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            // 取出输出：累加本窗 PCM 的功率谱与立体声相关性
            when (val outIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 24bit FLAC 在部分设备按 24bit/32bit 输出，字节宽必须跟随编码而非固定 16 位
                    pcmEncoding = PcmFormat.encodingOf(decoder.outputFormat)
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outIndex)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (outputBuffer == null || isConfig || info.size <= 0) {
                        decoder.releaseOutputBuffer(outIndex, false)
                    } else {
                        decodedFrames += accumulator.feed(
                            outputBuffer, info.offset, info.size, pcmEncoding,
                        )
                        decoder.releaseOutputBuffer(outIndex, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                }
            }
        }
    }

    // 判决摘要累加器：PCM 缓冲 → 平均功率谱 + 立体声相关性 + 升频死区探带。
    // 稀疏窗口（本对象，3 窗）与全曲（SpectrogramDecoder，整曲逐帧）两条路径共用同一实现，
    // 使「分段快速采样」与「完整分析」只在取样范围上不同，判据输入的结构与刻度完全一致。
    // 分帧跨缓冲连续：解码器单次输出常不足一窗（有损格式约千帧），残留样本留待后续缓冲续接，
    // 否则每缓冲都凑不满一窗、累计不出任何块，凡输入皆不可判定。
    // 单实例只由一条解码循环喂入，无需内部同步
    internal class Accumulator(
        private val sampleRate: Int,
        private val channels: Int,
    ) {

        // Hann 窗：逐帧重复求余弦是长音频分析的主要冗余开销，按长度缓存一次
        private val hannWindow = Fft.hannWindow(FFT_SIZE)

        private val powerSum = FloatArray(FFT_SIZE / 2 + 1)
        private val scratchRe = FloatArray(FFT_SIZE)
        private val scratchIm = FloatArray(FFT_SIZE)
        // 滑动窗：未满一窗的样本留在窗内，与后续缓冲拼接使用
        private val window = FloatArray(FFT_SIZE)
        private var filled = 0
        private val stereo = StereoAccumulator()
        private val probe = ProbeAccumulator(PROBE_LO_HZ, PROBE_HI_HZ, sampleRate)
        private var blocks = 0

        // 丢弃未成帧的残留样本：探测窗经 seek 后样本不连续，残留不得与下一窗拼成伪帧
        fun reset() {
            filled = 0
        }

        // 喂入一个输出缓冲：解交织单声道并逐样本入窗，满一窗即做 Welch 帧累加，
        // 同时逐样本喂入立体声相关性；返回本次消费的采样帧数。
        // 按编码区分样本解释方式：24bit 打包为 3 字节有符号小端，32bit 为有符号整型（非浮点）
        fun feed(buffer: ByteBuffer, offset: Int, size: Int, pcmEncoding: Int): Int {
            val bytesPerSample = PcmFormat.bytesPerSample(pcmEncoding)
            val frames = size / (channels * bytesPerSample).coerceAtLeast(1)
            if (frames <= 0) return 0
            val view = buffer.duplicate()
            view.order(ByteOrder.LITTLE_ENDIAN)
            var cursor = offset
            for (i in 0 until frames) {
                var acc = 0f
                var left = 0f
                var right = 0f
                for (c in 0 until channels) {
                    val sample = PcmFormat.read(view, cursor, pcmEncoding)
                    cursor += bytesPerSample
                    if (c == 0) left = sample else if (c == 1) right = sample
                    acc += sample
                }
                window[filled++] = acc / channels
                if (channels == 2) stereo.feed(left, right)
                if (filled < FFT_SIZE) continue
                // 50% 重叠滑动窗：最大限度利用解码输入，稳定噪声底估计
                accumulateWindow()
                System.arraycopy(window, HOP_SIZE, window, 0, FFT_SIZE - HOP_SIZE)
                filled = FFT_SIZE - HOP_SIZE
            }
            return frames
        }

        // 当前窗的功率谱：加窗变换后累加平均谱；探带功率在同一块频谱上顺带累计，零额外 FFT
        private fun accumulateWindow() {
            for (i in 0 until FFT_SIZE) {
                scratchRe[i] = window[i] * hannWindow[i]
                scratchIm[i] = 0f
            }
            Fft.transform(scratchRe, scratchIm)
            Fft.accumulatePower(scratchRe, scratchIm, powerSum)
            probe.addBlock(scratchRe, scratchIm)
            blocks++
        }

        // 收摘要：未解出任何 FFT 块时返回 null（无法判定），由调用方按各自语义处理
        fun summary(): DecodeSummary? {
            if (blocks <= 0) return null
            return DecodeSummary(
                powerSum = powerSum,
                blocks = blocks,
                sampleRate = sampleRate,
                channels = channels,
                stereoCorrelation = if (channels == 2) stereo.correlation() else 0f,
                stereoCorrSamples = stereo.samples,
                probe22050 = probe.snapshot(),
            )
        }

        // 升频死区探带累加器：逐 FFT 块记录指定窄带（源奈奎斯特上方）的总功率。
        // 采样率不足以容纳探带（bin 超出奈奎斯特）时不启用；snapshot 返回逐帧功率数组
        private class ProbeAccumulator(
            loHz: Float,
            hiHz: Float,
            sampleRate: Int,
        ) {
            private val binHz = sampleRate.toFloat() / FFT_SIZE
            private val loBin = (loHz / binHz).toInt().coerceAtLeast(0)
            private val hiBin = (hiHz / binHz).toInt()
            private val n = FFT_SIZE / 2
            private val enabled = loBin <= hiBin && hiBin <= n
            private val powers = ArrayList<Float>(512)

            fun addBlock(re: FloatArray, im: FloatArray) {
                if (!enabled) return
                var acc = 0f
                for (i in loBin..hiBin) acc += re[i] * re[i] + im[i] * im[i]
                powers.add(acc)
            }

            fun snapshot(): FloatArray {
                if (!enabled || powers.isEmpty()) return FloatArray(0)
                return FloatArray(powers.size) { powers[it] }
            }
        }

        // 立体声相关性累计：约 250Hz 高通（直流阻塞）后逐采样累积一/二阶矩，积分时长覆盖全部喂入采样，
        // 消除低频单声道主导，聚焦中高频的去相关程度
        private class StereoAccumulator {
            // 高通滤波系数：约 250Hz 截止，剔除低频单声道主导的干扰
            private val alpha = 0.97f
            private var prevX = 0f
            private var prevY = 0f
            private var hpX = 0f
            private var hpY = 0f
            private var sumX = 0.0
            private var sumY = 0.0
            private var sumXX = 0.0
            private var sumYY = 0.0
            private var sumXY = 0.0
            var samples = 0L
                private set

            fun feed(x: Float, y: Float) {
                hpX = alpha * (hpX + x - prevX)
                prevX = x
                hpY = alpha * (hpY + y - prevY)
                prevY = y
                sumX += hpX
                sumY += hpY
                sumXX += hpX * hpX
                sumYY += hpY * hpY
                sumXY += hpX * hpY
                samples++
            }

            fun correlation(): Float {
                // 时长过短统计不可靠时返回 0（无证据），由调用方跳过该征象
                if (samples < 4096) return 0f
                val n = samples.toDouble()
                val dx = sumXX - sumX * sumX / n
                val dy = sumYY - sumY * sumY / n
                val denom = sqrt(dx * dy)
                if (denom <= 0.0) return 0f
                return ((sumXY - sumX * sumY / n) / denom).toFloat()
            }
        }
    }
}
