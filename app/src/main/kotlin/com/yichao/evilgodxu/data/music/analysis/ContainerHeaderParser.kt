package com.yichao.evilgodxu.data.music.analysis

import kotlin.math.pow
import kotlin.math.roundToInt

// 容器头规格：采样率、位深与声道数。
// 位深可空——有损编码的采样描述 msamplesize 只是名义值，容器未声明真实位深时留空，
// 调用方据此跳过位深相关判定，不用 0 或推测值顶替
internal data class ContainerFormat(
    val sampleRate: Int,
    val bitDepth: Int?,
    val channels: Int,
)

// 音频容器头部规格解析：只读文件头的元数据块，不依赖平台解码器。
// 覆盖 FLAC、WAV/RF64、AIFF/AIFC、ALAC（MP4 音轨）、APE、DSF、DFF——APE 与 DSD 无平台
// 解码器，容器头是这些格式唯一的规格来源。解析器全部是纯函数（字节数组 + 窗口在文件中的
// 绝对偏移 → 规格），与文件 IO 解耦，既便于单测，也让「读多少字节」由调用方按格式决定。
//
// 解析一律先校验魔数与块结构，再读字段：容器头被 ID3v2 标签或私有块顶偏时宁可返回 null，
// 也不在错误的位置上取出一组看似合理的采样率/位深
internal object ContainerHeaderParser {

    // DSD 的物理位深恒为 1bit。DSF 的 "bits per sample" 字段标注的是位序（1=LSB/8=MSB），
    // 不是位深；直接采用会把 MSB 优先的文件误报成 8bit
    private const val DSD_BIT_DEPTH = 1

    // 采样率上限：PCM 容器最高到 DXD 量级，DSD 是 MHz 量级，两者分开校验
    private const val PCM_MAX_SAMPLE_RATE = 768_000
    private const val DSD_MAX_SAMPLE_RATE = 45_158_400L

    // MP4 采样描述中代表线性 PCM 的条目类型：只有这些类型的 msamplesize 是真实位深
    private val LINEAR_PCM_ENTRY_TYPES = setOf(
        "lpcm", "sowt", "twos", "in24", "in32", "fl32", "fl64", "raw ", "alaw", "ulaw", "ima4",
    )

    // MP4 顶层盒类型：用于判别文件是否为 ISO-BMFF 容器
    private val MP4_TOP_LEVEL_BOXES = setOf("ftyp", "moov", "free", "mdat", "wide", "skip", "styp")

    // 入口：按魔数识别容器并解析规格。windowStart 为 buf[0] 在文件中的绝对偏移，
    // fileLength 用于解析尺寸外延到文件末的顶层盒子（如 mdat）。无法识别返回 null
    fun parse(buf: ByteArray, windowStart: Long, fileLength: Long): ContainerFormat? {
        if (buf.size < 12) return null
        return when {
            buf.matches(0, "fLaC") -> parseFlac(buf, 0)
            buf.matches(0, "RIFF") || buf.matches(0, "RF64") -> parseRiff(buf, 0)
            buf.matches(0, "FORM") && (buf.matches(8, "AIFF") || buf.matches(8, "AIFC")) ->
                parseAiff(buf, 0)
            buf.matches(0, "MAC ") -> parseApe(buf, 0)
            buf.matches(0, "DSD ") -> parseDsf(buf, 0)
            buf.matches(0, "FRM8") && buf.matches(12, "DSD ") -> parseDff(buf, 0)
            isMp4Like(buf) -> parseMp4(buf, windowStart, fileLength)
            else -> null
        }
    }

    // ID3v2 前置标签的总字节数（10 字节头 + 同步安全长度字段）；无标签返回 0。
    // 部分下载源会在 FLAC/APE/DSF 前写入 ID3v2，此时容器魔数不在文件首字节，
    // 调用方据本偏移重新定位容器头。长度字段异常偏大时按无标签处理，
    // 避免畸形标签把读取位置推到文件之外
    fun id3v2TagSize(buf: ByteArray): Int {
        if (buf.size < ID3V2_HEADER_BYTES || !buf.matches(0, "ID3")) return 0
        // 版本字节为 0xFF 属非法值，可用于排除正文中偶然出现的 "ID3" 三字节
        if (buf[3].toInt() and 0xFF == 0xFF) return 0
        val size = ((buf[6].toInt() and 0x7F) shl 21) or
            ((buf[7].toInt() and 0x7F) shl 14) or
            ((buf[8].toInt() and 0x7F) shl 7) or
            (buf[9].toInt() and 0x7F)
        val total = ID3V2_HEADER_BYTES + size
        return if (total in ID3V2_HEADER_BYTES..MAX_ID3V2_BYTES) total else 0
    }

