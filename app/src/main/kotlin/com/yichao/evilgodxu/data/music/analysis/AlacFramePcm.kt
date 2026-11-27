package com.yichao.evilgodxu.data.music.analysis

import com.yichao.evilgodxu.data.music.playback.alac.AlacFrameDecoder
import com.yichao.evilgodxu.data.music.playback.alac.AlacOutputEncoding
import com.yichao.evilgodxu.data.music.playback.alac.AlacPcmEncoder
import com.yichao.evilgodxu.data.music.playback.alac.AlacSpecificConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把 ALAC 帧写成交错浮点 PCM。
 * 小端，须与 PcmFormat 一致。
 */
internal class AlacFramePcm(private val config: AlacSpecificConfig) {

    // 按整帧预置容量，逐帧复用同一块
    private val buffer: ByteBuffer =
        ByteBuffer.allocate(config.frameLength * config.channelCount * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

    /**
     * 写入当前帧，返回本帧视图。
     * 缓冲逐帧复用，下次 encode 前有效。
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
