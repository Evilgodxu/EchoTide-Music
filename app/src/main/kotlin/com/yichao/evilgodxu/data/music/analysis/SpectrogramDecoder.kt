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

// 全曲时频分析：把整首音频解码为 PCM，逐窗做短时傅里叶变换，
// 产出可直接渲染成频谱图的强度矩阵。
//
// 解码来源有两处，之后的步骤完全共用：平台解码器（MediaCodec）覆盖 mp3/flac/aac 等多数码流；
// AOSP 不含 audio/alac 的解码组件，该码流改由自研帧解码接管（与播放侧同一份实现）。
// 两条来源只负责把交错 PCM 交给同一个投递口，分帧口径与进度算法因此不分叉。
//
// 计算摊到多核的四处：解码线程独占推进解码并按块搬运 PCM；
// 切片阶段把暂存的 PCM 分段并行转成单声道整窗；变换线程组并行做加窗变换与半谱归并；
// 收尾归一化按列并行。
// 跨越并行边界的顺序仍由单点按时间序执行（帧序经重排器归位、列序本就互不依赖），
// 故输出与逐帧串行处理逐位一致——并行只换取吞吐，不改变分析口径。
internal object SpectrogramDecoder {

    // ALAC 的码流 MIME：AOSP 没有它的解码组件，缺失时由自研帧解码接管
    private const val MIME_ALAC = "audio/alac"

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
    private const val MICROS_PER_SECOND = 1_000_000L
    // 输入缓冲只做非阻塞轮询：喂满解码器内部队列即转去取输出，节流交给输出端的等待
    private const val INPUT_POLL_TIMEOUT_US = 0L
    // 解码线程可领先变换线程的整窗数，同时也是空闲窗口缓冲池的容量。
    // 深度取「足以填平解码与变换的耗时差、而缓冲总量仍在数百 KB」的量级
    private const val WINDOW_POOL_SIZE = 32
    // 变换线程数上限：核数很高时新增线程的收益被内存带宽与调度开销吃掉
    private const val MAX_ANALYSIS_WORKERS = 8
    // 转换暂存块容量（字节）：转换粒度必须够粗，切出的段才有足够工作量抵过线程调度开销。
    // 单个解码输出缓冲往往只有几千帧，按缓冲直接并行会被调度开销吃光，故先按字节累积成块
    private const val STAGING_BYTES = 128 * 1024
    // 单段转换至少要覆盖的采样帧数：不足此数就不再切段，
    // 否则为几百微秒的活儿付一次线程调度并不划算
    private const val MIN_FRAMES_PER_CONVERT_TASK = 4 * 1024

    // 变换线程数：解码线程独占一核，其余核全部投入变换计算；单核设备退化为 1
    private val analysisWorkerCount: Int =
        (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, MAX_ANALYSIS_WORKERS)

    // 解码并分析整首音频；进度取已解码时长相对容器时长的比例，协程取消时即时释放解码器。
    // 无法解出任何有效帧时返回 null，由调用方按失败处理
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

            // 平台解码器优先：厂商若带 audio/alac 解码器也在此命中，与播放侧的选取顺序一致，
            // 频谱与播放因此对同一文件用同一只解码器
            val platformDecoder = openPlatformDecoder(mediaFormat, mime)
            decoder = platformDecoder
            // 系统解不了 audio/alac 时由自研帧解码接管；cookie 取不到就无从接管，按失败处理
            val alacConfig = if (platformDecoder == null) alacConfigOf(mediaFormat) else null

            // 预期时长取容器时长优先、曲目时长兜底：仅作进度分母，
            // 分析帧数由跳步与帧数上限决定，不依赖时长是否准确
            val containerDurationUs = mediaFormat.getLong(MediaFormat.KEY_DURATION, 0L)
            val durationUs =
                if (containerDurationUs > 0L) containerDurationUs else track.duration * 1000L

