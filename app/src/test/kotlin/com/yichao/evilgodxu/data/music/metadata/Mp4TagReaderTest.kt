package com.yichao.evilgodxu.data.music.metadata

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 大封面顶出窗口后仍须读到字段 */
class Mp4TagReaderTest {

    @Test
    fun `封面大于头窗口之后仍能读到字段`() {
        // 封面 1MB，标签原子排在其后
        val source = MemorySource(
            mp4(
                "covr" to dataAtom(ByteArray(COVER_BYTES), kind = 13),
                "©nam" to dataAtom("标题".toByteArray(StandardCharsets.UTF_8)),
                "©ART" to dataAtom("艺术家".toByteArray(StandardCharsets.UTF_8)),
                "©alb" to dataAtom("专辑".toByteArray(StandardCharsets.UTF_8)),
                "trkn" to dataAtom(byteArrayOf(0, 0, 0, 7, 0, 12, 0, 0)),
            ),
        )
        val fields = Mp4TagReader.readFields(source.size, source::read)

        assertNotNull("大封面之后的标签读不到", fields)
        assertEquals("标题", fields!!.get(TagField.TITLE))
        assertEquals("艺术家", fields.get(TagField.ARTIST))
        assertEquals("专辑", fields.get(TagField.ALBUM))
        assertEquals("7/12", fields.get(TagField.TRACK_NUMBER))
        // 封面只读盒头，读取量极小
        assertTrue(
            "读取量为 ${source.totalRead}B，封面(${COVER_BYTES}B)被整段读进内存了",
            source.totalRead < HEADER_WINDOW_BYTES,
        )
    }

    @Test
    fun `自由格式原子不靠自身原子名携带字段`() {
        val source = MemorySource(
            mp4("----" to freeformAtom("REPLAYGAIN_TRACK_GAIN", "-3.14 dB")),
        )
        assertEquals("-3.14 dB", Mp4TagReader.readFields(source.size, source::read)?.get(TagField.REPLAY_GAIN_TRACK_GAIN))
    }

    @Test
    fun `写入的自由格式值读回时不含控制字节`() {
        // data 子原子只一层，多套会多读 8 字节
        val source = java.io.File("src/test/resources/audio/sample.m4a").readBytes()
        val fields = TrackTagFields.build { put(TagField.REPLAY_GAIN_TRACK_GAIN, "-7.2 dB") }
        val rewritten = MusicMetadataWriter.writeMetadataBytes(source, fields, null)!!
        val read = Mp4TagReader.readFields(rewritten.size.toLong()) { offset, count ->
            rewritten.copyOfRange(offset.toInt(), (offset + count).toInt())
        }
        assertEquals("-7.2 dB", read?.get(TagField.REPLAY_GAIN_TRACK_GAIN))
    }

    @Test
    fun `非标准原子名被忽略`() {
        // 认不出的原子名不给推断值
        val source = MemorySource(
            mp4(
                "soar" to dataAtom("排序艺术家".toByteArray(StandardCharsets.UTF_8)),
                "  " to dataAtom("无关".toByteArray(StandardCharsets.UTF_8)),
            ),
        )
        assertNull(Mp4TagReader.readFields(source.size, source::read))
    }

    @Test
    fun `结构缺失时不产出字段`() {
        // 缺 moov/udta/meta/ilst 不产出字段
        val flat = box("ftyp", ByteArray(16)) + box("©nam", dataAtom("标题".toByteArray(StandardCharsets.UTF_8)))
        assertNull(Mp4TagReader.readFields(flat.size.toLong()) { offset, count ->
            flat.copyOfRange(offset.toInt(), (offset + count).toInt())
        })
    }

    @Test
    fun `大盒头可被跟随`() {
        // 64 位长度盒不得中断读取
        val meta = box("meta", ByteArray(4) + box("ilst", box("©nam", dataAtom("大长度".toByteArray(StandardCharsets.UTF_8)))))
        val moov = box("moov", box("udta", meta))
        val bytes = ftyp() + largeBox("free", ByteArray(1024)) + moov
        val fields = Mp4TagReader.readFields(bytes.size.toLong()) { offset, count ->
            bytes.copyOfRange(offset.toInt(), (offset + count).toInt())
        }
        assertEquals("大长度", fields?.get(TagField.TITLE))
    }

    // 内存字节源，记录读取量与最长触及位置
    private class MemorySource(private val bytes: ByteArray) {
        var totalRead = 0
        val size: Long = bytes.size.toLong()

        fun read(offset: Long, count: Int): ByteArray? {
            if (offset < 0L || count <= 0 || offset + count > bytes.size) return null
            totalRead += count
            return bytes.copyOfRange(offset.toInt(), (offset + count).toInt())
        }
    }

    // 合成样本构造

    // 嵌套 ftyp→moov→udta→meta→ilst
    private fun mp4(vararg atoms: Pair<String, ByteArray>): ByteArray {
        val ilst = box("ilst", atoms.fold(ByteArray(0)) { acc, (type, body) -> acc + box(type, body) })
        // meta 前有 4 字节版本与标志
        val meta = box("meta", ByteArray(4) + box("hdlr", ByteArray(0)) + ilst)
        val moov = box("moov", box("mvhd", ByteArray(8)) + box("udta", meta))
        return ftyp() + moov
    }

    private fun ftyp(): ByteArray =
        box("ftyp", "M4A ".toByteArray(StandardCharsets.ISO_8859_1) + ByteArray(16))

    private fun box(type: String, body: ByteArray): ByteArray =
        int32BE(body.size + 8) + type.toByteArray(StandardCharsets.ISO_8859_1) + body

    // 长度写 1，真实长度在其后 8 字节
    private fun largeBox(type: String, body: ByteArray): ByteArray =
        int32BE(1) + type.toByteArray(StandardCharsets.ISO_8859_1) +
            longBE(body.size + 16L) + body

    // data：16 字节头 + 载荷
    private fun dataAtom(payload: ByteArray, kind: Int = 1): ByteArray {
        val body = int32BE(kind) + ByteArray(4) + payload
        return int32BE(body.size + 8) + "data".toByteArray(StandardCharsets.ISO_8859_1) + body
    }

    // 自由格式：mean + name + data
    private fun freeformAtom(name: String, value: String): ByteArray {
        val mean = box("mean", ByteArray(4) + "com.apple.iTunes".toByteArray(StandardCharsets.UTF_8))
        val nameAtom = box("name", ByteArray(4) + name.toByteArray(StandardCharsets.UTF_8))
        val data = dataAtom(value.toByteArray(StandardCharsets.UTF_8))
        return mean + nameAtom + data
    }

    private fun int32BE(value: Int): ByteArray =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun longBE(value: Long): ByteArray = ByteArray(8) { index ->
        (value shr (56 - index * 8)).toByte()
    }

    private companion object {
        // 旧头窗 512KB，封面取 1MB 超过
        const val HEADER_WINDOW_BYTES = 512 * 1024
        const val COVER_BYTES = 1024 * 1024
    }
}
