package com.yichao.evilgodxu.data.music.playback.alac

/**
 * 自适应 Golomb 熵解码：一元前缀 + 定长余数
 * 均值逐样本更新，残差须按序解；低均值走零游程
 * 中间量按 32 位无符号回绕，不能按有符号算。
 */
internal object AlacEntropyDecoder {

    /** 均值以 1/512 为单位累加 */
    private const val MEAN_SHIFT = 9

    /** 均值 1.0 的定点表示，判零游程用 */
    private const val ONE_IN_FIXED_POINT = 1 shl MEAN_SHIFT

    /** 判定前放大倍数，放大后仍低于 1.0 才走零游程 */
    private const val MEAN_FRACTION_SHIFT = 2

    /** 零游程位宽＝前导零数 − 基准 − 偏置移位 */
    private const val ZERO_RUN_BASE_BITS = 24
    private const val ZERO_RUN_MEAN_OFFSET = 1 shl (MEAN_SHIFT - MEAN_FRACTION_SHIFT - 3)
    private const val ZERO_RUN_MEAN_SHIFT = MEAN_SHIFT - MEAN_FRACTION_SHIFT - 1

    /** 一元前缀的位数上限：达到即改读定长余数 */
    private const val MAX_PREFIX = 9

    /** 零游程数的定长余数位宽 */
    private const val ZERO_RUN_ESCAPE_BITS = 16

    /** 零游程上限；达到时符号偏置复位 */
    private const val ZERO_RUN_LIMIT = 0xFFFF

    /** 均值上限，超过即夹住 */
    private const val MEAN_CLAMP_THRESHOLD = 0xFFFF

    /** 解 sampleCount 个残差写入 out */
    fun decode(
        reader: AlacBitReader,
        out: IntArray,
        sampleCount: Int,
        escapeBitWidth: Int,
        initialMean: Int,
        meanScale: Int,
        meanLimitShift: Int,
    ) {
        val meanLimitMask = (1u shl meanLimitShift) - 1u
        var mean = initialMean.toUInt()
        var zeroMode = 0u
        var produced = 0
        while (produced < sampleCount) {
            val m = mean shr MEAN_SHIFT
            var k = 31 - Integer.numberOfLeadingZeros((m + 3u).toInt())
            if (k > meanLimitShift) k = meanLimitShift
            val n = readResidual(reader, (1u shl k) - 1u, k, escapeBitWidth)

            // 最低位为符号位：奇负偶正
            val encoded = n + zeroMode
            val magnitude = (encoded + 1u) shr 1
            out[produced] = if ((encoded and 1u) == 1u) -magnitude.toInt() else magnitude.toInt()
            produced++

            mean = (meanScale.toUInt() * encoded) + mean -
                ((meanScale.toUInt() * mean) shr MEAN_SHIFT)
            if (n > MEAN_CLAMP_THRESHOLD.toUInt()) mean = MEAN_CLAMP_THRESHOLD.toUInt()

            zeroMode = 0u
            if ((mean shl MEAN_FRACTION_SHIFT) < ONE_IN_FIXED_POINT.toUInt() &&
                produced < sampleCount
            ) {
                zeroMode = 1u
                val zeroRunShift = Integer.numberOfLeadingZeros(mean.toInt()) - ZERO_RUN_BASE_BITS +
                    ((mean + ZERO_RUN_MEAN_OFFSET.toUInt()) shr ZERO_RUN_MEAN_SHIFT).toInt()
                val runBits = ((1u shl zeroRunShift) - 1u) and meanLimitMask
                val run = readZeroRun(reader, runBits, zeroRunShift)
                if (produced.toLong() + run.toLong() > sampleCount) {
                    throw IllegalArgumentException(
                        "零游程越过帧尾：已产出 $produced，游程 $run，本帧 $sampleCount"
                    )
                }
                repeat(run.toInt()) {
                    out[produced++] = 0
                }
                if (run >= ZERO_RUN_LIMIT.toUInt()) zeroMode = 0u
                mean = 0u
            }
        }
    }

    /**
     * 读残差：连续 1 为前缀，达上限改读定长值
     * 余数 <2 少占一位不加偏移，≥2 按余数减一
     */
    private fun readResidual(
        reader: AlacBitReader,
        multiplier: UInt,
        remainderBits: Int,
        escapeBitWidth: Int,
    ): UInt {
        val prefix = leadingOnes(reader)
        if (prefix >= MAX_PREFIX) {
            reader.skip(MAX_PREFIX)
            return reader.read(escapeBitWidth).toUInt()
        }
        reader.skip(prefix + 1)
        if (remainderBits == 1) return prefix.toUInt()
        val remainder = reader.peek(remainderBits)
        reader.skip(remainderBits - 1)
        val base = prefix.toUInt() * multiplier
        if (remainder < 2) return base
        reader.skip(1)
        return base + (remainder - 1).toUInt()
    }

    /** 读零游程长度，编码同残差，无符号位 */
    private fun readZeroRun(reader: AlacBitReader, multiplier: UInt, remainderBits: Int): UInt {
        val prefix = leadingOnes(reader)
        if (prefix >= MAX_PREFIX) {
            reader.skip(MAX_PREFIX)
            return reader.read(ZERO_RUN_ESCAPE_BITS).toUInt()
        }
        reader.skip(prefix + 1)
        val remainder = reader.peek(remainderBits)
        reader.skip(remainderBits)
        var result = prefix.toUInt() * multiplier + remainder.toUInt() - 1u
        if (remainder < 2) {
            // 余数 0/1 时那一位不属于本字段，归还位流
            result -= (remainder - 1).toUInt()
            reader.skip(-1)
        }
        return result
    }

    /** 当前位起连续 1 的个数，即一元前缀 */
    private fun leadingOnes(reader: AlacBitReader): Int =
        Integer.numberOfLeadingZeros(reader.peek(32).inv())
}
