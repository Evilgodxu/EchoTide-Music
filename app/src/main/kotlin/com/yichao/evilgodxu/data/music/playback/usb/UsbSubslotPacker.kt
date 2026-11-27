package com.yichao.evilgodxu.data.music.playback.usb

import com.yichao.evilgodxu.data.music.playback.PcmConversion
import java.nio.ByteBuffer

/**
 * 打包成设备子槽：样本左对齐 32 位。
 * 音量按 15 位定点，满幅时逐位原样。
 * 返回末尾不足一个样本的字节数。
 */
internal fun packToSubslot(
    source: ByteBuffer,
    output: ByteBuffer,
    pcmEncoding: Int,
    subslotBytes: Int,
    gainFromQ15: Int = UNITY_GAIN_Q15,
    gainToQ15: Int = gainFromQ15,
    rampSamples: Int = RAMP_WHOLE_BLOCK,
): Int {
    require(subslotBytes in 1..4) { "子槽宽度只能是 1..4 字节，收到 $subslotBytes" }
    val bytesPerSourceSample = PcmConversion.frameSize(pcmEncoding, 1)
    if (gainFromQ15 == gainToQ15) {
        val applyGain = gainFromQ15 != UNITY_GAIN_Q15
        while (source.remaining() >= bytesPerSourceSample) {
            val value = PcmConversion.readAs32BitIntPcm(source, pcmEncoding)
            writeSubslot(output, if (applyGain) scaleByGain(value, gainFromQ15) else value, subslotBytes)
        }
    } else if (rampSamples == RAMP_WHOLE_BLOCK) {
        // 按本块均分：末样本落在目标，下一块续走
        val samples = source.remaining() / bytesPerSourceSample
        val span = (samples - 1).coerceAtLeast(1)
        var index = 0
        while (source.remaining() >= bytesPerSourceSample) {
            val value = PcmConversion.readAs32BitIntPcm(source, pcmEncoding)
            val gain = gainFromQ15 + ((gainToQ15 - gainFromQ15).toLong() * index / span).toInt()
            writeSubslot(output, scaleByGain(value, gain), subslotBytes)
            index++
        }
    } else {
        // 定长斜坡：第 span 个起即为目标增益
        val span = minOf(rampSamples, source.remaining() / bytesPerSourceSample).coerceAtLeast(0)
        val last = (span - 1).coerceAtLeast(0)
        var index = 0
        while (source.remaining() >= bytesPerSourceSample) {
            val value = PcmConversion.readAs32BitIntPcm(source, pcmEncoding)
            val gain = when {
                index >= span -> gainToQ15
                // 只有一个样本时无从插值，取起点值
                span <= 1 -> gainFromQ15
                else -> gainFromQ15 + ((gainToQ15 - gainFromQ15).toLong() * index / last).toInt()
            }
            writeSubslot(output, scaleByGain(value, gain), subslotBytes)
            index++
        }
    }
    val leftover = source.remaining()
    if (leftover > 0) {
        // 余数消费掉，否则同一缓冲会反复读出同样的余数
        source.position(source.limit())
    }
    return leftover
}

/**
 * 写一个样本的高 subslotBytes 字节，小端。
 * 丢低位前按目标 LSB 四舍五入。
 * 不做抖动，深衰减靠子槽余量。
 */
internal fun writeSubslot(output: ByteBuffer, value: Int, subslotBytes: Int) {
    val skip = 4 - subslotBytes
    // 满幅加半 LSB 会越过 Int，先在 Long 加
    val rounded = if (skip == 0) {
        value
    } else {
        (value.toLong() + (1L shl (8 * skip - 1)))
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
    }
    for (index in 0 until subslotBytes) {
        output.put((rounded shr (8 * (skip + index))).toByte())
    }
}

/** 15 位定点的满幅增益：等于不缩放 */
internal const val UNITY_GAIN_Q15 = 32768

/** 斜坡按整块均分，给定样本数则只走该数 */
internal const val RAMP_WHOLE_BLOCK = -1

/**
 * 音量换算成 15 位定点，满幅及以上取满幅。
 */
internal fun volumeToGainQ15(volume: Float): Int {
    if (volume >= 1f) return UNITY_GAIN_Q15
    return (volume.coerceAtLeast(0f) * UNITY_GAIN_Q15).toInt()
}

/** 按定点增益缩放，带半步进位，长期均值不偏负 */
private fun scaleByGain(value: Int, gainQ15: Int): Int =
    ((value.toLong() * gainQ15 + UNITY_GAIN_Q15 / 2) shr 15).toInt()
