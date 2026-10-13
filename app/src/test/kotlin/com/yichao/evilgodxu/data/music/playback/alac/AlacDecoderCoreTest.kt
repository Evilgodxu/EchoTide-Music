package com.yichao.evilgodxu.data.music.playback.alac

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ALAC 解码核心的实测：以一条真实 ALAC 曲目的帧为样本，逐帧核对解出 PCM 的 SHA-256。
 *
 * 样本取自 `Temporary Test Resources` 下的《02. 願我可以學會放低你 [ALAC] [16 bit - 44.1 kHz].m4a》
 * （按容器样本表切成裸帧，即本解码器实际的输入）。期望摘要是同一条曲目经 FFmpeg 解码所得的 PCM——
 * 即本实现与另一条独立实现逐位一致，而非与自身既有输出比对：任何一位偏差都会让摘要不同。
 *
 * 前缀段覆盖静音帧、低熵帧与接近满帧的高熵帧；末尾一帧是唯一自带样本数的分片帧。
 */
class AlacDecoderCoreTest {

    private val config = requireNotNull(AlacSpecificConfig.parse(COOKIE)) { "cookie 应能解析" }

    @Test
    fun magicCookieYieldsCodecParameters() {
        assertEquals(4096, config.frameLength)
        assertEquals(16, config.bitDepth)
        assertEquals(40, config.pb)
        assertEquals(10, config.mb)
        assertEquals(14, config.kb)
        assertEquals(2, config.channelCount)
        assertEquals(44100, config.sampleRate)
    }

    @Test
    fun magicCookieIgnoresHistoricalAtomFraming() {
        // 历史编码器会把配置再包一层 'frma' / 'alac' 原子，两个原子头都须跳过
        val framed = ByteArray(ATOM_HEADER_BYTES * 2) + COOKIE
        writeTag(framed, 0, "frma")
        writeTag(framed, ATOM_HEADER_BYTES, "alac")

        val parsed = requireNotNull(AlacSpecificConfig.parse(framed)) { "带原子包装的 cookie 应能解析" }

        assertEquals(config.sampleRate, parsed.sampleRate)
        assertEquals(config.channelCount, parsed.channelCount)
        assertEquals(config.bitDepth, parsed.bitDepth)
    }

    @Test
    fun magicCookieRejectsUnsupportedParameters() {
        assertNull("长度不足应判为不支持", AlacSpecificConfig.parse(COOKIE.copyOf(20)))

        assertNull(
            "兼容版本高于实现应判为不支持",
            AlacSpecificConfig.parse(COOKIE.copyOf().also { it[4] = 1 }),
        )
        assertNull(
            "无输出分支的位深应判为不支持",
            AlacSpecificConfig.parse(COOKIE.copyOf().also { it[5] = 28 }),
        )
        assertNull(
            "均值的移位量为 0 应判为不支持",
            AlacSpecificConfig.parse(COOKIE.copyOf().also { it[8] = 0 }),
        )
        assertNull(
            "采样率为 0 应判为不支持",
            AlacSpecificConfig.parse(COOKIE.copyOf().also { for (i in 20..23) it[i] = 0 }),
        )
        assertNull(
            "声道数超出实现应判为不支持",
            AlacSpecificConfig.parse(COOKIE.copyOf().also { it[9] = 9 }),
        )
    }

    @Test
    fun bitReaderReadsMsbFirstAcrossByteBoundaries() {
        val reader = AlacBitReader(byteArrayOf(0b10110110.toByte(), 0b01000000), length = 2)

        assertEquals(0b101, reader.read(3))
        assertEquals(0b101, reader.read(3))
        assertEquals(0b10010000, reader.read(8))
        // 越过缓冲末尾按 0 补齐，而不是读到缓冲区里别处的数据
        assertEquals(0, reader.read(8))
        assertEquals(0b101, AlacBitReader(byteArrayOf(0b10100000.toByte()), 1).peek(3))
    }

