package com.yichao.evilgodxu.data.music.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** 以逐样本直入为基准比对分段并行 */
class WindowSlicerTest {

    private companion object {
        // 窗长与跳步，与解码端一致
        const val FFT_SIZE = 2048
        const val HOP_SIZE = FFT_SIZE / 2

        // 空闲窗缓冲远超总窗数，避免挂起
        const val FREE_WINDOW_COUNT = 64
    }

    @Test
    fun `分段送流与逐样本参考一致`() = runTest {
        // 超过暂存容量 32768 帧，覆盖两条清仓路径
        val frames = 40_000
        val channels = 2
        val shorts = ShortArray(frames * channels) { index ->
            ((index * 37) % 20011 - 10005).toShort()
        }
        val pcm = ByteBuffer.allocate(shorts.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> for (value in shorts) buffer.putShort(value) }
            .array()

        val expected = referenceWindows(monoOf(FloatArray(shorts.size) { shorts[it] / 32768f }, channels))
        // 块大小不整除窗长，逼近真实节奏
        val actual = collectWindows(pcm, channels, PcmFormat.ENCODING_16BIT, entryBytes = 1500, parts = 4)

        assertWindows(expected, actual)
    }

    @Test
    fun `浮点编码与参考一致`() = runTest {
        val frames = 12_000
        val channels = 2
        val samples = FloatArray(frames * channels) { index ->
            ((index * 13) % 4001 - 2000) / 2000f
        }
        val pcm = ByteBuffer.allocate(samples.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> for (value in samples) buffer.putFloat(value) }
            .array()

        val expected = referenceWindows(monoOf(samples, channels))
        val actual = collectWindows(pcm, channels, PcmFormat.ENCODING_FLOAT, entryBytes = 1600, parts = 4)

        assertWindows(expected, actual)
    }

    @Test
    fun `单段收尾与参考一致`() = runTest {
        // 低于单段下限时退化为单段
        val frames = 5_000
        val channels = 1
        val shorts = ShortArray(frames) { index -> ((index * 61) % 30011 - 15005).toShort() }
        val pcm = ByteBuffer.allocate(shorts.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> for (value in shorts) buffer.putShort(value) }
            .array()

        val expected = referenceWindows(monoOf(FloatArray(frames) { shorts[it] / 32768f }, channels))
        val actual = collectWindows(pcm, channels, PcmFormat.ENCODING_16BIT, entryBytes = 10_000, parts = 1)

        assertWindows(expected, actual)
    }

    // 按固定字节数逐块喂入，收走整窗
    // 块大小取采样帧整数倍
    private suspend fun collectWindows(
        pcm: ByteArray,
        channels: Int,
        encoding: Int,
        entryBytes: Int,
        parts: Int,
    ): List<FloatArray> {
        val freeWindows = Channel<FloatArray>(FREE_WINDOW_COUNT)
        repeat(FREE_WINDOW_COUNT) { freeWindows.trySend(FloatArray(FFT_SIZE)) }
        val windows = Channel<SpectrogramDecoder.IndexedWindow>(Channel.UNLIMITED)
        val slicer = SpectrogramDecoder.WindowSlicer(freeWindows, parts)

        val entry = ByteBuffer.allocate(entryBytes).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = 0
        while (cursor < pcm.size) {
            val size = minOf(entryBytes, pcm.size - cursor)
            entry.clear()
            entry.put(pcm, cursor, size)
            entry.flip()
            slicer.feedBuffer(entry, 0, size, channels, encoding, windows)
            cursor += size
        }
        slicer.flush(windows)
        windows.close()

        val emitted = ArrayList<FloatArray>()
        for (window in windows) emitted += window.samples
        return emitted
    }

    // 基准：逐声道均值后按跳步取窗
    private fun monoOf(perSample: FloatArray, channels: Int): FloatArray {
        val frames = perSample.size / channels
        return FloatArray(frames) { frame ->
            var acc = 0f
            for (channel in 0 until channels) acc += perSample[frame * channels + channel]
            acc / channels
        }
    }

    private fun referenceWindows(mono: FloatArray): List<FloatArray> {
        if (mono.size < FFT_SIZE) return emptyList()
        val count = (mono.size - FFT_SIZE) / HOP_SIZE + 1
        return List(count) { window -> FloatArray(FFT_SIZE) { k -> mono[window * HOP_SIZE + k] } }
    }

    private fun assertWindows(expected: List<FloatArray>, actual: List<FloatArray>) {
        assertEquals("整窗数", expected.size, actual.size)
        for (index in expected.indices) {
            assertArrayEquals("窗 $index", expected[index], actual[index], 0f)
        }
    }
}
