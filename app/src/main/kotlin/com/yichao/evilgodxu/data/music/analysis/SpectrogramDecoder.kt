package com.yichao.evilgodxu.data.music.analysis

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.alac.AlacFrameDecoder
import com.yichao.evilgodxu.data.music.playback.alac.AlacSpecificConfig
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

// 全曲时频分析：整首解码为 PCM，逐窗做 FFT
//
// 平台解码器覆盖多数码流，audio/alac 由自研解码接管
// 两条解码路径都只交交错 PCM 给投递口
// 计算摊到多核，输出与串行逐位一致
internal object SpectrogramDecoder {

    // ALAC MIME，系统无组件时自研接管
    private const val MIME_ALAC = "audio/alac"

    // 2048 点 FFT，半窗跳步保 50% 重叠
    private const val FFT_SIZE = 2048
    private const val HOP_SIZE = FFT_SIZE / 2
    private const val HALF_SPECTRUM = FFT_SIZE / 2 + 1
    // 输出行数 1024，与半谱同阶，保留频率分辨率
    private const val FREQ_ROWS = 1024
    // 帧数上限 2048，达到即两两合并封顶内存
    private const val MAX_FRAMES = 2048
    private const val CODEC_TIMEOUT_US = 10_000L
    // 进度分 50 档回调，避免逐缓冲重组
    private const val PROGRESS_STEPS = 50
    private const val MICROS_PER_SECOND = 1_000_000L
    // 输入非阻塞轮询，喂满内部队列即取输出
    private const val INPUT_POLL_TIMEOUT_US = 0L
    // 解码可领先变换 32 个整窗，也是窗口缓冲池容量
    private const val WINDOW_POOL_SIZE = 32
    // 变换线程上限 8，更高核数收益递减
    private const val MAX_ANALYSIS_WORKERS = 8
    // 暂存块 128KB，段才有工作量抵过调度
    private const val STAGING_BYTES = 128 * 1024
    // 单段至少 4K 帧，否则切段不划算
    private const val MIN_FRAMES_PER_CONVERT_TASK = 4 * 1024

