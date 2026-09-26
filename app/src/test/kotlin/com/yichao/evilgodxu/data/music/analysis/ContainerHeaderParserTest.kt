package com.yichao.evilgodxu.data.music.analysis

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 容器头解析复核：逐格式构造合成头部字节，断言取出的采样率/位深/声道。
 * 合成数据只按各容器的公开规范手工排布，不调用被测代码的位运算，避免与实现同错。
 */
class ContainerHeaderParserTest {

    @Test
    fun flacStreaminfoIsParsed() {
        val bytes = flac(44_100, 16, 2)
        assertEquals(ContainerFormat(44_100, 16, 2), ContainerHeaderParser.parse(bytes, 0, bytes.size.toLong()))
    }

    @Test
    fun flacHighBitDepthDoesNotAliasTo16Bit() {
        // 32bit 的位深域值为 31，低 4 位进位曾使旧实现误读为 16bit
        val bytes = flac(192_000, 32, 2)
        assertEquals(ContainerFormat(192_000, 32, 2), ContainerHeaderParser.parse(bytes, 0, bytes.size.toLong()))
    }

    @Test
    fun id3v2PrefixIsReportedSoContainerCanBeRelocated() {
        val tag = id3Tag(payloadBytes = 1024)
        val bytes = tag + flac(48_000, 24, 2)
        assertEquals(tag.size, ContainerHeaderParser.id3v2TagSize(bytes))
        // 标签之后的音频数据才含容器魔数：按偏移重新定位即可解析
        assertNull(ContainerHeaderParser.parse(bytes, 0, bytes.size.toLong()))
        assertEquals(
            ContainerFormat(48_000, 24, 2),
            ContainerHeaderParser.parse(bytes.copyOfRange(tag.size, bytes.size), tag.size.toLong(), bytes.size.toLong()),
        )
    }

    @Test
    fun plainAudioHasNoId3Tag() {
        val bytes = flac(44_100, 16, 2)
        assertEquals(0, ContainerHeaderParser.id3v2TagSize(bytes))
    }

    @Test
    fun wavFmtChunkIsParsed() {
        val bytes = wav(channels = 2, sampleRate = 48_000, bitDepth = 24)
        assertEquals(ContainerFormat(48_000, 24, 2), ContainerHeaderParser.parse(bytes, 0, bytes.size.toLong()))
    }

    @Test
    fun wavFmtAfterOtherChunkIsFoundByWalkingChunks() {
        val bytes = wav(channels = 2, sampleRate = 44_100, bitDepth = 16, leadingChunkId = "JUNK", leadingChunkBytes = 64)
        assertEquals(ContainerFormat(44_100, 16, 2), ContainerHeaderParser.parse(bytes, 0, bytes.size.toLong()))
    }

    @Test
    fun wavExtensiblePrefersValidBitsOverContainerBits() {
        val bytes = wav(
            channels = 2,
            sampleRate = 96_000,
            bitDepth = 24,
            formatTag = 0xFFFE,
            validBitsPerSample = 20,
        )
        assertEquals(ContainerFormat(96_000, 20, 2), ContainerHeaderParser.parse(bytes, 0, bytes.size.toLong()))
    }

    @Test
    fun aiffCommIsParsed() {
        val bytes = aiff(channels = 2, sampleRate = 44_100, bitDepth = 16)
        assertEquals(ContainerFormat(44_100, 16, 2), ContainerHeaderParser.parse(bytes, 0, bytes.size.toLong()))
    }

    @Test
    fun apeHeaderIsParsed() {
        val bytes = ape(channels = 2, sampleRate = 44_100, bitDepth = 24)
        assertEquals(ContainerFormat(44_100, 24, 2), ContainerHeaderParser.parse(bytes, 0, bytes.size.toLong()))
    }

    @Test
    fun dsfFmtReportsOneBitPhysicalDepth() {
        // DSF 的 "bits per sample" 字段标注位序（1=LSB/8=MSB），物理位深恒为 1
        val bytes = dsf(channels = 2, sampleRate = 2_822_400, bitOrderFlag = 8)
        assertEquals(ContainerFormat(2_822_400, 1, 2), ContainerHeaderParser.parse(bytes, 0, bytes.size.toLong()))
    }

    @Test
    fun dffPropIsParsed() {
        val bytes = dff(channels = 2, sampleRate = 5_644_800)
        assertEquals(ContainerFormat(5_644_800, 1, 2), ContainerHeaderParser.parse(bytes, 0, bytes.size.toLong()))
    }

    @Test
    fun alacBitDepthComesFromMagicCookie() {
        // 采样描述自带的 samplesize（16）对 ALAC 是名义值，真实位深在 alac 幻数盒中
        val bytes = alacFile(bitDepth = 24, channels = 2, sampleRate = 48_000)
        assertEquals(ContainerFormat(48_000, 24, 2), ContainerHeaderParser.parse(bytes, 0, bytes.size.toLong()))
    }

