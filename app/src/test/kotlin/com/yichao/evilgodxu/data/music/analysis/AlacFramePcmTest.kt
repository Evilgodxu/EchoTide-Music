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

/**
 * ALAC 帧接入切片器的实测：以真实 ALAC 曲目的帧为样本，核对 [AlacFramePcm] 的落盘经切片器
 * 还原成单声道整窗后与解码样本一致。
 *
 * 守的是落盘与解释两端的字节序：[AlacFramePcm] 按小端写，切片器按 [PcmFormat] 小端读，
 * 两侧一旦错开不会报错，只会让频谱静默退化成噪声——逐样本比对能立刻暴露。
 *
 * 样本取自 `YiChaoTmp` 下的《陈慧娴 - 千千阕歌.m4a》（24 bit - 96 kHz），与 `AlacDecoderCoreTest` 同源。
 */
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

        // 重复送同一帧：累计帧数越过暂存块容量，同时走遍「装满即清仓」与「收尾清仓」两条路径
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

    // 驱动切片器：按整帧投递，收走其发出的整窗
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

    // 基准：逐样本取声道均值，口径与解码样本的浮点归一化一致
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
        /** magic cookie，即容器下发的 ALACSpecificConfig，与 AlacDecoderCoreTest 同一份 */
        val COOKIE = hex("000010000018280a0e0200ff000060040046500000017700")

        const val PREFIX_FIXTURE = "alac-frames-prefix.bin"
        const val PARTIAL_FRAME_FIXTURE = "alac-frame-partial.bin"

        /** 前缀夹具首帧的字节数 */
        const val FRAME_SIZE = 8224

        /** 分片帧的样本数，取自位流 */
        const val PARTIAL_FRAME_SAMPLES = 154

        // 与解码端同源的窗长与跳步
        const val FFT_SIZE = 2048
        const val HOP_SIZE = FFT_SIZE / 2

        // 送帧次数：需越过暂存块容量（浮点立体声下为 16384 帧），才会走到「装满即清仓」
        const val SENDS = 5

        const val FREE_WINDOW_COUNT = 64
        const val MAX_CONVERT_PARTS = 4

        fun hex(text: String): ByteArray =
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