    // 变换线程取核数减一，单核退化为 1
    private val analysisWorkerCount: Int =
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, MAX_ANALYSIS_WORKERS)

    // 整首分析，进度按已解码时长；无有效帧返回 null
    suspend fun decode(
        track: MusicTrack,
        onProgress: (Float) -> Unit = {},
    ): Spectrogram? {
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
            if (trackIndex < 0) return null
            val mediaFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = mediaFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
            val channels = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
            if (sampleRate <= 0 || channels <= 0) return null

            // 平台解码器优先，与播放侧选取顺序一致
            val platformDecoder = openPlatformDecoder(mediaFormat, mime)
            decoder = platformDecoder
            // 系统解不了 alac 时自研接管，无 cookie 即失败
            val alacConfig = if (platformDecoder == null) alacConfigOf(mediaFormat) else null

            // 时长优先取容器，曲目时长兜底，仅作进度分母
            val containerDurationUs = mediaFormat.getLong(MediaFormat.KEY_DURATION, 0L)
            val durationUs =
                if (containerDurationUs > 0L) containerDurationUs else track.duration * 1000L

            coroutineScope {
                // 窗口池空即形成背压，解码不会无限领先堆积内存
                val freeWindows = Channel<FloatArray>(WINDOW_POOL_SIZE)
                repeat(WINDOW_POOL_SIZE) { freeWindows.trySend(FloatArray(FFT_SIZE)) }
                val windows = Channel<IndexedWindow>(WINDOW_POOL_SIZE)
                val collector = FrameCollector()
                val orderer = FrameOrderer(collector::store)
                val slicer = WindowSlicer(freeWindows, analysisWorkerCount)
                // 切片、进度与规格收在投递口
                val sink = PcmSink(
                    slicer = slicer,
                    windows = windows,
                    durationUs = durationUs,
                    initialSampleRate = sampleRate,
                    onProgress = onProgress,
                )

                val workers = List(analysisWorkerCount) {
                    launch { analyseWindows(windows, orderer, freeWindows) }
                }
                launch {
                    try {
                        when {
                            platformDecoder != null ->
                                drainCodec(platformDecoder, extractor, channels, sink)

                            alacConfig != null -> drainAlac(extractor, alacConfig, sink)

                            // 无解码器也不在自研范围，收集器交回 null
                            else -> Unit
                        }
                    } finally {
                        // 关闭窗口通道即通知变换线程收工
                        windows.close()
                    }
                }
                // 等变换线程退出即等解码收尾，异常会取消本作用域
                workers.joinAll()
                collector.build(sink.sampleRate, analysisWorkerCount)
            }
        } catch (e: CancellationException) {
            // 协程取消属正常流程，不记日志直接抛出
            throw e
        } catch (e: Exception) {
            CrashLogManager.logException("SpectrogramDecoder", "频谱图解码失败", e)
            null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    // 只有 alac 允许无解码器收场，其余即失败
    private fun openPlatformDecoder(format: MediaFormat, mime: String): MediaCodec? {
        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            if (mime == MIME_ALAC) return null else throw e
        }
        return try {
            codec.configure(format, null, null, 0)
            codec.start()
            codec
        } catch (e: Exception) {
            // 配置或启动失败，先释放再按缺失处理
            runCatching { codec.release() }
            if (mime == MIME_ALAC) null else throw e
        }
    }

    // csd-0 即 ALACSpecificConfig
    private fun alacConfigOf(format: MediaFormat): AlacSpecificConfig? {
        val buffer = format.getByteBuffer("csd-0") ?: return null
        val cookie = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
        return AlacSpecificConfig.parse(cookie)
    }

    // 投递口：交 PCM、推进度，采样率跟解码器
    private class PcmSink(
        private val slicer: WindowSlicer,
        private val windows: SendChannel<IndexedWindow>,
        private val durationUs: Long,
        initialSampleRate: Int,
        private val onProgress: (Float) -> Unit,
    ) {

        var sampleRate: Int = initialSampleRate

        private var elapsedUs = 0L
        private var progressStep = -1

        // 按时间而非采样数计比例，中途改采样率时比例不跳变
        suspend fun accept(
            buffer: ByteBuffer,
            offset: Int,
            size: Int,
            channels: Int,
            encoding: Int,
        ) {
            val frames = slicer.feedBuffer(buffer, offset, size, channels, encoding, windows)
            if (frames <= 0L) return
            elapsedUs += frames * MICROS_PER_SECOND / sampleRate
            val total = durationUs.coerceAtLeast(1L)
            val step = (elapsedUs * PROGRESS_STEPS / total).toInt()
            if (step == progressStep) return
            progressStep = step
            onProgress((elapsedUs.toDouble() / total).coerceIn(0.0, 1.0).toFloat())
        }

        // 收尾清仓，否则丢掉暂存块末尾不足一块的采样
        suspend fun flush() {
            slicer.flush(windows)
        }
    }

    // 主循环独占推进解码器并交出 PCM，不承担变换，两者并行
    private suspend fun drainCodec(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        containerChannels: Int,
        sink: PcmSink,
    ) {
        val info = MediaCodec.BufferInfo()
        var pcmEncoding = PcmFormat.ENCODING_16BIT
        var channels = containerChannels
        var inputEos = false
        var outputEos = false
        while (!outputEos) {
            // 保持可取消，离开页面即中止
            coroutineContext.ensureActive()
            // 喂入：能喂多少喂多少，喂不进才转去取输出
            if (!inputEos) {
                while (true) {
                    val inIndex = decoder.dequeueInputBuffer(INPUT_POLL_TIMEOUT_US)
                    if (inIndex < 0) break
                    val sampleSize = extractor.sampleSize
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputEos = true
                        break
                    }
                    val inputBuffer = decoder.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(inputBuffer, 0)
                    decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
            // 连续取到取空，背压挂起时缓冲仍归本线程
            while (true) {
                val outIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 规格以解码器为准，24bit 与 SBR 会改声明值
                    val outputFormat = decoder.outputFormat
                    pcmEncoding = PcmFormat.encodingOf(outputFormat)
                    outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
                        .takeIf { it > 0 }?.let { sink.sampleRate = it }
                    outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
                        .takeIf { it > 0 }?.let { channels = it }
                    continue
                }
                // 取空即本轮结束，回到外层补喂输入
                if (outIndex < 0) break
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val outputBuffer =
                    if (isConfig || info.size <= 0) null
                    else decoder.getOutputBuffer(outIndex)
                if (outputBuffer != null) {
                    sink.accept(outputBuffer, info.offset, info.size, channels, pcmEncoding)
                }
                decoder.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputEos = true
                    break
                }
            }
        }
        sink.flush()
    }

    // 自研 ALAC 循环：每帧自带全部参数，逐帧解码无需跨帧状态
    private suspend fun drainAlac(
        extractor: MediaExtractor,
        config: AlacSpecificConfig,
        sink: PcmSink,
    ) {
        val frameDecoder = AlacFrameDecoder(config)
        val pcm = AlacFramePcm(config)
        // 按 cookie 上限预置帧缓冲，超出即扩容
        var frame = ByteArray(config.maxFrameBytes)
        // 采样率取 cookie，ALAC 是线性 PCM 无 SBR
        sink.sampleRate = config.sampleRate
        while (true) {
            coroutineContext.ensureActive()
            // -1 表示样本表已读完
            val sampleSize = extractor.sampleSize.toInt()
            if (sampleSize < 0) break
            if (sampleSize > frame.size) frame = ByteArray(sampleSize)
            if (extractor.readSampleData(ByteBuffer.wrap(frame), 0) < 0) break
            frameDecoder.decode(frame, sampleSize)
            val decoded = pcm.encode(frameDecoder)
            sink.accept(
                buffer = decoded,
                offset = 0,
                size = decoded.limit(),
                channels = config.channelCount,
                encoding = PcmFormat.ENCODING_FLOAT,
            )
            extractor.advance()
        }
        sink.flush()
    }

    // 逐窗独立变换，按序号交重排器后归还缓冲；缓冲各自持有
    private suspend fun analyseWindows(
        windows: ReceiveChannel<IndexedWindow>,
        orderer: FrameOrderer,
        freeWindows: SendChannel<FloatArray>,
    ) {
        val worker = AnalysisWorker()
        for (window in windows) {
            orderer.submit(window.index, worker.analyse(window.samples))
            freeWindows.send(window.samples)
        }
    }

    // 待变换整窗与序号，模块内可见供单测
    internal class IndexedWindow(val index: Long, val samples: FloatArray)

    // 逐窗切片：按字节攒暂存块，分段并行转单声道
    // 攒粗块是为抵过调度开销，整段拷贝更快
    // 入窗仍单线程按序，模块内可见供单测
    internal class WindowSlicer(
        private val freeWindows: ReceiveChannel<FloatArray>,
        // 转换段数不超过线程数，否则互相抢占
        private val maxConvertParts: Int,
    ) {

        private val pending = FloatArray(FFT_SIZE)
        private var filled = 0
        private var index = 0L
        private val staging = ByteArray(STAGING_BYTES)
        private var stagedBytes = 0
        private var stagedChannels = 0
        private var stagedEncoding = PcmFormat.ENCODING_16BIT

        // 消费解码缓冲并返回帧数，换格式即清仓
        suspend fun feedBuffer(
            buffer: ByteBuffer,
            offset: Int,
            size: Int,
            channels: Int,
            encoding: Int,
            windows: SendChannel<IndexedWindow>,
        ): Long {
            val bytesPerSample = PcmFormat.bytesPerSample(encoding)
            val frameBytes = (channels * bytesPerSample).coerceAtLeast(1)
            val frameCount = size / frameBytes
            if (frameCount <= 0) return 0L
            if (stagedBytes > 0 && (stagedChannels != channels || stagedEncoding != encoding)) {
                flush(windows)
            }
            stagedChannels = channels
            stagedEncoding = encoding
            val view = buffer.duplicate()
            view.position(offset)
            view.limit(offset + size)
            val capacityFrames = staging.size / frameBytes
            var cursor = offset
            var remaining = frameCount
            while (remaining > 0) {
                val take = minOf(remaining, capacityFrames - stagedBytes / frameBytes)
                val byteCount = take * frameBytes
                view.position(cursor)
                view.get(staging, stagedBytes, byteCount)
                cursor += byteCount
                stagedBytes += byteCount
                remaining -= take
                if (stagedBytes == capacityFrames * frameBytes) flush(windows)
            }
            return frameCount.toLong()
        }

        // 清仓：段间只读不同区间可并行，入窗按段序
        suspend fun flush(windows: SendChannel<IndexedWindow>) {
            val channels = stagedChannels
            val encoding = stagedEncoding
            val frameBytes = (channels * PcmFormat.bytesPerSample(encoding)).coerceAtLeast(1)
            val frames = stagedBytes / frameBytes
            if (frames <= 0) {
                stagedBytes = 0
                return
            }
            val view = ByteBuffer.wrap(staging).order(ByteOrder.LITTLE_ENDIAN)
            val parts = (frames / MIN_FRAMES_PER_CONVERT_TASK).coerceIn(1, maxConvertParts)
            val converted = if (parts <= 1) {
                listOf(convertRange(view, 0, frames, channels, encoding))
            } else {
                coroutineScope {
                    (0 until parts).map { part ->
                        async {
                            convertRange(
                                view = view,
                                from = frames * part / parts,
                                to = frames * (part + 1) / parts,
                                channels = channels,
                                encoding = encoding,
                            )
                        }
                    }.awaitAll()
                }
            }
            for (part in converted) {
                for (sample in part) {
                    pending[filled++] = sample
                    if (filled == FFT_SIZE) emitWindow(windows)
                }
            }
            stagedBytes = 0
        }

        // 整窗就绪：先拷贝后滑动，与逐样本直入同一次序
        private suspend fun emitWindow(windows: SendChannel<IndexedWindow>) {
            val window = freeWindows.receive()
            System.arraycopy(pending, 0, window, 0, FFT_SIZE)
            windows.send(IndexedWindow(index++, window))
            System.arraycopy(pending, HOP_SIZE, pending, 0, FFT_SIZE - HOP_SIZE)
            filled = FFT_SIZE - HOP_SIZE
        }

        // 解交织成单声道浮点，绝对下标可并行
        private fun convertRange(
            view: ByteBuffer,
            from: Int,
            to: Int,
            channels: Int,
            encoding: Int,
        ): FloatArray {
            val bytesPerSample = PcmFormat.bytesPerSample(encoding)
            val out = FloatArray(to - from)
            var cursor = from * channels * bytesPerSample
            for (i in out.indices) {
                var acc = 0f
                for (c in 0 until channels) {
                    acc += PcmFormat.read(view, cursor, encoding)
                    cursor += bytesPerSample
                }
                out[i] = acc / channels
            }
            return out
        }
    }

    // 变换单元：一窗样本变各频带平均功率
    private class AnalysisWorker {

        private val window = Fft.hannWindow(FFT_SIZE)
        private val re = FloatArray(FFT_SIZE)
        private val im = FloatArray(FFT_SIZE)

        // 加窗后归并半谱，行内取平均功率
        fun analyse(samples: FloatArray): FloatArray {
            for (i in 0 until FFT_SIZE) {
                re[i] = samples[i] * window[i]
                im[i] = 0f
            }
            Fft.transform(re, im)
            val rowPower = FloatArray(FREQ_ROWS)
            var bin = 0
            for (row in 0 until FREQ_ROWS) {
                // 行末桶号向上取整，保证半谱桶完整覆盖、无遗漏
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
            return rowPower
        }
    }

    // 逐帧累积与归一化，只由重排器单点调用
    private class FrameCollector {

        private val frames = ArrayList<FloatArray>()

        // 收帧：达上限即两两合并，规模恒定
        fun store(frame: FloatArray) {
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
                // 奇数时末尾落单一帧无处合并，原样保留
                if (read < frames.size) frames[write++] = frames[read]
                while (frames.size > write) frames.removeAt(frames.size - 1)
            }
            frames.add(frame)
        }

        // 全局峰值为 0dB 映射到 0..1，无帧返回 null
        suspend fun build(sampleRate: Int, parts: Int): Spectrogram? {
            if (frames.isEmpty()) return null
            var peak = 0f
            for (frame in frames) {
                for (power in frame) if (power > peak) peak = power
            }
            if (peak <= 0f) return null
            val columns = frames.size
            val values = FloatArray(columns * FREQ_ROWS)
            val floorDb = -SPECTROGRAM_DYNAMIC_RANGE_DB
            coroutineScope {
                val split = parts.coerceIn(1, columns)
                (0 until split).map { part ->
                    async {
                        val from = columns * part / split
                        val to = columns * (part + 1) / split
                        for (column in from until to) {
                            val frame = frames[column]
                            val base = column * FREQ_ROWS
                            for (row in 0 until FREQ_ROWS) {
                                val db = 10f * log10((frame[row] / peak).coerceAtLeast(1e-9f))
                                values[base + row] = ((db - floorDb) / -floorDb).coerceIn(0f, 1f)
                            }
                        }
                    }
                }.awaitAll()
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