            coroutineScope {
                // 窗口缓冲池：池空即说明解码已跑到变换前面，取缓冲处自然形成背压，
                // 使解码不会在长曲目上无限领先并堆积内存
                val freeWindows = Channel<FloatArray>(WINDOW_POOL_SIZE)
                repeat(WINDOW_POOL_SIZE) { freeWindows.trySend(FloatArray(FFT_SIZE)) }
                val windows = Channel<IndexedWindow>(WINDOW_POOL_SIZE)
                val collector = FrameCollector()
                val orderer = FrameOrderer(collector::store)
                val slicer = WindowSlicer(freeWindows, analysisWorkerCount)
                // 切片、进度与输出规格收在投递口一处：两条解码来源只负责把 PCM 交来，
                // 分帧口径与进度算法因此不需要各写一份
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

                            // 既无平台解码器、也不在自研实现的覆盖范围内：不产出任何 PCM，
                            // 收集器据空帧集合交回 null
                            else -> Unit
                        }
                    } finally {
                        // 关闭窗口通道即通知变换线程收工
                        windows.close()
                    }
                }
                // 变换线程退出的前提是解码已结束且窗口通道已取空，故此处同时等到解码收尾；
                // 解码中抛出异常会取消本作用域，不会让收集器在残缺数据上继续收尾
                workers.joinAll()
                collector.build(sink.sampleRate, analysisWorkerCount)
            }
        } catch (e: CancellationException) {
            // 协程取消（如退出页面）属正常流程：不记日志，重新抛出
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

    // 取平台解码器。只有 audio/alac 允许以「本机无此解码器」收场，因为自研帧解码能接管它；
    // 其余码流取不到解码器就是分析失败，异常照旧抛出交由外层记录，不在此处静默吞掉
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
            // 配置或启动失败：实例已取到但不可用，先释放再按缺失处理
            runCatching { codec.release() }
            if (mime == MIME_ALAC) null else throw e
        }
    }

    // ALAC 的 magic cookie：ISO-BMFF 的 alac 采样描述由 MediaExtractor 放在 csd-0，
    // 内容即 ALACSpecificConfig 本身。解析不出参数时返回 null，不以默认值猜一条流
    private fun alacConfigOf(format: MediaFormat): AlacSpecificConfig? {
        val buffer = format.getByteBuffer("csd-0") ?: return null
        val cookie = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
        return AlacSpecificConfig.parse(cookie)
    }

    // 解码产出的投递口：把交错 PCM 交给切片器，并按已解码时长推进进度。
    // 采样率跟着解码器上报的输出格式走，而不是容器声明值——HE-AAC 的 SBR 让输出采样率翻倍，
    // 频率轴与进度都必须按实际输出计算
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

        // 消费一块 PCM 并推进进度。进度按时间而非采样数计比例，
        // 解码器中途改采样率时比例才不会跳变
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

        // 解码收尾：暂存块里可能还剩不足一块的采样，不清仓就会丢掉末尾若干帧
        suspend fun flush() {
            slicer.flush(windows)
        }
    }

    // 平台解码器主循环：独占推进解码器，把输出 PCM 交给投递口，
    // 本线程不承担变换计算，两者因此得以并行推进
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
            // 分析进行中保持可取消：离开页面即中止，解码器由外层 finally 释放
            coroutineContext.ensureActive()
            // 喂入：本轮能喂多少喂多少，把解码器内部队列保持填满。
            // 逐块「喂一块取一块」会让解码器在两次调用之间空转，故只在喂不进时才转去取输出
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
            // 取出：连续取到取空为止；每块 PCM 直接交给投递口。
            // 投递可能因背压挂起，此时输出缓冲仍归本线程所有，解码器只是暂停产出，不会丢数据
            while (true) {
                val outIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 输出规格以解码器上报为准：24bit FLAC 在部分设备按 24bit/32bit 输出，
                    // HE-AAC 的 SBR 又让输出采样率高于容器声明值；缺失或非法值保留原值
                    val outputFormat = decoder.outputFormat
                    pcmEncoding = PcmFormat.encodingOf(outputFormat)
                    outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
                        .takeIf { it > 0 }?.let { sink.sampleRate = it }
                    outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
                        .takeIf { it > 0 }?.let { channels = it }
                    continue
                }
                // 取空（含旧版缓冲区变更通知）：本轮输出结束，回到外层补喂输入
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

    // 自研 ALAC 主循环：容器只交出逐帧字节与 magic cookie，解帧交给与播放侧共用的帧解码。
    // ALAC 每帧自带全部参数（预测系数与自适应 Golomb 参数都在帧内），逐帧解码无需跨帧状态，
    // 故这里与平台解码器一样按样本表顺序读一帧、解一帧、交一帧
    private suspend fun drainAlac(
        extractor: MediaExtractor,
        config: AlacSpecificConfig,
        sink: PcmSink,
    ) {
        val frameDecoder = AlacFrameDecoder(config)
        val pcm = AlacFramePcm(config)
        // 单帧字节按 cookie 声明的上限预置；样本超出该上限时随即扩容，不为个别大帧长期占用
        var frame = ByteArray(config.maxFrameBytes)
        // 采样率取 cookie：ALAC 是线性 PCM 编码，容器声明的采样率就是输出采样率
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

    // 变换线程循环：逐窗独立变换，行数据按序号交给重排器后立即归还窗口缓冲。
    // 每个线程持有自己的变换缓冲，除只读的窗函数外不共享可写状态
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

    // 待变换的整窗样本与其在时间轴上的序号：序号供重排器恢复帧序。
    // 与切片器同为模块内可见，供单元测试直接驱动分帧过程
    internal class IndexedWindow(val index: Long, val samples: FloatArray)

    // 逐窗切片：把解码输出按字节累积到暂存块，整块分段并行转成单声道浮点后按序入窗。
    // 不逐样本直入的原因有两点。其一是转换是切片里唯一的重活，只有先攒成足够大的块，
    // 切出的段才有足够工作量抵过线程调度开销；其二是整段拷贝比逐样本读取便宜一个量级，
    // 解码线程据此能立刻归还输出缓冲，解交织与声道平均推迟到转换段里一次做完。
    // 入窗（取缓冲、拷贝、滑动）仍由本协程单线程按序推进，故分帧次序与逐样本直入完全一致。
    // 模块内可见：分帧过程没有平台依赖，单元测试据此直接驱动并比对参考实现
    internal class WindowSlicer(
        private val freeWindows: ReceiveChannel<FloatArray>,
        // 转换段数上限：转换与变换共用同一批核，段数超过线程数只会互相抢占
        private val maxConvertParts: Int,
    ) {

        private val pending = FloatArray(FFT_SIZE)
        private var filled = 0
        private var index = 0L
        private val staging = ByteArray(STAGING_BYTES)
        private var stagedBytes = 0
        private var stagedChannels = 0
        private var stagedEncoding = PcmFormat.ENCODING_16BIT

        // 消费解码输出缓冲：返回本次消费的采样帧数（非分析帧数）。
        // 采样按字节搬进暂存块，满块或换格式即清仓；同一批字节始终按同一套参数解释，
        // 故格式变化必须在此处就被截断，不能让前后两段参数不同的字节落进同一块
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

        // 清仓：把暂存的采样转成单声道浮点并入窗。段与段只读暂存块的不同区间，
        // 故转换可并行；入窗仍按段序在本协程内推进，分帧次序与逐样本直入完全一致
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

        // 整窗就绪：拷贝先于移位，取窗在前、滑动在后，与逐样本直入同一次序
        private suspend fun emitWindow(windows: SendChannel<IndexedWindow>) {
            val window = freeWindows.receive()
            System.arraycopy(pending, 0, window, 0, FFT_SIZE)
            windows.send(IndexedWindow(index++, window))
            System.arraycopy(pending, HOP_SIZE, pending, 0, FFT_SIZE - HOP_SIZE)
            filled = FFT_SIZE - HOP_SIZE
        }

        // 把暂存块中 [from, to) 的采样帧解交织成单声道浮点。
        // 全程只用绝对下标读取，故多段可同时读同一暂存块而不互相干扰
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

    // 变换工作单元：持有本线程独占的实虚缓冲，把一窗样本变成各频带的平均功率
    private class AnalysisWorker {

        private val window = Fft.hannWindow(FFT_SIZE)
        private val re = FloatArray(FFT_SIZE)
        private val im = FloatArray(FFT_SIZE)

        // 当前窗加窗变换后归并半谱：频率等分到各行，行内取平均功率，
        // 使各行的桶数差异不转化为亮度偏置
        fun analyse(samples: FloatArray): FloatArray {
            for (i in 0 until FFT_SIZE) {
                re[i] = samples[i] * window[i]
                im[i] = 0f
            }
            Fft.transform(re, im)
            val rowPower = FloatArray(FREQ_ROWS)
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
            return rowPower
        }
    }

    // 逐帧行集合的累积与收尾归一化：合并与列序都必须在时间序上发生，
    // 故只由重排器按序单点调用，并行只发生在它之前
    private class FrameCollector {

        private val frames = ArrayList<FloatArray>()

        // 收帧：达到上限即把相邻帧两两合并，腾出一半容量。
        // 长曲目据此逐次减半时间分辨率，换得帧集合规模恒定，不随播放时长增长
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
                // 帧数为奇数时末尾落单的一帧无处合并，原样保留
                if (read < frames.size) frames[write++] = frames[read]
                while (frames.size > write) frames.removeAt(frames.size - 1)
            }
            frames.add(frame)
        }

        // 归一化并产出渲染用矩阵：以全局峰值功率为 0dB 参考换算 dB 后映射到 0..1，
        // 低幅细节不会被线性映射压成同一色。无有效帧时返回 null。
        // 列与列互不依赖、各段只写 values 的不相交区间，故按列分段并行无需同步；
        // 逐值取对数是分析收尾前最后一段成规模的计算，也是这里唯一值得摊开的部分
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
