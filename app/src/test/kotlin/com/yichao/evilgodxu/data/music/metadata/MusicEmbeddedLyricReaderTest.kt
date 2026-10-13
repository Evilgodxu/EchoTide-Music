package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 内嵌歌词容器解析复核：重点覆盖 M4A 的 data 原子解码——歌词原子的长度字段描述的是
 * 「原子自身总长」，载荷长度须再减去原子头 16 字节（类型 4 + 区域设置 4 + 版本与标志 8），
 * 把「偏移 + 长度」当成载荷长度传入会让解码越过缓冲区。
 * 合成样本按 MP4/FLAC 规范排布，不依赖被测代码的写入结果。
 */
class MusicEmbeddedLyricReaderTest {

    private val lyrics = "[00:01.00]第一行\n[00:03.50]第二行"

    @Test
    fun mp4LyricsAtomIsDecoded() {
        // 载荷取 22KB：大于定点读取的小块窗口，顺带覆盖大块直读分支
        val payload = lyrics + " ".repeat(DATA_ATOM_PAYLOAD_BYTES - lyrics.toByteArray(StandardCharsets.UTF_8).size)
        assertEquals(payload, MusicEmbeddedLyricReader.extractLyrics(mp4("©lyr" to dataAtom(payload))))
    }

    @Test
    fun mp4LyricsAtomWithShortDataAtomIsRejected() {
        // data 子原子只声明 8 字节、容纳不下版本与区域设置字段：视为损坏，不得解码出脏数据
        val shortData = int32BE(8) + "data".toByteArray(StandardCharsets.ISO_8859_1)
        assertNull(MusicEmbeddedLyricReader.extractLyrics(mp4("©lyr" to shortData)))
    }

    @Test
    fun mp4LyricsAfterCoverLargerThanHeaderWindowAreFound() {
        // 实测样本的形态：ilst 下 covr 排在 ©lyr 之前且远超头窗（旧实现的 512KB），
        // 按固定头窗解析会因 covr 越窗而把整份标签读空，歌词也就一并丢掉
        val bytes = mp4(
            "covr" to dataAtom(ByteArray(COVER_BYTES), kind = 13),
            "©lyr" to dataAtom(lyrics),
        )
        assertEquals(lyrics, MusicEmbeddedLyricReader.extractLyrics(bytes))
    }

    @Test
    fun flacVorbisCommentLyricsIsDecoded() {        val comment = ByteArrayOutputStream()
        val vendor = "EdgeGesture".toByteArray(StandardCharsets.UTF_8)
        val fields = listOf("TITLE=标题", "LYRICS=$lyrics")
        comment.write(intLE(vendor.size)); comment.write(vendor); comment.write(intLE(fields.size))
        fields.forEach {
            val value = it.toByteArray(StandardCharsets.UTF_8)
            comment.write(intLE(value.size)); comment.write(value)
        }
        val body = comment.toByteArray()
        val length = byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte())
        val block = byteArrayOf(0x84.toByte()) + length + body
        assertEquals(lyrics, MusicEmbeddedLyricReader.extractLyrics("fLaC".toByteArray() + block))
    }

    @Test
    fun oggVorbisLyricsFromEncodedFixtureAreDecoded() {
        // 真实 Ogg Vorbis 样本：注释头包的 LYRICS 字段由生成脚本写入（页 CRC 同步重算）。
        // 注释头包以「包类型 3 + vorbis」起头，不含任何 "vorbis_comment" 字面
        val bytes = File("src/test/resources/audio/sample.ogg").readBytes()
        assertEquals("[00:01.00]第一行\n[00:03.50]第二行", MusicEmbeddedLyricReader.extractLyrics(bytes))
    }

    // 嵌套结构：ftyp → moov → udta → meta → ilst → 各标签原子（与写入端的排布一致）
    private fun mp4(vararg atoms: Pair<String, ByteArray>): ByteArray {
        val ilst = box("ilst", atoms.fold(ByteArray(0)) { acc, (type, body) -> acc + box(type, body) })
        // meta 原子在子原子之前另有 4 字节版本与标志
        val meta = box("meta", ByteArray(4) + box("hdlr", ByteArray(0)) + ilst)
        val moov = box("moov", box("mvhd", ByteArray(8)) + box("udta", meta))
        return box("ftyp", "M4A ".toByteArray(StandardCharsets.ISO_8859_1) + ByteArray(16)) + moov
    }

    private fun box(type: String, body: ByteArray): ByteArray =
        int32BE(body.size + 8) + type.toByteArray(StandardCharsets.ISO_8859_1) + body

    // data 子原子：类型/区域/版本标志 16 字节头 + 载荷
    private fun dataAtom(payload: String, kind: Int = 1): ByteArray =
        dataAtom(payload.toByteArray(StandardCharsets.UTF_8), kind)

    private fun dataAtom(payload: ByteArray, kind: Int = 1): ByteArray {
        val body = int32BE(kind) + ByteArray(4) + payload
        return int32BE(body.size + 8) + "data".toByteArray(StandardCharsets.ISO_8859_1) + body
    }

    private fun int32BE(value: Int): ByteArray =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun intLE(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private companion object {
        // 合成封面取 1MB：务必超过旧实现的 512KB 头窗
        const val COVER_BYTES = 1024 * 1024
        // 歌词载荷长度：大于定点读取的小块窗口（16KB），覆盖大块直读分支
        const val DATA_ATOM_PAYLOAD_BYTES = 22_344
    }
}