    @Test
    fun decodesFramesBitExactlyAgainstFfmpegReference() {
        val frames = readResource(PREFIX_FIXTURE)
        var offset = 0
        FRAME_SIZES.forEachIndexed { index, size ->
            val decoder = AlacFrameDecoder(config)
            decoder.decode(frames.copyOfRange(offset, offset + size), size)

            assertEquals(config.frameLength, decoder.sampleCount)
            assertEquals(
                "第 $index 帧（$size 字节）解出的 PCM 与参考解码器不一致",
                EXPECTED_DIGESTS[index],
                sha256(encodePcm(decoder, AlacOutputEncoding.INT16)),
            )
            offset += size
        }
        assertEquals("前缀夹具应恰好用完", frames.size, offset)
    }

    @Test
    fun decodesTrailingPartialFrameByItsOwnSampleCount() {
        val frame = readResource(PARTIAL_FRAME_FIXTURE)
        val decoder = AlacFrameDecoder(config)

        decoder.decode(frame, frame.size)

        assertEquals("分片帧的样本数应取自位流", PARTIAL_FRAME_SAMPLES, decoder.sampleCount)
        assertEquals(
            "分片帧解出的 PCM 与参考解码器不一致",
            EXPECTED_PARTIAL_DIGEST,
            sha256(encodePcm(decoder, AlacOutputEncoding.INT16)),
        )
    }

    @Test
    fun rejectsEmptyFrame() {
        expectRejected("空帧应被判为不可解") { AlacFrameDecoder(config).decode(ByteArray(0), 0) }
    }

    @Test
    fun rejectsIllegalExtraBitsCount() {
        // 附加低位字节数占标志字节的中间两位，取值 3 在参考实现里同样是非法值
        val frame = readResource(PREFIX_FIXTURE).copyOf(FRAME_SIZES[0])
        setBits(frame, FLAGS_BIT_POSITION + 1, 2, 0b11)

        expectRejected("附加低位字节数为 3 应被判为不可解") {
            AlacFrameDecoder(config).decode(frame, frame.size)
        }
    }

    @Test
    fun pcmEncoderRescalesBetweenWordLengths() {
        val channels = arrayOf(intArrayOf(1, -32768, 32767), intArrayOf(-1, 256, -256))
        val samples = 3

        assertArrayEquals(
            "16 位输出应按左右交错、低位在前写出",
            byteArrayOf(
                0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(),
                0x00, 0x80.toByte(), 0x00, 0x01,
                0xFF.toByte(), 0x7F, 0x00, 0xFF.toByte(),
            ),
            encodeChannels(channels, samples, 16, AlacOutputEncoding.INT16),
        )
        // 24 位输出把 16 位源左移补齐，低位补零而不是丢位
        assertArrayEquals(
            byteArrayOf(0x00, 0x01, 0x00),
            encodeChannels(channels, samples, 16, AlacOutputEncoding.INT24).copyOfRange(0, 3),
        )
        // 浮点输出按源码位深归一化
        val floats = ByteBuffer.wrap(encodeChannels(channels, samples, 16, AlacOutputEncoding.FLOAT))
            .order(ByteOrder.nativeOrder())
        assertEquals(1f / 32768f, floats.getFloat(0), 0f)
        assertEquals(-1f / 32768f, floats.getFloat(Float.SIZE_BYTES), 0f)
    }

    private fun encodePcm(decoder: AlacFrameDecoder, encoding: AlacOutputEncoding): ByteArray =
        encodeChannels(decoder.channels, decoder.sampleCount, config.bitDepth, encoding)