    // 探测 MP4 顶层盒子的绝对偏移，用于 moov 后置（未 faststart）的文件：
    // 头窗遍历会被超出窗口的 mdat 截断，此时其终点即下一个顶层盒子的起点，
    // 调用方在该偏移重读窗口后再调 parseMp4 校验。盒子越界且无后续空间时返回 null
    fun mp4BoxOffset(buf: ByteArray, windowStart: Long, fileLength: Long, type: String): Long? {
        var p = 0
        while (p + BOX_HEADER_BYTES <= buf.size) {
            val total = topLevelBoxLength(buf, p, windowStart, fileLength) ?: return null
            if (buf.matches(p + 4, type)) return windowStart + p
            val next = p + total
            // 盒子越过窗口：终点即下一个顶层盒子起点，可能仍是待解析的盒子
            if (next > buf.size) return (windowStart + next).takeIf { it < fileLength }
            p = next.toInt()
        }
        return null
    }

    // 窗口首字节是否为 ISO-BMFF 顶层盒
    fun isMp4Like(buf: ByteArray): Boolean =
        buf.size >= 12 && ascii(buf, 4, 4) in MP4_TOP_LEVEL_BOXES

    // ---- FLAC ----

    // STREAMINFO（类型 0，块长固定 34）紧随 fLaC 魔数与 4 字节块头之后，
    // 采样率 20 位 + 声道数-1 共 3 位 + 位深-1 共 5 位，打包在其后第 10 字节起的 64 位字段中。
    // 块类型或块长不符说明头部结构不成立（STREAMINFO 按规范必须是首块）
    private fun parseFlac(buf: ByteArray, from: Int): ContainerFormat? {
        if (from + 22 > buf.size || !buf.matches(from, "fLaC")) return null
        val blockType = buf[from + 4].toInt() and 0x7F
        if (blockType != 0 || u24be(buf, from + 5) != FLAC_STREAMINFO_BYTES) return null
        val p = from + 18
        val sampleRate = ((buf[p].toInt() and 0xFF) shl 12) or
            ((buf[p + 1].toInt() and 0xFF) shl 4) or
            ((buf[p + 2].toInt() and 0xF0) ushr 4)
        val channels = ((buf[p + 2].toInt() and 0x0E) ushr 1) + 1
        // 位深 5 位域先拼合再加 1：加 1 须作用于整个 5 位值，
        // 否则 32bit（域值 31）会被低 4 位进位吞掉高位而误读为 16bit
        val bitDepth = (((buf[p + 2].toInt() and 0x01) shl 4) or
            ((buf[p + 3].toInt() and 0xF0) ushr 4)) + 1
        return containerFormatOf(sampleRate.toLong(), bitDepth, channels)
    }

    // ---- WAV / RF64 ----

    // 逐块遍历找 fmt 块。fmt 恒为首块、块长恒 16 的固定偏移假设，在带 bext/JUNK 等
    // 前置块或 WAVE_FORMAT_EXTENSIBLE 的文件上不成立，故按块结构推进
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
                // WAVEFORMATEX 有效字段到块体偏移 16 为止
                if (size < 16) return null
                val formatTag = u16le(buf, body)
                val channels = u16le(buf, body + 2)
                val sampleRate = u32le(buf, body + 4)
                var bitDepth = u16le(buf, body + 14)
                // WAVE_FORMAT_EXTENSIBLE 的容器位深可大于有效位深，以后者为准
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

    // ---- AIFF / AIFC ----

