package com.yichao.evilgodxu.data.music.metadata

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M4A data 原子载荷须减去 16 字节头 */
class MusicEmbeddedLyricReaderTest {

    private val lyrics = "[00:01.00]第一行\n[00:03.50]第二行"

    @Test
    fun `MP4 歌词原子可解码`() {
        // 22KB 超过小块窗口，覆盖直读分支
        val payload = lyrics + " ".repeat(DATA_ATOM_PAYLOAD_BYTES - lyrics.toByteArray(StandardCharsets.UTF_8).size)
        assertEquals(payload, MusicEmbeddedLyricReader.extractLyrics(mp4("©lyr" to dataAtom(payload))))
    }

    @Test
    fun `数据原子过短的 MP4 歌词被拒绝`() {
        // 声明 8 字节视为损坏，不产出脏数据
        val shortData = int32BE(8) + "data".toByteArray(StandardCharsets.ISO_8859_1)
        assertNull(MusicEmbeddedLyricReader.extractLyrics(mp4("©lyr" to shortData)))
    }

    @Test
    fun `封面大于头窗口之后的 MP4 歌词仍能找到`() {
        // 大封面排在歌词前，头窗解析会读空
        val bytes = mp4(
            "covr" to dataAtom(ByteArray(COVER_BYTES), kind = 13),
            "©lyr" to dataAtom(lyrics),
        )
        assertEquals(lyrics, MusicEmbeddedLyricReader.extractLyrics(bytes))
    }

    @Test
    fun `FLAC 的 Vorbis 注释歌词可解码`() {        val comment = ByteArrayOutputStream()
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
    fun `编码样本里的 Ogg Vorbis 歌词可解码`() {
        // 注释头包类型 3 + vorbis，无字面名
        val bytes = File("src/test/resources/audio/sample.ogg").readBytes()
        assertEquals("[00:01.00]第一行\n[00:03.50]第二行", MusicEmbeddedLyricReader.extractLyrics(bytes))
    }

    // 嵌套 ftyp→moov→udta→meta→ilst
    private fun mp4(vararg atoms: Pair<String, ByteArray>): ByteArray {
        val ilst = box("ilst", atoms.fold(ByteArray(0)) { acc, (type, body) -> acc + box(type, body) })
        // meta 前有 4 字节版本与标志
        val meta = box("meta", ByteArray(4) + box("hdlr", ByteArray(0)) + ilst)
        val moov = box("moov", box("mvhd", ByteArray(8)) + box("udta", meta))
        return box("ftyp", "M4A ".toByteArray(StandardCharsets.ISO_8859_1) + ByteArray(16)) + moov
    }

    private fun box(type: String, body: ByteArray): ByteArray =
        int32BE(body.size + 8) + type.toByteArray(StandardCharsets.ISO_8859_1) + body

    // data：16 字节头 + 载荷
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
        // 封面 1MB，超过旧 512KB 头窗
        const val COVER_BYTES = 1024 * 1024
        // 歌词载荷超 16KB，覆盖直读分支
        const val DATA_ATOM_PAYLOAD_BYTES = 22_344
    }
}
