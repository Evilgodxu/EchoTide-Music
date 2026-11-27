package com.yichao.evilgodxu.data.music.analysis

// 容器头规格：采样率、位深与声道数
// 有损编码的位深只是名义值，故可空
internal data class ContainerFormat(
    val sampleRate: Int,
    val bitDepth: Int?,
    val channels: Int,
    /**
     * 容器头能认定的码流名，认定不出留空
     */
    val codecName: String? = null,
)

// 只读文件头元数据，不依赖平台解码器
// 覆盖 FLAC、WAV/RF64、ALAC
// 先校验魔数与块结构，宁可返回 null
internal object ContainerHeaderParser {

    // 采样率上限：PCM 容器最高到 DXD 量级
    private const val PCM_MAX_SAMPLE_RATE = 768_000

    // MP4 线性 PCM 条目，其 msamplesize 为真
    private val LINEAR_PCM_ENTRY_TYPES = setOf(
        "lpcm", "sowt", "twos", "in24", "in32", "fl32", "fl64", "raw ", "alaw", "ulaw", "ima4",
    )

    // alac 幻数盒成立时，采样描述就是 ALAC
    private const val ALAC_CODEC_NAME = "ALAC"

    // MP4 顶层盒类型，用于判别 ISO-BMFF
    private val MP4_TOP_LEVEL_BOXES = setOf("ftyp", "moov", "free", "mdat", "wide", "skip", "styp")

    // 按魔数解析，windowStart 为 buf 偏移
    fun parse(buf: ByteArray, windowStart: Long, fileLength: Long): ContainerFormat? {
        if (buf.size < 12) return null
        return when {
            buf.matches(0, "fLaC") -> parseFlac(buf, 0)
            buf.matches(0, "RIFF") || buf.matches(0, "RF64") -> parseRiff(buf, 0)
            isMp4Like(buf) -> parseMp4(buf, windowStart, fileLength)
            else -> null
        }
    }

    // ID3v2 总字节数，无标签返回 0
    // FLAC 可能被顶偏，调用方据此重定位
    fun id3v2TagSize(buf: ByteArray): Int {
        if (buf.size < ID3V2_HEADER_BYTES || !buf.matches(0, "ID3")) return 0
        // 版本字节 0xFF 非法，排除正文偶然的 ID3
        if (buf[3].toInt() and 0xFF == 0xFF) return 0
        val size = ((buf[6].toInt() and 0x7F) shl 21) or
            ((buf[7].toInt() and 0x7F) shl 14) or
            ((buf[8].toInt() and 0x7F) shl 7) or
            (buf[9].toInt() and 0x7F)
        val total = ID3V2_HEADER_BYTES + size
        return if (total in ID3V2_HEADER_BYTES..MAX_ID3V2_BYTES) total else 0
    }

    // 探测 MP4 顶层盒偏移，供 moov 后置重读
    fun mp4BoxOffset(buf: ByteArray, windowStart: Long, fileLength: Long, type: String): Long? {
        var p = 0
        while (p + BOX_HEADER_BYTES <= buf.size) {
            val total = topLevelBoxLength(buf, p, windowStart, fileLength) ?: return null
            if (buf.matches(p + 4, type)) return windowStart + p
            val next = p + total
            // 盒子越过窗口，终点即下一顶层盒起点
            if (next > buf.size) return (windowStart + next).takeIf { it < fileLength }
            p = next.toInt()
        }
        return null
    }

    // 窗口首字节是否为 ISO-BMFF 顶层盒
    fun isMp4Like(buf: ByteArray): Boolean =
        buf.size >= 12 && ascii(buf, 4, 4) in MP4_TOP_LEVEL_BOXES

    // 采样率 20 位、声道-1 3 位、位深-1 5 位
    // 块类型或块长不符即结构不成立
    private fun parseFlac(buf: ByteArray, from: Int): ContainerFormat? {
        if (from + 22 > buf.size || !buf.matches(from, "fLaC")) return null
        val blockType = buf[from + 4].toInt() and 0x7F
        if (blockType != 0 || u24be(buf, from + 5) != FLAC_STREAMINFO_BYTES) return null
        val p = from + 18
        val sampleRate = ((buf[p].toInt() and 0xFF) shl 12) or
            ((buf[p + 1].toInt() and 0xFF) shl 4) or
            ((buf[p + 2].toInt() and 0xF0) ushr 4)
        val channels = ((buf[p + 2].toInt() and 0x0E) ushr 1) + 1
        // 5 位先拼合再加 1，否则 32bit 被进位吞高位
        val bitDepth = (((buf[p + 2].toInt() and 0x01) shl 4) or
            ((buf[p + 3].toInt() and 0xF0) ushr 4)) + 1
        return containerFormatOf(sampleRate.toLong(), bitDepth, channels)
    }

