package com.yichao.evilgodxu.data.music.playback.alac

/**
 * ALAC 的自适应 Golomb 熵解码（参考实现的 `dyn_decomp` / `dyn_get_32bit` / `dyn_get`）。
 *
 * 每个残差由「一元前缀 + 定长余数」两段构成，前缀长度按均值自适应调整：均值是残差幅度的滑动估计，
 * 幅度大时前缀短、余数长，反之亦然。均值本身随每一样本更新，故残差必须**按顺序**解，不能并行。
 *
 * 另有一条零游程通路：均值降到很低时（连续小残差），改用一段专门的游程编码一次性写出若干个零残差，
 * 静音与极安静的段落因此只占几位。两条通路的选择只取决于均值，与外界无关。
 *
 * 算术一律按 32 位无符号（[UInt]）进行——参考实现里这一层的变量都是 `uint32_t`，中间量会在
 * 0xFFFFFFFF 附近回绕，按有符号算会在回绕点上与参考实现分道扬镳。
 */
internal object AlacEntropyDecoder {

    /** 均值的小数位数：均值以 1/512 为单位累加 */
    private const val MEAN_SHIFT = 9

    /** 「均值 = 1.0」的定点表示，用于判定是否进入零游程通路 */
    private const val ONE_IN_FIXED_POINT = 1 shl MEAN_SHIFT

    /** 判定前对均值的放大倍数：放大后仍低于 1.0 才走零游程通路 */
    private const val MEAN_FRACTION_SHIFT = 2

    /**
     * 零游程通路的余数位宽由均值现推，算式与编码端约定一致：
     * `前导零数 − 基准 − 偏置移位`，即均值越小、位宽越窄。
     */
    private const val ZERO_RUN_BASE_BITS = 24
    private const val ZERO_RUN_MEAN_OFFSET = 1 shl (MEAN_SHIFT - MEAN_FRACTION_SHIFT - 3)
    private const val ZERO_RUN_MEAN_SHIFT = MEAN_SHIFT - MEAN_FRACTION_SHIFT - 1

    /** 一元前缀的位数上限：达到即改读定长余数 */
    private const val MAX_PREFIX = 9

    /** 零游程数的定长余数位宽 */
    private const val ZERO_RUN_ESCAPE_BITS = 16

    /** 零游程数的上限：达到上限时该数已由定长余数直接给出，符号偏置随之复位 */
    private const val ZERO_RUN_LIMIT = 0xFFFF

    /** 均值达到该值后不再增长（超过即夹住） */
    private const val MEAN_CLAMP_THRESHOLD = 0xFFFF

    /** 解码 [sampleCount] 个残差写入 [out]；[escapeBitWidth] 是源码字宽，决定定长余数的上限 */
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

            // 最低位是符号位：奇数表示负，偶数表示正
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
     * 读一个残差幅度的编码值。
     *
     * 前缀为若干连续 1；前缀达到上限时改读 [escapeBitWidth] 位的定长值（留给超出均值模型的大幅度残差）。
     * 余数段少占一位：余数小于 2 时那一位不属于本字段（下一字段紧接其后），故读到的 0/1 与 2 以上要分开
     * 还原——前者不加偏移，后者才按「余数减一」计入。位序上这同样意味着 0/1 的幅度仅消耗
     * 前缀 + 余数位宽 − 1 位，2 以上才用满余数位宽。
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

    /** 读零游程的长度，编码方式与残差同源，只是没有符号位 */
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
            // 余数为 0/1 时那一位不属于本字段，归还给位流
            result -= (remainder - 1).toUInt()
            reader.skip(-1)
        }
        return result
    }

    /** 当前位起连续 1 的个数，即为自适应 Golomb 的一元前缀 */
    private fun leadingOnes(reader: AlacBitReader): Int =
        Integer.numberOfLeadingZeros(reader.peek(32).inv())
}
