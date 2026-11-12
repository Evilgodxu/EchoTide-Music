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
 * 样本取自 `YiChaoTmp` 下的《陈慧娴 - 千千阕歌.m4a》（24 bit - 96 kHz，按容器样本表切成裸帧，
 * 即本解码器实际的输入）。期望摘要是同一条曲目经 FFmpeg（PyAV / libalac）解码所得的 PCM——
 * 即本实现与另一条独立实现逐位一致，而非与自身既有输出比对：任何一位偏差都会让摘要不同。
 *
 * 24 bit 是覆盖重点：附加低位段只在 24/32 bit 的写法中并入样本，16 bit 走不到该分支。
 * 前缀段由中熵帧过渡到高熵帧；末尾一帧是唯一自带样本数的分片帧。
 */
class AlacDecoderCoreTest {

    private val config = requireNotNull(AlacSpecificConfig.parse(COOKIE)) { "cookie 应能解析" }

    @Test
    fun `magic cookie 解析出编码参数`() {
        assertEquals(4096, config.frameLength)
        assertEquals(24, config.bitDepth)
        assertEquals(40, config.pb)
        assertEquals(10, config.mb)
        assertEquals(14, config.kb)
        assertEquals(2, config.channelCount)
        assertEquals(96000, config.sampleRate)
    }