    // FORM 容器逐块遍历取 COMM：声道 2 字节 + 采样帧数 4 字节 + 位深 2 字节，
    // 采样率是 80 位 IEEE 扩展精度浮点数，非 IEEE 754 二进制小数
    private fun parseAiff(buf: ByteArray, from: Int): ContainerFormat? {
        if (!buf.matches(from, "FORM")) return null
        if (!buf.matches(from + 8, "AIFF") && !buf.matches(from + 8, "AIFC")) return null
        var p = from + 12
        while (p + 8 <= buf.size) {
            val size = u32be(buf, p + 4)
            val body = p + 8
            val bodyEnd = body + size
            if (size <= 0 || bodyEnd > buf.size) return null
            if (buf.matches(p, "COMM")) {
                // COMM 有效字段到块体偏移 18 为止（10 字节采样率）
                if (size < 18) return null
                return containerFormatOf(
                    sampleRate = extended80ToInt(buf, body + 8).toLong(),
                    bitDepth = u16be(buf, body + 6),
                    channels = u16be(buf, body),
                )
            }
            p = (bodyEnd + (size and 1L)).toInt()
        }
        return null
    }

    // 80 位 IEEE 扩展精度浮点 → 整数：1 位符号 + 15 位偏置指数 + 64 位显式整数尾数。
    // 音频采样率恒为正整数；指数为 0（零/非规格化）或全 1（无穷/NaN）、符号位为负时不可解析。
    // 尾数只取高 32 位：规格化后二进制点在最高位之后，高 32 位已给出 2^-32 的相对精度，
    // 而整个 64 位尾数在 Long 中是负数（最高位恒为 1），按有符号读取会得到负值
    private fun extended80ToInt(buf: ByteArray, at: Int): Int {
        if (at + 10 > buf.size) return 0
        if (buf[at].toInt() and 0x80 != 0) return 0
        val exponent = ((buf[at].toInt() and 0x7F) shl 8) or (buf[at + 1].toInt() and 0xFF)
        if (exponent == 0 || exponent == 0x7FFF) return 0
        var mantissa = 0L
        for (i in 2 until 6) mantissa = (mantissa shl 8) or (buf[at + i].toLong() and 0xFF)
        if (mantissa <= 0L) return 0
        val value = mantissa.toDouble() * 2.0.pow(exponent - 16383 - 31)
        if (value < 1.0 || value > PCM_MAX_SAMPLE_RATE.toDouble()) return 0
        return value.roundToInt()
    }

    // ---- APE ----

    // MAC 描述符第 8 字节起的 32 位字段给出描述符总长，APE_HEADER 紧随其后；
    // 位深/声道/采样率位于 APE_HEADER 偏移 16/18/20。描述符长度随版本变化（3.98+ 为 52），
    // 字段越界时按现行版本长度兜底
    private fun parseApe(buf: ByteArray, from: Int): ContainerFormat? {
        if (!buf.matches(from, "MAC ")) return null
        val descriptorBytes = u32le(buf, from + 8)
        val headerOffset = if (descriptorBytes in APE_MIN_DESCRIPTOR_BYTES..APE_MAX_DESCRIPTOR_BYTES) {
            descriptorBytes.toInt()
        } else {
            APE_MODERN_DESCRIPTOR_BYTES
        }
        val headerAt = from + headerOffset
        if (headerAt + APE_HEADER_BYTES > buf.size) return null
        return containerFormatOf(
            sampleRate = u32le(buf, headerAt + 20),
            bitDepth = u16le(buf, headerAt + 16),
            channels = u16le(buf, headerAt + 18),
        )
    }

    // ---- DSF ----

    // DSD 块 28 字节后是 fmt 块，声道数与采样率位于 fmt 块偏移 24/28
    private fun parseDsf(buf: ByteArray, from: Int): ContainerFormat? {
        if (!buf.matches(from, "DSD ")) return null
        val fmtAt = from + DSF_DSD_CHUNK_BYTES
        if (!buf.matches(fmtAt, "fmt ") || fmtAt + DSF_FMT_CHUNK_BYTES > buf.size) return null
        return dsdFormatOf(u32le(buf, fmtAt + 28), u32le(buf, fmtAt + 24))
    }

    // ---- DFF ----