    @Test
    fun mp4BoxOffsetSkipsBoxLargerThanWindow() {
        // moov 后置：头窗在超大 mdat 处被截断，据其声明尺寸外推出 moov 起点
        val ftyp = box("ftyp", Builder().ascii("M4A ").u32be(0).ascii("M4A ").toBytes())
        val truncatedMdat = Builder().u32be(MDAT_DECLARED_BYTES).ascii("mdat").toBytes()
        val head = ftyp + truncatedMdat
        val expectedMoov = ftyp.size.toLong() + MDAT_DECLARED_BYTES
        assertEquals(
            expectedMoov,
            ContainerHeaderParser.mp4BoxOffset(head, 0, expectedMoov + 4096, "moov"),
        )
    }

    @Test
    fun unknownHeaderIsRejected() {
        assertNull(ContainerHeaderParser.parse(ByteArray(64), 0, 64))
    }

    @Test
    fun truncatedHeaderIsRejected() {
        val bytes = flac(44_100, 16, 2)
        assertNull(ContainerHeaderParser.parse(bytes.copyOfRange(0, 12), 0, bytes.size.toLong()))
    }

    // ---- 合成头部构造 ----

    private class Builder {
        private val out = ByteArrayOutputStream()

        fun ascii(text: String) = apply { out.write(text.toByteArray(Charsets.ISO_8859_1)) }

        fun u8(value: Int) = apply { out.write(value and 0xFF) }

        fun u16be(value: Int) = apply { u8(value ushr 8); u8(value) }

        fun u16le(value: Int) = apply { u8(value); u8(value ushr 8) }

        fun u32be(value: Long) = apply {
            u8((value ushr 24).toInt()); u8((value ushr 16).toInt())
            u8((value ushr 8).toInt()); u8(value.toInt())
        }

        fun u32be(value: Int) = u32be(value.toLong())

        fun u32le(value: Long) = apply {
            u8(value.toInt()); u8((value ushr 8).toInt())
            u8((value ushr 16).toInt()); u8((value ushr 24).toInt())
        }

        fun u32le(value: Int) = u32le(value.toLong())

        fun u64be(value: Long) = apply { u32be(value ushr 32); u32be(value and 0xFFFFFFFFL) }

        fun u64be(value: Int) = u64be(value.toLong())

        fun u64le(value: Long) = apply { u32le(value and 0xFFFFFFFFL); u32le(value ushr 32) }

        fun u64le(value: Int) = u64le(value.toLong())

        fun zeros(count: Int) = apply { repeat(count) { u8(0) } }

        fun splice(bytes: ByteArray) = apply { out.write(bytes) }

        fun toBytes(): ByteArray = out.toByteArray()
    }

    // FLAC：STREAMINFO 的采样率 20 位 + 声道数-1 共 3 位 + 位深-1 共 5 位打包在块体偏移 10 起的 8 字节中
    private fun flac(sampleRate: Int, bitDepth: Int, channels: Int): ByteArray {
        val packed = (sampleRate.toLong() shl 44) or
            (((channels - 1).toLong()) shl 41) or
            (((bitDepth - 1).toLong()) shl 36)
        return Builder()
            .ascii("fLaC")
            .u8(0x00).u8(0x00).u8(0x00).u8(34)
            .u16be(4096).u16be(4096).zeros(6)
            .u64be(packed)
            .toBytes()
    }

    private fun id3Tag(payloadBytes: Int): ByteArray = Builder()
        .ascii("ID3").u8(4).u8(0).u8(0)
        .u8((payloadBytes ushr 21) and 0x7F)
        .u8((payloadBytes ushr 14) and 0x7F)
        .u8((payloadBytes ushr 7) and 0x7F)
        .u8(payloadBytes and 0x7F)
        .zeros(payloadBytes)
        .toBytes()

    private fun wav(
        channels: Int,
        sampleRate: Int,
        bitDepth: Int,
        formatTag: Int = 1,
        validBitsPerSample: Int = 0,
        leadingChunkId: String = "",
        leadingChunkBytes: Int = 0,
    ): ByteArray {
        val fmtBytes = if (formatTag == 0xFFFE) 40 else 16
        val fmt = Builder()
            .u16le(formatTag).u16le(channels).u32le(sampleRate.toLong())
            .u32le(sampleRate.toLong() * channels * bitDepth / 8).u16le(channels * bitDepth / 8)
            .u16le(bitDepth)
        if (formatTag == 0xFFFE) {
            fmt.u16le(22).u16le(validBitsPerSample).u32be(3).zeros(16)
        }
        val body = Builder()
        if (leadingChunkId.isNotEmpty()) {
            body.ascii(leadingChunkId).u32le(leadingChunkBytes.toLong()).zeros(leadingChunkBytes)
        }
        val fmtChunk = body.ascii("fmt ").u32le(fmtBytes.toLong()).splice(fmt.toBytes()).toBytes()
        val dataChunk = Builder().ascii("data").u32le(0).toBytes()
        return Builder()
            .ascii("RIFF").u32le((4 + fmtChunk.size + dataChunk.size).toLong())
            .ascii("WAVE").splice(fmtChunk).splice(dataChunk)
            .toBytes()
    }