    // 逐块找 fmt，不假定为首块且长 16
    private fun parseRiff(buf: ByteArray, from: Int): ContainerFormat? {
        if (!buf.matches(from, "RIFF") && !buf.matches(from, "RF64")) return null
        if (!buf.matches(from + 8, "WAVE")) return null
        var p = from + 12
        while (p + 8 <= buf.size) {
            val size = u32le(buf, p + 4)
            val body = p + 8
            val bodyEnd = body + size
            if (size <= 0 || bodyEnd > buf.size) return null
            if (buf.matches(p, "fmt ")) {
                // WAVEFORMATEX 有效字段到偏移 16 止
                if (size < 16) return null
                val formatTag = u16le(buf, body)
                val channels = u16le(buf, body + 2)
                val sampleRate = u32le(buf, body + 4)
                var bitDepth = u16le(buf, body + 14)
                // EXTENSIBLE 容器位深可大于有效位深，取后者
                if (formatTag == WAVE_FORMAT_EXTENSIBLE && size >= 40) {
                    val validBits = u16le(buf, body + 18)
                    if (validBits in 1 until bitDepth) bitDepth = validBits
                }
                return containerFormatOf(sampleRate, bitDepth, channels)
            }
            // 块按偶数字节对齐
            p = (bodyEnd + (size and 1L)).toInt()
        }
        return null
    }

    // 逐层下钻至 stsd
    // 取首个可解析的音频采样描述
    fun parseMp4(buf: ByteArray, windowStart: Long, fileLength: Long): ContainerFormat? {
        // 上界取窗口末与文件末较小者
        val remaining = if (fileLength > windowStart) fileLength - windowStart else Long.MAX_VALUE
        val topEnd = minOf(buf.size.toLong(), remaining).toInt()
        if (topEnd < BOX_HEADER_BYTES) return null
        val moov = findBox(buf, 0, topEnd, "moov") ?: return null
        val moovRange = boxRange(buf, moov, topEnd) ?: return null
        var trak = findBox(buf, moovRange.first, moovRange.last, "trak")
        while (trak != null) {
            val trakRange = boxRange(buf, trak, moovRange.last) ?: return null
            parseAudioTrack(buf, trakRange.first, trakRange.last)?.let { return it }
            trak = findBox(buf, trakRange.last, moovRange.last, "trak")
        }
        return null
    }

    // 下钻 mdia→minf→stbl→stsd
    private fun parseAudioTrack(buf: ByteArray, from: Int, to: Int): ContainerFormat? {
        val mdia = descend(buf, from, to, "mdia") ?: return null
        val minf = descend(buf, mdia.first, mdia.last, "minf") ?: return null
        val stbl = descend(buf, minf.first, minf.last, "stbl") ?: return null
        val stsd = descend(buf, stbl.first, stbl.last, "stsd") ?: return null
        // stsd 载荷：版本标志 4 + 条目数 4
        var p = stsd.first + 8
        while (p + BOX_HEADER_BYTES <= stsd.last) {
            val range = boxRange(buf, p, stsd.last) ?: return null
            parseAudioSampleEntry(buf, range.first, range.last, ascii(buf, p + 4, 4))
                ?.let { return it }
            p = range.last
        }
        return null
    }

    private fun descend(buf: ByteArray, from: Int, to: Int, type: String): IntRange? {
        val box = findBox(buf, from, to, type) ?: return null
        return boxRange(buf, box, to)
    }

    // 声道/名义位深/采样率在载荷 16/18/24
    // entryType 判定名义位深是否可信
    private fun parseAudioSampleEntry(
        buf: ByteArray,
        from: Int,
        to: Int,
        entryType: String,
    ): ContainerFormat? {
        if (to - from < AUDIO_SAMPLE_ENTRY_BYTES) return null
        val version = u16be(buf, from + 8)
        // 版本 2 布局不同，不覆盖，交平台兜底
        if (version == 2) return null
        val sampleRate = u32be(buf, from + 24) ushr 16
        val channels = u16be(buf, from + 16)
        // 版本 1 固定字段后另有 16 字节
        val childAt = from + AUDIO_SAMPLE_ENTRY_BYTES + if (version == 1) 16 else 0
        if (childAt + BOX_HEADER_BYTES <= to && buf.matches(childAt + 4, "alac")) {
            parseAlacCookie(buf, childAt, to)?.let { return it.copy(codecName = ALAC_CODEC_NAME) }
        }
        // 仅线性 PCM 的 msamplesize 为真，有损无效
        val bitDepth = if (entryType in LINEAR_PCM_ENTRY_TYPES) u16be(buf, from + 18) else null
        return containerFormatOf(sampleRate, bitDepth, channels)
    }

    // alac 盒：位深/声道/采样率在 17/21/32
    private fun parseAlacCookie(buf: ByteArray, at: Int, to: Int): ContainerFormat? {
        val range = boxRange(buf, at, to) ?: return null
        if (range.last - range.first < ALAC_CONFIG_BYTES || at + ALAC_CONFIG_BYTES + 8 > buf.size) {
            return null
        }
        return containerFormatOf(
            sampleRate = u32be(buf, at + 32),
            bitDepth = buf[at + 17].toInt() and 0xFF,
            channels = buf[at + 21].toInt() and 0xFF,
        )
    }