    private fun encodeChannels(
        channels: Array<IntArray>,
        sampleCount: Int,
        bitDepth: Int,
        encoding: AlacOutputEncoding,
    ): ByteArray {
        val bytes = AlacPcmEncoder.frameByteCount(sampleCount, channels.size, encoding)
        val buffer = ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder())
        AlacPcmEncoder.encode(channels, sampleCount, channels.size, bitDepth, encoding, buffer)
        return buffer.array()
    }

    private fun expectRejected(message: String, block: () -> Unit) {
        try {
            block()
            fail(message)
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    private fun readResource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/audio/$name")) { "缺少测试资源 $name" }
            .use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun writeTag(target: ByteArray, offset: Int, tag: String) {
        tag.forEachIndexed { index, char -> target[offset + ATOM_TAG_OFFSET + index] = char.code.toByte() }
    }

    /** 按位写入，供构造非法头字段用；位序与 ALAC 位流一致，MSB 在前 */
    private fun setBits(target: ByteArray, position: Int, count: Int, value: Int) {
        for (offset in 0 until count) {
            val bit = (value shr (count - 1 - offset)) and 1
            val index = position + offset
            val mask = 1 shl (7 - (index and 7))
            val current = target[index shr 3].toInt() and 0xFF
            target[index shr 3] = (if (bit == 1) current or mask else current and mask.inv()).toByte()
        }
    }

    private companion object {
        /** Temporary Test Resources 曲目的 magic cookie，即容器下发的 ALACSpecificConfig */
        val COOKIE = hex("000010000010280a0e0200ff00004004001588800000ac44")

        /** 前缀夹具里逐帧的字节数，按容器样本表原样抄录 */
        val FRAME_SIZES =
            intArrayOf(32, 32, 668, 808, 738, 1382, 2629, 6778, 7546, 8076, 6278, 7212, 6598, 6103)

        /** 各帧解出 PCM 的 SHA-256，由 FFmpeg（PyAV，自带 libalac）解码同一曲目生成 */
        val EXPECTED_DIGESTS = arrayOf(
            "4fe7b59af6de3b665b67788cc2f99892ab827efae3a467342b3bb4e3bc8e5bfe",
            "4fe7b59af6de3b665b67788cc2f99892ab827efae3a467342b3bb4e3bc8e5bfe",
            "6b15dda61c83b7e816d0dd48f3d1c9c3588c221191e99d9fed45ddac6b3d27b4",
            "23caab1aa59df621f62db6360d4df1afb57d476261ca43a4483d537439891a32",
            "11fa5b5b1379b39c5fb9b7f9cc806027fde8da7b76bdd4a6a13a452d2f3fc94c",
            "a71d61dbfb5a3059c9201d71adad149608a01fe7c3a257c3334e45d7134024d4",
            "3baf9519ab23adb29ee4313b4a298c622b3934d02e67557a40cf9d805fd4a6e0",
            "ca6e2a3551cd559e76220aec525fd6d4d206183effea4784e3ccbcff307f249a",
            "7e65143fe81829093889d2de4d0ea223a345392142f30d3696ec2bb9bffd30f9",
            "be42b940b0c1c550f31dcb6354f8e557083dd0e862497c683a9eccce835b1dc3",
            "e521552d690b1b1424e7411418ca564dcd9d02580ef369b075d0d1241aa5ef13",
            "f7292fd105d5a0a5f526a5ed7ad7a4b2af47fa7f8e62473d2152c370b1a10963",
            "e0b8afc3238fc321c2ac4e611f06e558ae9c77047608f5f52dadc7a46d98105a",
            "61976489ea9d12494935dcec58f277eef1c287e4bc4e27147c5507cab4063778",
        )
        const val PREFIX_FIXTURE = "alac-frames-prefix.bin"
        const val PARTIAL_FRAME_FIXTURE = "alac-frame-partial.bin"
        const val PARTIAL_FRAME_SAMPLES = 1248
        const val EXPECTED_PARTIAL_DIGEST =
            "3b628fecece8197f561fb51e00f8fb1ad33c4e4bc3ff7b0a5d8ba1eb9ed8cb49"

        /** 原子头长度，与 [AlacSpecificConfig] 跳过的一致 */
        const val ATOM_HEADER_BYTES = 12
        const val ATOM_TAG_OFFSET = 4

        /** 标志字节的起始位：元素标签 3 位 + 实例标签 4 位 + 保留头 12 位 */
        const val FLAGS_BIT_POSITION = 19

        fun hex(text: String): ByteArray =
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