    // FRM8 → PROP → SND 下的子块给出采样率（FS）与声道数（CHNL）。
    // 块为「4 字节标识 + 8 字节大端长度」结构，载荷按偶数字节对齐
    private fun parseDff(buf: ByteArray, from: Int): ContainerFormat? {
        if (!buf.matches(from, "FRM8") || !buf.matches(from + 12, "DSD ")) return null
        val prop = findDffChunk(buf, from + 16, buf.size, "PROP") ?: return null
        if (prop.size < 4 || !buf.matches(prop.body, "SND ")) return null
        val end = prop.body + prop.size
        var sampleRate = 0L
        var channels = 0L
        var p = prop.body + 4
        while (p + DFF_CHUNK_HEADER_BYTES <= end) {
            val size = u64be(buf, p + 4)
            val body = p + DFF_CHUNK_HEADER_BYTES
            if (size <= 0 || body + size > end) break
            when {
                buf.matches(p, "FS  ") && size >= 4 -> sampleRate = u32be(buf, body)
                buf.matches(p, "CHNL") && size >= 2 -> channels = u16be(buf, body).toLong()
            }
            p = (body + size + (size and 1L)).toInt()
        }
        return dsdFormatOf(sampleRate, channels)
    }

    // DSDIFF 块定位：返回载荷起点与长度；块结构不成立时终止遍历
    private fun findDffChunk(buf: ByteArray, from: Int, to: Int, id: String): DffChunk? {
        var p = from
        while (p + DFF_CHUNK_HEADER_BYTES <= to && p + DFF_CHUNK_HEADER_BYTES <= buf.size) {
            val size = u64be(buf, p + 4)
            val body = p + DFF_CHUNK_HEADER_BYTES
            if (size <= 0 || body + size > to) return null
            if (buf.matches(p, id)) return DffChunk(body, size.toInt())
            p = (body + size + (size and 1L)).toInt()
        }
        return null
    }

    // ---- MP4 / M4A（ALAC） ----