    @Test
    fun `magic cookie 忽略历史原子封装`() {
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
    fun `magic cookie 拒绝过大的帧分配`() {
        // 24 字节配置就能声明一帧上亿样本；解码器会按帧长 × 声道立刻预分配缓冲，
        // 必须在解析阶段按内存预算拒绝，而不是等构造解码器时 OOM
        val huge = COOKIE.copyOf().also { cookie ->
            cookie[0] = 0x05
            cookie[1] = 0xF5.toByte()
            cookie[2] = 0xE1.toByte()
            cookie[3] = 0x00
        }
        assertNull("超出单帧样本预算的帧长应判为不支持", AlacSpecificConfig.parse(huge))
    }

    @Test
    fun `magic cookie 拒绝不支持的参数`() {
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
    fun `位读取器跨字节边界按高位优先读`() {
        val reader = AlacBitReader(byteArrayOf(0b10110110.toByte(), 0b01000000), length = 2)

        assertEquals(0b101, reader.read(3))
        assertEquals(0b101, reader.read(3))
        assertEquals(0b10010000, reader.read(8))
        // 越过缓冲末尾按 0 补齐，而不是读到缓冲区里别处的数据
        assertEquals(0, reader.read(8))
        assertEquals(0b101, AlacBitReader(byteArrayOf(0b10100000.toByte()), 1).peek(3))
    }

    @Test
    fun `解码帧与 ffmpeg 参考位精确一致`() {
        val frames = readResource(PREFIX_FIXTURE)
        var offset = 0
        FRAME_SIZES.forEachIndexed { index, size ->
            val decoder = AlacFrameDecoder(config)
            decoder.decode(frames.copyOfRange(offset, offset + size), size)

            assertEquals(config.frameLength, decoder.sampleCount)
            assertEquals(
                "第 $index 帧（$size 字节）解出的 PCM 与参考解码器不一致",
                EXPECTED_DIGESTS[index],
                sha256(encodePcm(decoder, AlacOutputEncoding.INT32)),
            )
            offset += size
        }
        assertEquals("前缀夹具应恰好用完", frames.size, offset)
    }

    @Test
    fun `尾部不完整帧按自身样本数解码`() {
        val frame = readResource(PARTIAL_FRAME_FIXTURE)
        val decoder = AlacFrameDecoder(config)

        decoder.decode(frame, frame.size)

        assertEquals("分片帧的样本数应取自位流", PARTIAL_FRAME_SAMPLES, decoder.sampleCount)
        assertEquals(
            "分片帧解出的 PCM 与参考解码器不一致",
            EXPECTED_PARTIAL_DIGEST,
            sha256(encodePcm(decoder, AlacOutputEncoding.INT32)),
        )
    }

    @Test
    fun `空帧被拒绝`() {
        expectRejected("空帧应被判为不可解") { AlacFrameDecoder(config).decode(ByteArray(0), 0) }
    }

    @Test
    fun `非法附加位数被拒绝`() {
        // 附加低位字节数占标志字节的中间两位，取值 3 在参考实现里同样是非法值
        val frame = readResource(PREFIX_FIXTURE).copyOf(FRAME_SIZES[0])
        setBits(frame, FLAGS_BIT_POSITION + 1, 2, 0b11)

        expectRejected("附加低位字节数为 3 应被判为不可解") {
            AlacFrameDecoder(config).decode(frame, frame.size)
        }
    }

    @Test
    fun `PCM 编码器在位宽之间重缩放`() {
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

    // 摘要比对取 32 位落盘而非 16 位：24 bit 源低位若解错，右移到 16 位会把它抹掉，
    // 那正是本用例要守的部分
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
        /** 容器下发的 magic cookie，即 ALACSpecificConfig（frameLength/位深/pb/mb/kb/声道/采样率） */
        val COOKIE = hex("000010000018280a0e0200ff000060040046500000017700")

        /** 前缀夹具里逐帧的字节数，按容器样本表原样抄录 */
        val FRAME_SIZES =
            intArrayOf(8224, 8224, 8224, 8237, 8224, 8224, 12655, 16767, 15671, 15260, 15049, 15187, 16175, 17149)

        /** 各帧解出 PCM（32 位落盘）的 SHA-256，由 FFmpeg（PyAV，自带 libalac）解码同一曲目生成 */
        val EXPECTED_DIGESTS = arrayOf(
            "7a57d4e7a2eed0bef0ed78ca897abebe2f92cc14cc814ee3d07a9f8690729b45",
            "dc9eb23358622728282f53c4a8e98710c7dd83a1d672a945095d7d8329f9fc5c",
            "4bb1046071752770dbe9c7b8b6224861b2a4475c0148f33235d6994f1116c595",
            "0105df8acca6563b889d13628579b721464a045c2cda52c91f9384336c4bcc86",
            "64972e0699a1125b543038ebdf8d4afacbc1e3eeb999a7c8641ba68e1c1322de",
            "a2c3acc00804a184ffd65b6f543cddfb8124e88a03a1d0bd0f4a139c847e6089",
            "80f4fb59f113ac3113d7764b5fd846c3db48dd5324a01e8f85eba9bb48c281c4",
            "b27b32536e3a3713c4ff8dd651c73abcdd05a48a7863da4cf7fa4eece85aa100",
            "d1c4c78c09af9742fd17f624e7a15f9e08aef585a85f56f98f7a54dcf6f2afcc",
            "f10c29d23607ed1bf10b026104fa23f18b1be9f175f8a969648df5ce803d951a",
            "ffb570c3da28698967de6f90c7dd9685f7f62445232bf1aab8fbad6a6999b6bc",
            "e53606ff47c837981e3290836d8c6009986ca41dc7e0adb3a1be21398a196b39",
            "1cfb8566c3ddb00564c2a9308dad6d7470a0b4e52af3c7621816e43cbc65698d",
            "a751c9bce1fbac6d8dd211ccfedbed28a3882a22189b75ea98f0a24edd810077",
        )

        const val PREFIX_FIXTURE = "alac-frames-prefix.bin"
        const val PARTIAL_FRAME_FIXTURE = "alac-frame-partial.bin"
        const val PARTIAL_FRAME_SAMPLES = 154
        const val EXPECTED_PARTIAL_DIGEST =
            "5b45929db3579a09eca88f37ff3b86ef69cfe3af1e424aa84e11c47bd60cc86b"

        /** 原子头长度，与 [AlacSpecificConfig] 跳过的一致 */
        const val ATOM_HEADER_BYTES = 12
        const val ATOM_TAG_OFFSET = 4

        /** 标志字节的起始位：元素标签 3 位 + 实例标签 4 位 + 保留头 12 位 */
        const val FLAGS_BIT_POSITION = 19

        fun hex(text: String): ByteArray =
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
