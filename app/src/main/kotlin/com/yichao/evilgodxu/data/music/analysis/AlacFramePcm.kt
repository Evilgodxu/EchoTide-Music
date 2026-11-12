package com.yichao.evilgodxu.data.music.analysis

import com.yichao.evilgodxu.data.music.playback.alac.AlacFrameDecoder
import com.yichao.evilgodxu.data.music.playback.alac.AlacOutputEncoding
import com.yichao.evilgodxu.data.music.playback.alac.AlacPcmEncoder
import com.yichao.evilgodxu.data.music.playback.alac.AlacSpecificConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把 ALAC 解出的一帧写成切片器可消费的交错浮点 PCM。
 *
 * 存在的理由是字节序：切片器按 [PcmFormat] 解释样本，其中浮点样本按小端读，而落盘的字节序
 * 由写入方决定——写成大端不会报任何错，只会让频谱静默退化成噪声。故字节序在此钉死，
 * 与 [PcmFormat] 成对出现，改动须同时改两处。
 *
 * 中间表示取浮点而非整型：频谱只关心幅度，浮点免去「源码位深 × 输出编码」的字长换算，
 * 解码精度也不因中间表示受损。
 */
internal class AlacFramePcm(private val config: AlacSpecificConfig) {

    // 容量按整帧预置：解码缓冲的样本数上界即帧长，逐帧复用同一块，不随帧数增长
    private val buffer: ByteBuffer =
        ByteBuffer.allocate(config.frameLength * config.channelCount * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

    /**
     * 写入 [decoder] 当前解出的帧；返回位置为 0、上界为本帧字节数的视图。
     *
     * 返回的缓冲在下次 [encode] 前有效：逐帧复用是这里唯一的取舍，调用方用完即交出去。
     */
    fun encode(decoder: AlacFrameDecoder): ByteBuffer {
        val samples = decoder.sampleCount
        buffer.clear()
        AlacPcmEncoder.encode(
            channels = decoder.channels,
            sampleCount = samples,
            channelCount = config.channelCount,
            bitDepth = config.bitDepth,
            encoding = AlacOutputEncoding.FLOAT,
            out = buffer,
        )
        val byteCount = AlacPcmEncoder.frameByteCount(
            sampleCount = samples,
            channelCount = config.channelCount,
            encoding = AlacOutputEncoding.FLOAT,
        )
        buffer.position(0)
        buffer.limit(byteCount)
        return buffer
    }
}
