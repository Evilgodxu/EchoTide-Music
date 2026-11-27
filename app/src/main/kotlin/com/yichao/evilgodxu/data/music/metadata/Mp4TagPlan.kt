package com.yichao.evilgodxu.data.music.metadata

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

// MP4 顶层盒子头：长度 4 + 标识 4
private const val MP4_BOX_HEADER_BYTES = 8

// MP4 顶层盒子：只读头，数据区跳过
private class Mp4TopBox(val type: String, val offset: Long, val size: Long)

/** MP4 重写：moov 重建，样本取字节多的一侧搬运 */
internal object Mp4TagPlan {

    fun plan(
        source: TagSource,
        fields: TrackTagFields,
        cover: ByteArray?,
        removeCover: Boolean,
    ): RewritePlan? {
        val boxes = readMp4TopBoxes(source) ?: return null
        val moov = boxes.firstOrNull { it.type == "moov" } ?: return null
        val moovBytes = readParseWindow(source, moov.offset, moov.size) ?: return null
        val parsed = Mp4Atom.parseAll(moovBytes)?.singleOrNull()?.takeIf { it.type == "moov" } ?: return null
        val originalSize = parsed.build().size
        parsed.replaceMetadata(fields, cover, removeCover)
        val sizeDelta = parsed.build().size - originalSize

        val before = boxes.filter { it.offset < moov.offset }
        val after = boxes.filter { it.offset > moov.offset }
        val beforeBytes = before.sumOf { it.size }
        val afterBytes = after.sumOf { it.size }
        val streamAfter = afterBytes >= beforeBytes
        // moov 之后的样本随尺寸差平移，之前的不动
        if (sizeDelta != 0 &&
            !parsed.adjustChunkOffsets(sizeDelta, moov.offset + moov.size)
        ) return null
        val rebuiltMoov = parsed.build()

        val head = ByteArrayOutputStream()
        if (streamAfter) {
            // 前导小盒 + 重建 moov，其后原样搬运
            // 前导盒超上限即放弃，多为多段 mdat 或异常容器
            if (beforeBytes > MAX_IN_MEMORY_BYTES) return null
            before.forEach { source.copyRange(it.offset, it.offset + it.size, head) }
            head.write(rebuiltMoov)
            return RewritePlan(head.toByteArray(), moov.offset + moov.size, source.size, ByteArray(0))
        }
        // moov 在样本后：前导写出，样本搬运，moov 追尾
        val streamStart = before.firstOrNull { it.size > MAX_IN_MEMORY_BYTES }?.offset ?: moov.offset
        val lead = before.filter { it.offset < streamStart }
        val tailBytes = afterBytes + moov.size
        // 字面重建部分超上限即放弃
        if (tailBytes > MAX_IN_MEMORY_BYTES || lead.sumOf { it.size } > MAX_IN_MEMORY_BYTES) return null
        lead.forEach { source.copyRange(it.offset, it.offset + it.size, head) }
        val tail = ByteArrayOutputStream()
        tail.write(rebuiltMoov)
        after.forEach { source.copyRange(it.offset, it.offset + it.size, tail) }
        return RewritePlan(head.toByteArray(), streamStart, moov.offset, tail.toByteArray())
    }

    // 顶层盒子表；长度非法或未覆盖全文即放弃
    private fun readMp4TopBoxes(source: TagSource): List<Mp4TopBox>? {
        val boxes = mutableListOf<Mp4TopBox>()
        var offset = 0L
        while (offset < source.size) {
            val header = source.readAt(offset, MP4_BOX_HEADER_BYTES) ?: return null
            if (header.size < MP4_BOX_HEADER_BYTES) return null
            val size = int32(header, 0).toLong() and 0xffffffffL
            // 长度 1(64 位) 或 0(到末尾) 不解析
            if (size < MP4_BOX_HEADER_BYTES) return null
            boxes += Mp4TopBox(String(header, 4, 4, StandardCharsets.ISO_8859_1), offset, size)
            offset += size
        }
        return boxes.takeIf { it.isNotEmpty() && offset == source.size }
    }

