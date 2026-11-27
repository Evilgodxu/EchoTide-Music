package com.yichao.evilgodxu.data.music.analysis

import com.yichao.evilgodxu.data.music.playback.alac.AlacFrameDecoder
import com.yichao.evilgodxu.data.music.playback.alac.AlacSpecificConfig
import java.nio.ByteBuffer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 落盘与切片器字节序须一致 */
class AlacFramePcmTest {

    private val config = requireNotNull(AlacSpecificConfig.parse(COOKIE)) { "cookie 应能解析" }

    @Test
    fun `整帧落盘经切片器还原为单声道整窗`() = runTest {
        val decoder = decode(prefixFrame())
        val pcm = AlacFramePcm(config)
        assertEquals(
            "落盘长度应为样本帧数 × 声道 × 浮点字宽",
            config.frameLength * config.channelCount * Float.SIZE_BYTES,
            pcm.encode(decoder).limit(),
        )

        // 越过暂存容量，覆盖两条清仓路径
        val actual = collectWindows { emit -> repeat(SENDS) { emit(pcm.encode(decoder)) } }
        val expected = referenceWindows(monoOf(decoder, config.frameLength * SENDS))

        assertEquals("整窗数", expected.size, actual.size)
        for (index in expected.indices) {
            assertArrayEquals("窗 $index", expected[index], actual[index], 0f)
        }
    }

    @Test
    fun `分片帧落盘为自身样本数且不足以成窗`() = runTest {
        val decoder = decode(readResource(PARTIAL_FRAME_FIXTURE))
        val pcm = AlacFramePcm(config)

        val emitted = collectWindows { it(pcm.encode(decoder)) }

        assertEquals("分片帧的样本数应取自位流", PARTIAL_FRAME_SAMPLES, decoder.sampleCount)
        assertEquals(
            "落盘长度应为分片样本数 × 声道 × 浮点字宽",
            PARTIAL_FRAME_SAMPLES * config.channelCount * Float.SIZE_BYTES,
            pcm.encode(decoder).limit(),
        )
        assertTrue("样本数不足一个整窗时不应交出任何窗", emitted.isEmpty())
    }

    private fun decode(frame: ByteArray): AlacFrameDecoder =
        AlacFrameDecoder(config).also { it.decode(frame, frame.size) }

    private fun prefixFrame(): ByteArray = readResource(PREFIX_FIXTURE).copyOfRange(0, FRAME_SIZE)

    // 按整帧投递，收走整窗
    private suspend fun collectWindows(
        feed: suspend (emit: suspend (ByteBuffer) -> Unit) -> Unit,
    ): List<FloatArray> {
        val freeWindows = Channel<FloatArray>(FREE_WINDOW_COUNT)
        repeat(FREE_WINDOW_COUNT) { freeWindows.trySend(FloatArray(FFT_SIZE)) }
        val windows = Channel<SpectrogramDecoder.IndexedWindow>(Channel.UNLIMITED)
        val slicer = SpectrogramDecoder.WindowSlicer(freeWindows, MAX_CONVERT_PARTS)

        feed { pcm ->
            slicer.feedBuffer(
                buffer = pcm,
                offset = 0,
                size = pcm.limit(),
                channels = config.channelCount,
                encoding = PcmFormat.ENCODING_FLOAT,
                windows = windows,
            )
        }
        slicer.flush(windows)
        windows.close()

        val emitted = ArrayList<FloatArray>()
        for (window in windows) emitted += window.samples
        return emitted
    }

    // 基准取声道均值，与浮点归一化一致
    private fun monoOf(decoder: AlacFrameDecoder, frames: Int): FloatArray {
        val scale = (1L shl (config.bitDepth - 1)).toFloat()
        return FloatArray(frames) { frame ->
            var acc = 0f
            for (channel in 0 until config.channelCount) {
                acc += decoder.channels[channel][frame % config.frameLength] / scale
            }
            acc / config.channelCount
        }
    }

    private fun referenceWindows(mono: FloatArray): List<FloatArray> {
        if (mono.size < FFT_SIZE) return emptyList()
        val count = (mono.size - FFT_SIZE) / HOP_SIZE + 1
        return List(count) { window -> FloatArray(FFT_SIZE) { k -> mono[window * HOP_SIZE + k] } }
    }

    private fun readResource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/audio/$name")) { "缺少测试资源 $name" }
            .use { it.readBytes() }

    private companion object {
        /** 容器下发的 ALACSpecificConfig */
        val COOKIE = hex("000010000018280a0e0200ff000060040046500000017700")

        const val PREFIX_FIXTURE = "alac-frames-prefix.bin"
        const val PARTIAL_FRAME_FIXTURE = "alac-frame-partial.bin"

        /** 前缀夹具首帧的字节数 */
        const val FRAME_SIZE = 8224

        /** 分片帧的样本数，取自位流 */
        const val PARTIAL_FRAME_SAMPLES = 154

        // 窗长与跳步，与解码端一致
        const val FFT_SIZE = 2048
        const val HOP_SIZE = FFT_SIZE / 2

        // 越过暂存容量 16384 帧
        const val SENDS = 5

        const val FREE_WINDOW_COUNT = 64
        const val MAX_CONVERT_PARTS = 4

        fun hex(text: String): ByteArray =
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
