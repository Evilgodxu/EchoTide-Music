package com.yichao.evilgodxu.data.music.metadata

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yichao.evilgodxu.log.CrashLogManager
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

// MP4 顶层盒子的长度字段宽度与盒子标识字段宽度
private const val MP4_BOX_HEADER_BYTES = 8

// MP4 顶层盒子：解析只读盒子头，数据区按长度跳过
private class Mp4TopBox(val type: String, val offset: Long, val size: Long)

/**
 * M4A/MP4 标签重写：顶层盒子表按盒子头逐个定位，moov 盒整体读入重建，样本数据按一条连续区间流式搬运。
 *
 * 需要重建的只有 moov，故把被它分开的两侧中**字节更多的一侧**作为搬运区间，另一侧（只含
 * ftyp/free 这类小盒子）按字面字节重建。判据必须按「合计」而不是「单个盒子」：
 * DASH 式分片 MP4 的尾部是成百上千组成对的 moof+mdat，每块都不大，合计却可达数百 MB，
 * 按整段读进内存会把进程推到系统内存回收线以下（被系统杀死且没有崩溃日志）。
 *
 * moov 在搬运区间之前时样本数据整体后移，chunk 偏移随 moov 尺寸变化同步修正。
 */
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
        // 重建后的 moov 始终落在原 moov 起点，只有位于它之后的样本会随尺寸差整体平移；
        // 位于 moov 之前的样本按原偏移字面复制，不能一起改
        if (sizeDelta != 0 &&
            !parsed.adjustChunkOffsets(sizeDelta, moov.offset + moov.size)
        ) return null
        val rebuiltMoov = parsed.build()

        val head = ByteArrayOutputStream()
        if (streamAfter) {
            // 前导小盒子 + 重建的 moov，其后全部原样搬运
            // moov 之前的盒子须按字面重建，超过上限即放弃：多为多段 mdat 或结构异常的容器
            if (beforeBytes > MAX_IN_MEMORY_BYTES) return null
            before.forEach { source.copyRange(it.offset, it.offset + it.size, head) }
            head.write(rebuiltMoov)
            return RewritePlan(head.toByteArray(), moov.offset + moov.size, source.size, ByteArray(0))
        }
        // moov 在样本数据之后：前导小盒子按字面写出，样本整段搬运，重建的 moov 追在末尾
        val streamStart = before.firstOrNull { it.size > MAX_IN_MEMORY_BYTES }?.offset ?: moov.offset
        val lead = before.filter { it.offset < streamStart }
        val tailBytes = afterBytes + moov.size
        // moov 周围须按字面重建的部分超过上限即放弃：多为多段 mdat 或结构异常的容器
        if (tailBytes > MAX_IN_MEMORY_BYTES || lead.sumOf { it.size } > MAX_IN_MEMORY_BYTES) return null
        lead.forEach { source.copyRange(it.offset, it.offset + it.size, head) }
        val tail = ByteArrayOutputStream()
        tail.write(rebuiltMoov)
        after.forEach { source.copyRange(it.offset, it.offset + it.size, tail) }
        return RewritePlan(head.toByteArray(), streamStart, moov.offset, tail.toByteArray())
    }

    // 顶层盒子表：逐个读盒子头并按声明的长度定位下一个，数据区不进入内存。
    // 长度字段不合法（含 64 位长度与「延伸到文件末尾」）或未能恰好覆盖全文时放弃解析，不做截断猜测
    private fun readMp4TopBoxes(source: TagSource): List<Mp4TopBox>? {
        val boxes = mutableListOf<Mp4TopBox>()
        var offset = 0L
        while (offset < source.size) {
            val header = source.readAt(offset, MP4_BOX_HEADER_BYTES) ?: return null
            if (header.size < MP4_BOX_HEADER_BYTES) return null
            val size = int32(header, 0).toLong() and 0xffffffffL
            // 长度字段为 1（64 位长度）或 0（延续到文件末尾）不解析
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
        // 顶层 atom 在文件中的起始偏移，供 mdat 流式复制定位
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

        /**
         * 平移位于 [minOffset, 文件末尾) 的 chunk 偏移；返回 false 表示整张表无法在不越界的前提下完成修正。
         *
         * stco 的 32 位偏移按无符号解释，越过 0x7FFFFFFF 的合法值不能先变成负数。
         * 先算完整张表再落笔：任何一项越界都整体放弃，避免表里一半是新地址、一半是旧地址。
         */
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

        // ©lyr 为歌词原子；此处不把它列入解析时的容器类型，避免历史文件中格式异常的
        // ©lyr 让整个 atom 树解析失败（进而导致封面/标题写入整体失败），按整块丢弃重建即可
        private fun replaceItems(fields: TrackTagFields, cover: ByteArray?, removeCover: Boolean) {
            val replacedTypes = mutableSetOf<String>()
            val replacedFreeform = mutableSetOf<String>()
            fields.entries().forEach { (field, _) ->
                Mp4TagSchema.ATOMS[field]?.let { replacedTypes += it }
                Mp4TagSchema.FREEFORM_KEYS[field]?.let { replacedFreeform += it }
            }
            val mp4Cover = cover?.let { toMp4Cover(it) }
            // 删除封面同样要剔除既有 covr 原子，否则它会被当作「未涉及」原样保留
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

        /** 自由格式原子的键名：---- 原子的 name 子原子内容；非自由格式或结构异常时为 null */
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
                // trak/mdia/minf/stbl 按容器解析，调整 stco/co64 时才能遍历到内部的 chunk 偏移
                val container = type == "moov" || type == "trak" || type == "mdia" || type == "minf" || type == "stbl" ||
                    type == "udta" || type == "meta" || type == "ilst" || type == "©nam" || type == "©ART" || type == "©alb" || type == "covr"
                if (!container) return Mp4Atom(type, bytes.copyOfRange(payloadStart, payloadEnd), null, offset)
                val head = if (type == "meta") 4 else 0; val children = mutableListOf<Mp4Atom>(); var p = payloadStart + head
                while (p + 8 <= payloadEnd) { val child = parse(bytes, p, payloadEnd, p) ?: return null; children += child; val childSize = int32(bytes, p); if (childSize < 8) return null; p += childSize }
                if (p != payloadEnd) return null
                return Mp4Atom(type, bytes.copyOfRange(payloadStart, payloadStart + head), children, offset)
            }
            // 新建 ilst 时无需「删除封面」：容器里本就没有既有封面可删
            private fun metaAtom(fields: TrackTagFields, cover: ByteArray?) =
                Mp4Atom("meta", byteArrayOf(0, 0, 0, 0), mutableListOf(hdlrAtom(), ilstAtom(fields, cover)))

            // meta 需要 hdlr（handler_type=mdir）才被识别为 iTunes 风格元数据
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

            // 普通字段原子：音轨号/碟号是 8 字节二进制对（编号 + 总数），其余是 UTF-8 文本
            private fun mp4FieldAtom(type: String, field: TagField, value: String): Mp4Atom =
                if (field == TagField.TRACK_NUMBER || field == TagField.DISC_NUMBER) {
                    dataAtom(type, trackNumberBytes(value), 0)
                } else {
                    dataAtom(type, value.toByteArray(StandardCharsets.UTF_8))
                }

            // 音轨号/碟号的二进制载荷：2 字节保留 + 编号(2) + 总数(2) + 2 字节保留
            private fun trackNumberBytes(value: String): ByteArray {
                val number = value.substringBefore('/').trim().toIntOrNull() ?: 0
                val total = value.substringAfter('/', "").trim().toIntOrNull() ?: 0
                return byteArrayOf(0, 0, (number shr 8).toByte(), number.toByte(), (total shr 8).toByte(), total.toByte(), 0, 0)
            }

            // 自由格式原子：---- 原子内以 mean(命名空间) + name(键名) + data(值) 三个子原子承载
            private fun freeformAtom(name: String, value: String): Mp4Atom = Mp4Atom(
                Mp4TagSchema.FREE_FORM_ATOM,
                ByteArray(0),
                mutableListOf(
                    Mp4Atom("mean", intBytes(0) + Mp4TagSchema.FREEFORM_MEAN.toByteArray(StandardCharsets.UTF_8), null),
                    Mp4Atom("name", intBytes(0) + name.toByteArray(StandardCharsets.UTF_8), null),
                    // data 子原子本身只承载 type + locale + 值，不能再包一层 dataAtom：
                    // 多套一层会让读取端按标准载荷取到多出的八个控制字节
                    Mp4Atom("data", intBytes(1) + byteArrayOf(0, 0, 0, 0) + value.toByteArray(StandardCharsets.UTF_8), null),
                ),
            )

            // data atom 布局：type(4字节，1=文本/13=JPEG/14=PNG) + locale(4字节全0) + 数据
            private fun dataAtom(type: String, value: ByteArray, kind: Int = 1) =
                Mp4Atom(type, ByteArray(0), mutableListOf(Mp4Atom("data", intBytes(kind) + byteArrayOf(0, 0, 0, 0) + value, null)))
        }
    }

    // M4A 的 covr 只支持 JPEG(13)/PNG(14)：其余格式（WEBP/HEIC/BMP 等）解码后转成 JPEG 再内嵌，
    // 否则播放器按声明的 JPEG 解码真实数据会失败导致封面空白
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
