package com.yichao.evilgodxu.data.music.analysis

import android.media.MediaFormat
import java.nio.ByteBuffer

// MediaCodec 输出 PCM 的样本解释：频谱分析与逐字对齐两条解码路径共用，
// 避免「按编码读样本」的规则出现第二份实现。编码取值为 KEY_PCM_ENCODING 的常量，
// 其中 24bit 打包与 32bit 整型在部分 API 层级未公开常量，故按数值直接比较
internal object PcmFormat {

    const val ENCODING_16BIT = 2
    const val ENCODING_8BIT = 3
    const val ENCODING_FLOAT = 4
    const val ENCODING_24BIT_PACKED = 21
    const val ENCODING_32BIT = 22

    // 单样本字节宽：未知编码回退 16 位，避免按错误步长读取解交织
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        ENCODING_8BIT -> 1
        ENCODING_24BIT_PACKED -> 3
        ENCODING_FLOAT, ENCODING_32BIT -> 4
        else -> 2
    }

    // 输出格式声明的 PCM 编码，缺省按 16 位
    fun encodingOf(format: MediaFormat): Int =
        format.getInteger(MediaFormat.KEY_PCM_ENCODING, ENCODING_16BIT)

    // 把单样本读为 [-1, 1) 的浮点值。24bit 打包为 3 字节有符号小端；
    // 32bit 为有符号整型（非浮点），与 FLOAT 分属两种编码
    fun read(view: ByteBuffer, offset: Int, encoding: Int): Float = when (encoding) {
        ENCODING_FLOAT -> view.getFloat(offset)
        ENCODING_32BIT -> view.getInt(offset) / 2147483648f
        ENCODING_24BIT_PACKED -> {
            val b0 = view.get(offset).toInt() and 0xFF
            val b1 = view.get(offset + 1).toInt() and 0xFF
            val b2 = view.get(offset + 2).toInt() and 0xFF
            (((b2 shl 24) or (b1 shl 16) or (b0 shl 8)) shr 8) / 8388608f
        }
        ENCODING_8BIT -> ((view.get(offset).toInt() and 0xFF) - 128) / 128f
        else -> view.getShort(offset).toFloat() / 32768f
    }
}