    private data class Mp4Atom(
        val type: String,
        var data: ByteArray,
        val children: MutableList<Mp4Atom>?,
        // 顶层 atom 起始偏移，供 mdat 流式复制
        val offset: Int = 0,
    ) {
        fun build(): ByteArray {
            val body = if (children != null) data + children.joinToByteArray() else data
            return intBytes(body.size + 8) + type.toByteArray(StandardCharsets.ISO_8859_1) + body
        }

        fun replaceMetadata(fields: TrackTagFields, cover: ByteArray?, removeCover: Boolean): Mp4Atom {
            if (type == "moov") {
                val udta = children?.firstOrNull { it.type == "udta" }
                if (udta != null) udta.replaceMetadata(fields, cover, removeCover) else children?.add(Mp4Atom("udta", ByteArray(0), mutableListOf(metaAtom(fields, cover))))
            } else if (type == "udta") {
                val meta = children?.firstOrNull { it.type == "meta" }
                if (meta != null) meta.replaceMetadata(fields, cover, removeCover) else children?.add(metaAtom(fields, cover))
            } else if (type == "meta") {
                if (children?.none { it.type == "hdlr" } == true) {
                    children.add(0, hdlrAtom())
                }
                val ilst = children?.firstOrNull { it.type == "ilst" }
                if (ilst != null) {
                    ilst.replaceItems(fields, cover, removeCover)
                } else {
                    children?.add(ilstAtom(fields, cover))
                }
            }
            return this
        }

        /** 平移其后 chunk 偏移；任一越界则整表放弃 */
        fun adjustChunkOffsets(delta: Int, minOffset: Long): Boolean {
            if (type == "stco") {
                if (data.size < 8) return false
                val count = int32(data, 4)
                if (count < 0 || 8L + count.toLong() * 4L > data.size.toLong()) return false
                run {
                    val adjusted = LongArray(count)
                    for (index in 0 until count) {
                        val raw = int32(data, 8 + index * 4).toLong() and 0xffffffffL
                        if (raw < minOffset) {
                            adjusted[index] = raw
                            continue
                        }
                        val next = raw + delta
                        if (next !in 0..0xffffffffL) return false
                        adjusted[index] = next
                    }
                    for (index in 0 until count) {
                        writeInt32(data, 8 + index * 4, adjusted[index].toInt())
                    }
                }
            } else if (type == "co64") {
                if (data.size < 8) return false
                val count = int32(data, 4)
                if (count < 0 || 8L + count.toLong() * 8L > data.size.toLong()) return false
                run {
                    val adjusted = LongArray(count)
                    for (index in 0 until count) {
                        val raw = long64(data, 8 + index * 8)
                        if (raw < minOffset) {
                            adjusted[index] = raw
                            continue
                        }
                        val next = raw + delta
                        if (next < 0) return false
                        adjusted[index] = next
                    }
                    for (index in 0 until count) {
                        writeLong64(data, 8 + index * 8, adjusted[index])
                    }
                }
            }
            return children?.all { it.adjustChunkOffsets(delta, minOffset) } ?: true
        }

        // ©lyr 非容器；整块丢弃重建
        private fun replaceItems(fields: TrackTagFields, cover: ByteArray?, removeCover: Boolean) {
            val replacedTypes = mutableSetOf<String>()
            val replacedFreeform = mutableSetOf<String>()
            fields.entries().forEach { (field, _) ->
                Mp4TagSchema.ATOMS[field]?.let { replacedTypes += it }
                Mp4TagSchema.FREEFORM_KEYS[field]?.let { replacedFreeform += it }
            }
            val mp4Cover = cover?.let { toMp4Cover(it) }
            // 删除封面时也要剔除既有 covr
            if (mp4Cover != null || removeCover) replacedTypes += "covr"
            val kept = children.orEmpty().filterNot { child ->
                child.type in replacedTypes ||
                    (child.type == Mp4TagSchema.FREE_FORM_ATOM && child.freeformName() in replacedFreeform)
            }.toMutableList()
            fields.entries().forEach { (field, value) ->
                Mp4TagSchema.ATOMS[field]?.let { kept.add(mp4FieldAtom(it, field, value)) }
                Mp4TagSchema.FREEFORM_KEYS[field]?.let { kept.add(freeformAtom(it, value)) }
            }
            if (mp4Cover != null) kept.add(dataAtom("covr", mp4Cover.first, mp4Cover.second))
            children?.clear(); children?.addAll(kept)
        }

        /** 自由格式原子的 name 键名；非自由格式或异常为 null */
        fun freeformName(): String? {
            if (type != Mp4TagSchema.FREE_FORM_ATOM) return null
            var p = 0
            while (p + MP4_BOX_HEADER_BYTES <= data.size) {
                val size = int32(data, p)
                if (size < MP4_BOX_HEADER_BYTES || p + size > data.size) return null
                val childType = String(data, p + 4, 4, StandardCharsets.ISO_8859_1)
                if (childType == "name") {
                    if (size <= MP4_BOX_HEADER_BYTES + 4) return null
                    return String(data, p + MP4_BOX_HEADER_BYTES + 4, size - MP4_BOX_HEADER_BYTES - 4, StandardCharsets.UTF_8)
                }
                p += size
            }
            return null
        }

        companion object {
            fun parseAll(bytes: ByteArray): MutableList<Mp4Atom>? {
                val result = mutableListOf<Mp4Atom>(); var p = 0
                while (p + 8 <= bytes.size) { val atom = parse(bytes, p, bytes.size, p) ?: return null; result += atom; val size = int32(bytes, p); if (size < 8) return null; p += size }
                return if (p == bytes.size) result else null
            }
            private fun parse(bytes: ByteArray, start: Int, end: Int, offset: Int): Mp4Atom? {
                if (start + 8 > end) return null
                val size = int32(bytes, start); if (size < 8 || start + size > end) return null
                val type = String(bytes, start + 4, 4, StandardCharsets.ISO_8859_1); val payloadStart = start + 8; val payloadEnd = start + size
                // trak/mdia/minf/stbl 逐层按容器解析
                val container = type == "moov" || type == "trak" || type == "mdia" || type == "minf" || type == "stbl" ||
                    type == "udta" || type == "meta" || type == "ilst" || type == "©nam" || type == "©ART" || type == "©alb" || type == "covr"
                if (!container) return Mp4Atom(type, bytes.copyOfRange(payloadStart, payloadEnd), null, offset)
                val head = if (type == "meta") 4 else 0; val children = mutableListOf<Mp4Atom>(); var p = payloadStart + head
                while (p + 8 <= payloadEnd) { val child = parse(bytes, p, payloadEnd, p) ?: return null; children += child; val childSize = int32(bytes, p); if (childSize < 8) return null; p += childSize }
                if (p != payloadEnd) return null
                return Mp4Atom(type, bytes.copyOfRange(payloadStart, payloadStart + head), children, offset)
            }
            // 新建 ilst 无需删除封面
            private fun metaAtom(fields: TrackTagFields, cover: ByteArray?) =
                Mp4Atom("meta", byteArrayOf(0, 0, 0, 0), mutableListOf(hdlrAtom(), ilstAtom(fields, cover)))

            // meta 需 hdlr 为 mdir 才算元数据
            private fun hdlrAtom(): Mp4Atom {
                val data = ByteArrayOutputStream()
                data.write(byteArrayOf(0, 0, 0, 0))
                data.write(byteArrayOf(0, 0, 0, 0))
                data.write("mdir".toByteArray(StandardCharsets.ISO_8859_1))
                data.write(ByteArray(12))
                data.write(0)
                return Mp4Atom("hdlr", data.toByteArray(), null)
            }
            private fun ilstAtom(fields: TrackTagFields, cover: ByteArray?) = Mp4Atom("ilst", ByteArray(0), buildList {
                fields.entries().forEach { (field, value) ->
                    Mp4TagSchema.ATOMS[field]?.let { add(mp4FieldAtom(it, field, value)) }
                    Mp4TagSchema.FREEFORM_KEYS[field]?.let { add(freeformAtom(it, value)) }
                }
                cover?.let { toMp4Cover(it) }?.let { add(dataAtom("covr", it.first, it.second)) }
            }.toMutableList())

            // 音轨号/碟号为 8 字节二进制，其余为 UTF-8
            private fun mp4FieldAtom(type: String, field: TagField, value: String): Mp4Atom =
                if (field == TagField.TRACK_NUMBER || field == TagField.DISC_NUMBER) {
                    dataAtom(type, trackNumberBytes(value), 0)
                } else {
                    dataAtom(type, value.toByteArray(StandardCharsets.UTF_8))
                }

            // 音轨号：保留2 + 编号2 + 总数2 + 保留2
            private fun trackNumberBytes(value: String): ByteArray {
                val number = value.substringBefore('/').trim().toIntOrNull() ?: 0
                val total = value.substringAfter('/', "").trim().toIntOrNull() ?: 0
                return byteArrayOf(0, 0, (number shr 8).toByte(), number.toByte(), (total shr 8).toByte(), total.toByte(), 0, 0)
            }

            // 自由格式：mean、name、data 子原子
            private fun freeformAtom(name: String, value: String): Mp4Atom = Mp4Atom(
                Mp4TagSchema.FREE_FORM_ATOM,
                ByteArray(0),
                mutableListOf(
                    Mp4Atom("mean", intBytes(0) + Mp4TagSchema.FREEFORM_MEAN.toByteArray(StandardCharsets.UTF_8), null),
                    Mp4Atom("name", intBytes(0) + name.toByteArray(StandardCharsets.UTF_8), null),
                    // data 只承载 type+locale+值
                    Mp4Atom("data", intBytes(1) + byteArrayOf(0, 0, 0, 0) + value.toByteArray(StandardCharsets.UTF_8), null),
                ),
            )

            // data：type4 + locale4 + 数据
            private fun dataAtom(type: String, value: ByteArray, kind: Int = 1) =
                Mp4Atom(type, ByteArray(0), mutableListOf(Mp4Atom("data", intBytes(kind) + byteArrayOf(0, 0, 0, 0) + value, null)))
        }
    }

    // covr：JPEG(13)/PNG(14)，其余转码
    private fun toMp4Cover(cover: ByteArray): Pair<ByteArray, Int>? {
        if (cover.size >= 3 && cover[0] == 0xff.toByte() && cover[1] == 0xd8.toByte() && cover[2] == 0xff.toByte()) return cover to 13
        if (cover.size >= 8 && cover.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))) return cover to 14
        return try {
            val bitmap = BitmapFactory.decodeByteArray(cover, 0, cover.size) ?: return null
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)) return null
            bitmap.recycle()
            out.toByteArray() to 13
        } catch (e: Exception) {
            CrashLogManager.logException("Mp4TagPlan", "封面转 JPEG 失败", e)
            null
        }
    }

    private fun List<Mp4Atom>.joinToByteArray(): ByteArray {
        val out = ByteArrayOutputStream()
        forEach { out.write(it.build()) }
        return out.toByteArray()
    }
}
