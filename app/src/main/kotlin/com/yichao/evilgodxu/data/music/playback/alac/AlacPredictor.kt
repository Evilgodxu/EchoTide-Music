package com.yichao.evilgodxu.data.music.playback.alac

/**
 * 自适应预测：残差加回外推增量，按字宽截断。
 * 系数须与编码端逐位一致；只留通用循环。
 */
internal object AlacPredictor {

    /**
     * 一阶线性外推：不带系数，逐样本差分累加。
     * 预测模式非零时先原地跑一遍。
     */
    const val FIRST_ORDER_MODE = 31

    /**
     * 由 residual 还原样本写入 out。
     * coefs 仅一阶模式为 null。
     */
    fun restore(
        residual: IntArray,
        out: IntArray,
        count: Int,
        coefs: IntArray?,
        numActive: Int,
        channelBits: Int,
        denominatorShift: Int,
    ) {
        out[0] = residual[0]
        if (numActive == 0) {
            // 无系数即残差本身就是样本
            if (count > 1 && residual !== out) {
                residual.copyInto(out, 1, 1, count)
            }
            return
        }
        if (numActive == FIRST_ORDER_MODE) {
            var previous = out[0]
            for (index in 1 until count) {
                previous = truncateToWord(residual[index] + previous, channelBits)
                out[index] = previous
            }
            return
        }

        checkNotNull(coefs)
        require(numActive < count) {
            "预测阶数 $numActive 不小于本元素样本数 $count"
        }
        val denominatorHalf = 1 shl (denominatorShift - 1)

        // 前若干样本没有足够历史，退化为差分累加
        for (index in 1..numActive) {
            out[index] = truncateToWord(residual[index] + out[index - 1], channelBits)
        }

        val distance = numActive + 1
        for (index in distance until count) {
            val reference = out[index - distance]
            var sum = 0
            for (tap in 0 until numActive) {
                sum += coefs[tap] * (out[index - 1 - tap] - reference)
            }
            val residualValue = residual[index]
            val error = residualValue + reference + ((sum + denominatorHalf) shr denominatorShift)
            out[index] = truncateToWord(error, channelBits)

            // 系数按残差符号微调，误差耗尽即停
            val errorSign = signOf(residualValue)
            if (errorSign == 0) continue
            var remainingError = residualValue
            for (tap in numActive - 1 downTo 0) {
                val difference = reference - out[index - 1 - tap]
                val step = signOf(difference)
                // 系数按 16 位存取，回写须截断，与编码端一致
                coefs[tap] = (coefs[tap] - signOf(step * errorSign)).toShort().toInt()
                val applied = if (errorSign > 0) step else -step
                remainingError -= (numActive - tap) * ((applied * difference) shr denominatorShift)
                if (errorSign > 0 && remainingError <= 0) break
                if (errorSign < 0 && remainingError >= 0) break
            }
        }
    }

    /**
     * 按字宽截断取补码，即取低 channelBits 位
     * 字宽 ≥32 时无需截断。
     */
    private fun truncateToWord(value: Int, channelBits: Int): Int {
        val shift = 32 - channelBits
        if (shift <= 0) return value
        return (value shl shift) shr shift
    }

    /** 取符号：正 1、负 -1、零 0 */
    private fun signOf(value: Int): Int = when {
        value > 0 -> 1
        value < 0 -> -1
        else -> 0
    }
}
