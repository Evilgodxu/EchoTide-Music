package com.yichao.evilgodxu.data.music.metadata

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4A 标签读取复核：重点是大封面把标签原子顶出头窗的情形。
 *
 * 实测样本（两首带 3000×3000 封面的 M4A）都是这个形态：`ilst` 下 `covr` 排在文本原子之前，
 * 封面 1.1MB / 1.8MB，文本原子全部落在 1.2MB / 1.8MB 之后。按固定头窗解析时，遍历遇到超出窗口的
 * 盒子即判结构不完整并返回，于是整份标签一个字段都读不到——编辑页打开是空表单，用户把空值存回去
 * 就等于抹掉文件里的既有标签。
 *
 * 合成样本按 MP4/iTunes 规范排布（ftyp → moov → udta → meta → ilst），不依赖被测写入端。
 */
class Mp4TagReaderTest {

    @Test
    fun fieldsAreReadAfterCoverLargerThanHeaderWindow() {
        // 封面 1MB：超过旧实现的 512KB 头窗，标签原子全部排在其后
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
        // 封面只读盒子头：读取量远小于旧实现的头窗，更远小于封面本身
        assertTrue(
            "读取量为 ${source.totalRead}B，封面(${COVER_BYTES}B)被整段读进内存了",
            source.totalRead < HEADER_WINDOW_BYTES,
        )
    }

    @Test
    fun freeformAtomsCarryFieldsWithoutOwnAtomName() {
        val source = MemorySource(
            mp4("----" to freeformAtom("REPLAYGAIN_TRACK_GAIN", "-3.14 dB")),
        )
        assertEquals("-3.14 dB", Mp4TagReader.readFields(source.size, source::read)?.get(TagField.REPLAY_GAIN_TRACK_GAIN))
    }

    @Test
    fun nonStandardAtomNamesAreIgnored() {
        // 认不出的原子名不给推断值：猜出来的名字比没有更糟
        val source = MemorySource(
            mp4(
                "soar" to dataAtom("排序艺术家".toByteArray(StandardCharsets.UTF_8)),
                "  " to dataAtom("无关".toByteArray(StandardCharsets.UTF_8)),
            ),
        )
        assertNull(Mp4TagReader.readFields(source.size, source::read))
    }

    @Test
    fun missingStructureYieldsNoFields() {
        // 只有标签原子而没有 moov/udta/meta/ilst 的字节串不是 M4A 标签结构
        val flat = box("ftyp", ByteArray(16)) + box("©nam", dataAtom("标题".toByteArray(StandardCharsets.UTF_8)))
        assertNull(Mp4TagReader.readFields(flat.size.toLong()) { offset, count ->
            flat.copyOfRange(offset.toInt(), (offset + count).toInt())
        })
    }

    @Test
    fun largeBoxHeaderIsFollowed() {
        // 64 位长度字段（size==1）的盒子：读取不得因它而中断
        val meta = box("meta", ByteArray(4) + box("ilst", box("©nam", dataAtom("大长度".toByteArray(StandardCharsets.UTF_8)))))
        val moov = box("moov", box("udta", meta))
        val bytes = ftyp() + largeBox("free", ByteArray(1024)) + moov
        val fields = Mp4TagReader.readFields(bytes.size.toLong()) { offset, count ->
            bytes.copyOfRange(offset.toInt(), (offset + count).toInt())
        }
        assertEquals("大长度", fields?.get(TagField.TITLE))
    }

    // 内存字节源：记录读取量与最长触及位置，用于核对「封面没有被读进内存」
    private class MemorySource(private val bytes: ByteArray) {
        var totalRead = 0
        val size: Long = bytes.size.toLong()

        fun read(offset: Long, count: Int): ByteArray? {
            if (offset < 0L || count <= 0 || offset + count > bytes.size) return null
            totalRead += count
            return bytes.copyOfRange(offset.toInt(), (offset + count).toInt())
        }
    }

    // ---- 合成样本构造 ----

    // 嵌套结构：ftyp → moov → udta → meta → ilst → 各标签原子（与写入端的排布一致）
    private fun mp4(vararg atoms: Pair<String, ByteArray>): ByteArray {
        val ilst = box("ilst", atoms.fold(ByteArray(0)) { acc, (type, body) -> acc + box(type, body) })
        // meta 原子在子原子之前另有 4 字节版本与标志
        val meta = box("meta", ByteArray(4) + box("hdlr", ByteArray(0)) + ilst)
        val moov = box("moov", box("mvhd", ByteArray(8)) + box("udta", meta))
        return ftyp() + moov
    }

    private fun ftyp(): ByteArray =
        box("ftyp", "M4A ".toByteArray(StandardCharsets.ISO_8859_1) + ByteArray(16))

    private fun box(type: String, body: ByteArray): ByteArray =
        int32BE(body.size + 8) + type.toByteArray(StandardCharsets.ISO_8859_1) + body

    // 大尺寸盒子：长度字段写 1，真实长度放在其后 8 字节
    private fun largeBox(type: String, body: ByteArray): ByteArray =
        int32BE(1) + type.toByteArray(StandardCharsets.ISO_8859_1) +
            longBE(body.size + 16L) + body

    // data 子原子：类型/区域/版本标志 16 字节头 + 载荷
    private fun dataAtom(payload: ByteArray, kind: Int = 1): ByteArray {
        val body = int32BE(kind) + ByteArray(4) + payload
        return int32BE(body.size + 8) + "data".toByteArray(StandardCharsets.ISO_8859_1) + body
    }

    // ---- 自由格式原子：mean + name + data 三个子原子
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
        // 旧实现的头窗：合成封面取 1MB，务必超过它
        const val HEADER_WINDOW_BYTES = 512 * 1024
        const val COVER_BYTES = 1024 * 1024
    }
}
