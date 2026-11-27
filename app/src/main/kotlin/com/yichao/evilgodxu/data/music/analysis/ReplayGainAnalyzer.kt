package com.yichao.evilgodxu.data.music.analysis

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.alac.AlacFrameDecoder
import com.yichao.evilgodxu.data.music.playback.alac.AlacSpecificConfig
import com.yichao.evilgodxu.log.CrashLogManager
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 整曲解码后交给 LoudnessMeter 统计。
 * 平台解码器为主，audio/alac 由自研解码接管。
 * 无有效音频时返回 null。
 */
internal object ReplayGainAnalyzer {

    private const val MIME_ALAC = "audio/alac"
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val INPUT_POLL_TIMEOUT_US = 0L

    suspend fun analyze(track: MusicTrack): ReplayGainMeasurement? = withContext(Dispatchers.IO) {
        // 只接受文件路径，URI 由调用方拦
        if (track.path.isBlank()) return@withContext null
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(track.path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: return@withContext null
            val mediaFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            val mime = mediaFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            val sampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
            val channels = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
            if (sampleRate <= 0 || channels <= 0) return@withContext null

            val sink = DecodingSink(sampleRate)
            decoder = openDecoder(mediaFormat, mime)
            val alacConfig = if (decoder == null) alacConfigOf(mediaFormat) else null
            when {
                decoder != null -> drainCodec(decoder, extractor, channels, sink)
                alacConfig != null -> drainAlac(extractor, alacConfig, sink)
                else -> return@withContext null
            }
            sink.result()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrashLogManager.logException("ReplayGainAnalyzer", "响度分析失败: 歌曲=${track.title}", e)
            null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    // alac 取不到解码器时自研解码，其余失败
    private fun openDecoder(format: MediaFormat, mime: String): MediaCodec? {
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
            runCatching { codec.release() }
            if (mime == MIME_ALAC) null else throw e
        }
    }

    private fun alacConfigOf(format: MediaFormat): AlacSpecificConfig? {
        val buffer = format.getByteBuffer("csd-0") ?: return null
        val cookie = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
        return AlacSpecificConfig.parse(cookie)
    }

    // 先喂满输入再取空输出，逐块交给响度计
    private suspend fun drainCodec(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        containerChannels: Int,
        sink: DecodingSink,
    ) {
        val info = MediaCodec.BufferInfo()
        var pcmEncoding = PcmFormat.ENCODING_16BIT
        var channels = containerChannels
        var inputEos = false
        var outputEos = false
        while (!outputEos) {
            coroutineContext.ensureActive()
            if (!inputEos) {
                while (true) {
                    val inIndex = decoder.dequeueInputBuffer(INPUT_POLL_TIMEOUT_US)
                    if (inIndex < 0) break
                    if (extractor.sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                        break
                    }
                    val inputBuffer = decoder.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(inputBuffer, 0)
                    decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
            while (true) {
                val outIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 规格以解码器为准，SBR 会抬高采样率
                    val outputFormat = decoder.outputFormat
                    pcmEncoding = PcmFormat.encodingOf(outputFormat)
                    outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
                        .takeIf { it > 0 }?.let { sink.onSampleRateChanged(it) }
                    outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
                        .takeIf { it > 0 }?.let { channels = it }
                    continue
                }
                if (outIndex < 0) break
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!isConfig && info.size > 0) {
                    decoder.getOutputBuffer(outIndex)?.let { buffer ->
                        sink.accept(buffer, info.offset, info.size, channels, pcmEncoding)
                    }
                }
                decoder.releaseOutputBuffer(outIndex, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputEos = true
                    break
                }
            }
        }
    }

    // 自研 ALAC 循环，采样率取 cookie
    private suspend fun drainAlac(
        extractor: MediaExtractor,
        config: AlacSpecificConfig,
        sink: DecodingSink,
    ) {
        val frameDecoder = AlacFrameDecoder(config)
        val pcm = AlacFramePcm(config)
        var frame = ByteArray(config.maxFrameBytes)
        sink.onSampleRateChanged(config.sampleRate)
        while (true) {
            coroutineContext.ensureActive()
            val sampleSize = extractor.sampleSize.toInt()
            if (sampleSize < 0) break
            if (sampleSize > frame.size) frame = ByteArray(sampleSize)
            if (extractor.readSampleData(ByteBuffer.wrap(frame), 0) < 0) break
            frameDecoder.decode(frame, sampleSize)
            val decoded = pcm.encode(frameDecoder)
            sink.accept(decoded, 0, decoded.limit(), config.channelCount, PcmFormat.ENCODING_FLOAT)
            extractor.advance()
        }
    }

    /**
     * 解码输出到响度计的投递口。
     * 首块输出时才按采样率建表，之后不再改。
     */
    private class DecodingSink(initialSampleRate: Int) {

        private var sampleRate = initialSampleRate
        private var meter: LoudnessMeter? = null
        private var samples = FloatArray(0)

        fun onSampleRateChanged(rate: Int) {
            if (meter == null) sampleRate = rate
        }

        fun accept(buffer: ByteBuffer, offset: Int, size: Int, channels: Int, encoding: Int) {
            val bytesPerSample = PcmFormat.bytesPerSample(encoding)
            val bytesPerFrame = bytesPerSample * channels
            if (bytesPerFrame <= 0) return
            val frames = size / bytesPerFrame
            if (frames <= 0) return
            if (samples.size < frames * channels) samples = FloatArray(frames * channels)
            var cursor = offset
            var index = 0
            for (frame in 0 until frames) {
                for (channel in 0 until channels) {
                    samples[index++] = PcmFormat.read(buffer, cursor, encoding)
                    cursor += bytesPerSample
                }
            }
            val active = meter ?: LoudnessMeter(sampleRate.coerceAtLeast(1), channels).also { meter = it }
            active.accept(samples, frames)
        }

        fun result(): ReplayGainMeasurement? = meter?.result()
    }
}