    // 逐层下钻 moov→trak→mdia→minf→stbl→stsd，取首个可解析的音频采样描述。
    // 采样描述才是音轨规格来源：mdia 下的其他盒子不含采样率/位深
    fun parseMp4(buf: ByteArray, windowStart: Long, fileLength: Long): ContainerFormat? {
        // 顶层遍历上界取窗口末与文件末的较小者：moov 为末盒且尺寸字段为 0 时才能换算真实长度；
        // 文件长度不可得时退化为窗口内遍历
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

    // 音轨：mdia→minf→stbl→stsd 四层顺序下钻，任一层缺失即返回 null
    private fun parseAudioTrack(buf: ByteArray, from: Int, to: Int): ContainerFormat? {
        val mdia = descend(buf, from, to, "mdia") ?: return null
        val minf = descend(buf, mdia.first, mdia.last, "minf") ?: return null
        val stbl = descend(buf, minf.first, minf.last, "stbl") ?: return null
        val stsd = descend(buf, stbl.first, stbl.last, "stsd") ?: return null
        // stsd 载荷：版本/标志 4 字节 + 条目数 4 字节，其后逐个为采样描述
        var p = stsd.first + 8
        while (p + BOX_HEADER_BYTES <= stsd.last) {
            val range = boxRange(buf, p, stsd.last) ?: return null
            parseAudioSampleEntry(buf, range.first, range.last, ascii(buf, p + 4, 4))
                ?.let { return it }
            p = range.last
        }
        return null
    }

    // 查找子盒子并返回其载荷范围
    private fun descend(buf: ByteArray, from: Int, to: Int, type: String): IntRange? {
        val box = findBox(buf, from, to, type) ?: return null
        return boxRange(buf, box, to)
    }

    // 音频采样描述：data_reference_index 之后的字段定长，
    // 声道数/名义位深/16.16 定点采样率位于载荷偏移 16/18/24。entryType 为采样描述类型，
    // 用于判定名义位深是否可信
    private fun parseAudioSampleEntry(
        buf: ByteArray,
        from: Int,
        to: Int,
        entryType: String,
    ): ContainerFormat? {
        if (to - from < AUDIO_SAMPLE_ENTRY_BYTES) return null
        val version = u16be(buf, from + 8)
        // 版本 2 改写了字段布局（64 位浮点采样率、显式位深），本解析不覆盖，交由平台提取器兜底
        if (version == 2) return null
        val sampleRate = u32be(buf, from + 24) ushr 16
        val channels = u16be(buf, from + 16)
        // 版本 1 在固定字段后另有 16 字节，其后的子盒子才是 alac 幻数盒
        val childAt = from + AUDIO_SAMPLE_ENTRY_BYTES + if (version == 1) 16 else 0
        if (childAt + BOX_HEADER_BYTES <= to && buf.matches(childAt + 4, "alac")) {
            parseAlacCookie(buf, childAt, to)?.let { return it }
        }
        // 仅线性 PCM 采样描述的 msamplesize 是真实位深：有损编码该字段恒为名义值（常见 16）
        val bitDepth = if (entryType in LINEAR_PCM_ENTRY_TYPES) u16be(buf, from + 18) else null
        return containerFormatOf(sampleRate, bitDepth, channels)
    }

    // alac 幻数盒：4 字节版本/标志后为 ALACSpecificConfig，
    // 位深/声道数/采样率位于盒起偏移 17/21/32；配置段长度不足时视为结构不成立
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

    // ---- 盒子遍历 ----

    // 窗口内按顺序查找指定类型的盒子，返回其相对偏移；盒子结构不成立即终止
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

    // 盒子总长（含头）：尺寸字段为 1 时读 64 位大尺寸，为 0 时表示延伸到 to
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

    // 顶层盒子总长：尺寸为 0 时表示延伸到文件末，由窗口偏移与文件长度换算
    private fun topLevelBoxLength(buf: ByteArray, at: Int, windowStart: Long, fileLength: Long): Long? {
        if (u32be(buf, at) != 0L) return boxLength(buf, at, buf.size)
        val total = fileLength - windowStart - at
        return total.takeIf { it >= BOX_HEADER_BYTES }
    }

    // ---- 规格校验 ----

    // 统一校验后构造：越界说明头部解析落在了错误的位置，宁可返回 null 也不给出错误规格
    private fun containerFormatOf(sampleRate: Long, bitDepth: Int?, channels: Int): ContainerFormat? {
        if (sampleRate !in 1..PCM_MAX_SAMPLE_RATE.toLong()) return null
        if (channels !in 1..MAX_CHANNELS) return null
        if (bitDepth != null && bitDepth !in 1..MAX_BIT_DEPTH) return null
        return ContainerFormat(sampleRate.toInt(), bitDepth, channels)
    }

    // DSD 规格校验：采样率为 MHz 量级（DSD64 起为 2.8224MHz），位深恒为 1bit
    private fun dsdFormatOf(sampleRate: Long, channels: Long): ContainerFormat? {
        if (sampleRate !in 1..DSD_MAX_SAMPLE_RATE) return null
        if (channels !in 1..MAX_CHANNELS.toLong()) return null
        return ContainerFormat(sampleRate.toInt(), DSD_BIT_DEPTH, channels.toInt())
    }

    // ---- 字节与编码工具 ----

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

    // 32/64 位无符号值一律以 Long 承载；64 位最高位为 1 时超出音频文件可能范围，按非法返回 -1
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

    // DSDIFF 块：载荷起点与长度
    private class DffChunk(val body: Int, val size: Int)

    private const val ID3V2_HEADER_BYTES = 10
    private const val MAX_ID3V2_BYTES = 32 * 1024 * 1024
    private const val FLAC_STREAMINFO_BYTES = 34
    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    private const val APE_MIN_DESCRIPTOR_BYTES = 24L
    private const val APE_MAX_DESCRIPTOR_BYTES = 1024L
    private const val APE_MODERN_DESCRIPTOR_BYTES = 52
    private const val APE_HEADER_BYTES = 24
    private const val DSF_DSD_CHUNK_BYTES = 28
    private const val DSF_FMT_CHUNK_BYTES = 52
    private const val DFF_CHUNK_HEADER_BYTES = 12
    private const val BOX_HEADER_BYTES = 8
    private const val LARGE_BOX_HEADER_BYTES = 16
    // AudioSampleEntry 固定字段长度：6 保留 + 2 数据引用索引 + 20 版本/声道/位深/采样率
    private const val AUDIO_SAMPLE_ENTRY_BYTES = 28
    // alac 幻数盒配置段长度：4 版本标志 + 24 ALACSpecificConfig
    private const val ALAC_CONFIG_BYTES = 28
    private const val MAX_CHANNELS = 64
    private const val MAX_BIT_DEPTH = 64
}
