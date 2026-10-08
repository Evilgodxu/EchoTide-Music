package com.yichao.evilgodxu.data.music.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 切片分帧复核。
 *
 * 切片器把 PCM 攒成暂存块再分段并行转换：块边界、暂存块容量边界与段边界都可能改变分帧结果，
 * 而分帧一旦错位，频谱图上只表现为整幅图错位，不会有任何显式报错。
 * 故这里以逐样本直入的朴素实现为基准，逐窗比对分段并行实现的结果。
 */
class WindowSlicerTest {

    private companion object {
        // 与解码端同源的窗长与跳步
        const val FFT_SIZE = 2048
        const val HOP_SIZE = FFT_SIZE / 2

        // 空闲窗缓冲给到远超总窗数，让投递不因取不到缓冲而挂起
        const val FREE_WINDOW_COUNT = 64
    }

    @Test
    fun splitFeedsMatchSampleBySampleReference() = runTest {
        // 帧数取到超过暂存块容量（16bit 立体声下为 32768 帧），
        // 一次跑遍「装满了自动清仓」与「收尾清仓」两条路径
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
        // 块大小不整除窗长，逼近解码器一次一块的真实节奏
        val actual = collectWindows(pcm, channels, PcmFormat.ENCODING_16BIT, entryBytes = 1500, parts = 4)

        assertWindows(expected, actual)
    }

    @Test
    fun floatEncodingMatchesReference() = runTest {
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
    fun singlePartFlushMatchesReference() = runTest {
        // 帧数低于单段下限，转换退化为单段；此时入窗路径与并行路径必须给出同一结果
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

    // 驱动切片器：按固定字节数逐块喂入，收走其发出的整窗。
    // 块大小取采样帧的整数倍：解码输出缓冲本就按整帧给出，切片器也只按整帧消费
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

    // 基准实现：逐样本取声道均值后再按跳步取窗，等价于最早的串行切片逻辑
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