    // AIFF：采样率为 80 位 IEEE 扩展精度浮点，COMM 块体偏移 8 起共 10 字节
    private fun aiff(channels: Int, sampleRate: Int, bitDepth: Int): ByteArray {
        val comm = Builder()
            .u16be(channels).u32be(0).u16be(bitDepth)
            .splice(extended80(sampleRate))
            .toBytes()
        val commChunk = Builder().ascii("COMM").u32be(comm.size.toLong()).splice(comm).toBytes()
        return Builder()
            .ascii("FORM").u32be((4 + commChunk.size).toLong())
            .ascii("AIFF").splice(commChunk)
            .toBytes()
    }

    // 80 位扩展精度：15 位偏置指数（偏置 16383）+ 64 位显式整数尾数
    private fun extended80(value: Int): ByteArray {
        var exponent = -1
        var rest = value.toLong()
        while (rest > 0) {
            rest = rest shr 1
            exponent++
        }
        val mantissa = value.toLong() shl (63 - exponent)
        return Builder().u16be(16383 + exponent).u64be(mantissa).toBytes()
    }

    private fun ape(channels: Int, sampleRate: Int, bitDepth: Int): ByteArray = Builder()
        .ascii("MAC ").u16le(3980).u16le(0)
        .u32le(52).u32le(24).u32le(0).u32le(0).u32le(0).u32le(0).u32le(0)
        .zeros(16)
        .u16le(2000).u16le(0).u32le(73_728).u32le(0).u32le(0)
        .u16le(bitDepth).u16le(channels).u32le(sampleRate.toLong())
        .toBytes()

    private fun dsf(channels: Int, sampleRate: Int, bitOrderFlag: Int): ByteArray = Builder()
        .ascii("DSD ").u64le(28).u64le(0).u64le(0)
        .ascii("fmt ").u64le(52)
        .u32le(1).u32le(0).u32le(2).u32le(channels.toLong())
        .u32le(sampleRate.toLong()).u32le(bitOrderFlag.toLong())
        .u64le(0).u32le(4096).u32le(0)
        .toBytes()

    // DFF：FRM8 → FVER → PROP（SND 下的 FS/CHNL/ABSS）
    private fun dff(channels: Int, sampleRate: Int): ByteArray {
        val fver = dffChunk("FVER", Builder().u32be(0x01050000L).toBytes())
        val snd = Builder()
            .ascii("SND ")
            .splice(dffChunk("FS  ", Builder().u32be(sampleRate.toLong()).toBytes()))
            .splice(dffChunk("CHNL", Builder().u16be(channels).zeros(2).toBytes()))
            .toBytes()
        val prop = dffChunk("PROP", snd)
        return Builder()
            .ascii("FRM8").u64be((4 + fver.size + prop.size).toLong())
            .ascii("DSD ").splice(fver).splice(prop)
            .toBytes()
    }

    private fun dffChunk(id: String, body: ByteArray): ByteArray = Builder()
        .ascii(id).u64be(body.size.toLong()).splice(body)
        .toBytes()

    private fun alacFile(bitDepth: Int, channels: Int, sampleRate: Int): ByteArray {
        val cookie = Builder()
            .u32be(36).ascii("alac").u32be(0)
            .u32be(4096)
            .u8(0).u8(bitDepth).u8(40).u8(10).u8(14).u8(channels)
            .u16be(255).u32be(0).u32be(0).u32be(sampleRate.toLong())
            .toBytes()
        val sampleEntry = box(
            "alac",
            Builder()
                .zeros(6).u16be(1)
                .u16be(0).u16be(0).u32be(0)
                .u16be(channels).u16be(16).u16be(0).u16be(0)
                .u32be(sampleRate.toLong() shl 16)
                .splice(cookie)
                .toBytes(),
        )
        val stsd = box("stsd", Builder().u32be(0).u32be(1).splice(sampleEntry).toBytes())
        val stbl = box("stbl", stsd)
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val trak = box("trak", mdia)
        val moov = box("moov", trak)
        val ftyp = box("ftyp", Builder().ascii("M4A ").u32be(0).ascii("M4A ").toBytes())
        return ftyp + moov
    }

    private fun box(type: String, body: ByteArray): ByteArray = Builder()
        .u32be((8 + body.size).toLong()).ascii(type).splice(body)
        .toBytes()

    private companion object {
        // 声明尺寸远大于头窗，用于验证据尺寸外推后置 moov 起点
        const val MDAT_DECLARED_BYTES = 100_000L
    }
}
