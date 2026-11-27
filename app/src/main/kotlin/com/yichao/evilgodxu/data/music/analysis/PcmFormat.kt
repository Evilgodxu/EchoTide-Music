package com.yichao.evilgodxu.data.music.analysis

import android.media.MediaFormat
import java.nio.ByteBuffer

// MediaCodec 输出 PCM 的样本解释
// 24bit 打包与 32bit 整型按数值比较
internal object PcmFormat {

    const val ENCODING_16BIT = 2
    const val ENCODING_8BIT = 3
    const val ENCODING_FLOAT = 4
    const val ENCODING_24BIT_PACKED = 21
    const val ENCODING_32BIT = 22

    // 单样本字节宽，未知回退 16 位
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        ENCODING_8BIT -> 1
        ENCODING_24BIT_PACKED -> 3
        ENCODING_FLOAT, ENCODING_32BIT -> 4
        else -> 2
    }

    fun encodingOf(format: MediaFormat): Int =
        format.getInteger(MediaFormat.KEY_PCM_ENCODING, ENCODING_16BIT)

    // 读单样本为 [-1,1)，24bit 为小端
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