    // 窗口内按序查找盒子，返回相对偏移
    private fun findBox(buf: ByteArray, from: Int, to: Int, type: String): Int? {
        var p = from
        while (p + BOX_HEADER_BYTES <= to) {
            val total = boxLength(buf, p, to) ?: return null
            if (buf.matches(p + 4, type)) return p
            val next = p + total
            if (next > to) return null
            p = next.toInt()
        }
        return null
    }

    // 盒子载荷范围（起点含、终点不含）
    private fun boxRange(buf: ByteArray, at: Int, to: Int): IntRange? {
        val total = boxLength(buf, at, to) ?: return null
        val body = at + boxHeaderLength(buf, at)
        val end = minOf(at + total, to.toLong()).toInt()
        return if (body <= end) body..end else null
    }

    private fun boxHeaderLength(buf: ByteArray, at: Int): Int =
        if (at + 16 <= buf.size && u32be(buf, at) == 1L) LARGE_BOX_HEADER_BYTES else BOX_HEADER_BYTES

    // 盒子总长含头：1 读 64 位，0 延伸到 to
    private fun boxLength(buf: ByteArray, at: Int, to: Int): Long? {
        if (at + BOX_HEADER_BYTES > buf.size) return null
        val size32 = u32be(buf, at)
        val header = boxHeaderLength(buf, at)
        val total = when {
            size32 == 1L -> if (at + LARGE_BOX_HEADER_BYTES <= buf.size) u64be(buf, at + 8) else return null
            size32 == 0L -> (to - at).toLong()
            else -> size32
        }
        return total.takeIf { it >= header }
    }

    // 顶层盒总长：0 延伸到文件末，按窗口换算
    private fun topLevelBoxLength(buf: ByteArray, at: Int, windowStart: Long, fileLength: Long): Long? {
        if (u32be(buf, at) != 0L) return boxLength(buf, at, buf.size)
        val total = fileLength - windowStart - at
        return total.takeIf { it >= BOX_HEADER_BYTES }
    }

    // 统一校验后构造：越界说明位置解析错了，宁可返回 null
    private fun containerFormatOf(sampleRate: Long, bitDepth: Int?, channels: Int): ContainerFormat? {
        if (sampleRate !in 1..PCM_MAX_SAMPLE_RATE.toLong()) return null
        if (channels !in 1..MAX_CHANNELS) return null
        if (bitDepth != null && bitDepth !in 1..MAX_BIT_DEPTH) return null
        return ContainerFormat(sampleRate.toInt(), bitDepth, channels)
    }

    // 字节与编码工具

    private fun ByteArray.matches(at: Int, text: String): Boolean {
        if (at < 0 || at + text.length > size) return false
        for (i in text.indices) {
            if (this[at + i].toInt() and 0xFF != text[i].code) return false
        }
        return true
    }

    private fun ascii(buf: ByteArray, at: Int, length: Int): String {
        if (at < 0 || at + length > buf.size) return ""
        return String(buf, at, length, Charsets.ISO_8859_1)
    }

    private fun u16be(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

    private fun u24be(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 16) or
            ((buf[at + 1].toInt() and 0xFF) shl 8) or
            (buf[at + 2].toInt() and 0xFF)

    // 无符号以 Long 承载，64 位高位为 1 视为非法
    private fun u32be(buf: ByteArray, at: Int): Long =
        ((buf[at].toLong() and 0xFF) shl 24) or
            ((buf[at + 1].toLong() and 0xFF) shl 16) or
            ((buf[at + 2].toLong() and 0xFF) shl 8) or
            (buf[at + 3].toLong() and 0xFF)

    private fun u64be(buf: ByteArray, at: Int): Long {
        if (buf[at].toInt() and 0x80 != 0) return -1L
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (buf[at + i].toLong() and 0xFF)
        return value
    }

    private fun u16le(buf: ByteArray, at: Int): Int =
        (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8)

    private fun u32le(buf: ByteArray, at: Int): Long =
        (buf[at].toLong() and 0xFF) or
            ((buf[at + 1].toLong() and 0xFF) shl 8) or
            ((buf[at + 2].toLong() and 0xFF) shl 16) or
            ((buf[at + 3].toLong() and 0xFF) shl 24)

    private const val ID3V2_HEADER_BYTES = 10
    private const val MAX_ID3V2_BYTES = 32 * 1024 * 1024
    private const val FLAC_STREAMINFO_BYTES = 34
    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    private const val BOX_HEADER_BYTES = 8
    private const val LARGE_BOX_HEADER_BYTES = 16
    // AudioSampleEntry 定长 28
    private const val AUDIO_SAMPLE_ENTRY_BYTES = 28
    // alac 配置段 28 字节
    private const val ALAC_CONFIG_BYTES = 28
    private const val MAX_CHANNELS = 64
    private const val MAX_BIT_DEPTH = 64
}
