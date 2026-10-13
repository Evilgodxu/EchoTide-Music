package com.yichao.evilgodxu.data.music.playback.alac

/**
 * ALAC 的自适应预测器（参考实现的 `unpc_block`）。
 *
 * 残差是相对「按最近若干样本加权外推」的误差，本步把它累加回样本：每一样本先由前若干样本的加权差算出
 * 预测增量，再加上残差，并按源码字宽截断回原精度。
 *
 * 预测系数随每个样本微调：调整量与残差同号、按各抽头的差值定向，误差量耗尽即停。编码端做的是同一套
 * 调整，系数因此不必进码流——本步必须与编码端逐位一致，否则后面的样本会一路错到底。
 *
 * 参考实现为 4 阶与 8 阶各写了一份展开版本，此处只保留通用循环：两者逐位等价——加法在 32 位补码下
 * 满足结合律，而展开版本与通用循环的更新次序、权重、提前退出条件都一一对应（展开版本对最后一个系数
 * 不再更新误差量，而该量此后不再被读），故无需为速度另抄一份。
 */
internal object AlacPredictor {

    /**
     * 参考实现里表示「改用一阶线性外推」的阶数：该模式不带系数，只做逐样本差分累加。
     *
     * 位流里的预测模式非零时，本预测器要先按这个阶数原地跑一遍，故由调用方一并使用。
     */
    const val FIRST_ORDER_MODE = 31

    /**
     * 由 [residual] 还原 [count] 个样本写入 [out]。
     *
     * [coefs] 为 null 只在 [numActive] 为 [FIRST_ORDER_MODE] 时出现；该模式不使用系数。
     * [channelBits] 是本次预测所用的字宽，[denominatorShift] 是系数定点小数位数。
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

            // 系数微调：方向由残差符号决定，抽头自最旧往最新逐个吸收，误差量耗尽或反向即停
            val errorSign = signOf(residualValue)
            if (errorSign == 0) continue
            var remainingError = residualValue
            for (tap in numActive - 1 downTo 0) {
                val difference = reference - out[index - 1 - tap]
                val step = signOf(difference)
                // 系数以 16 位存取，回写须按 16 位截断——编码端同此，越界与否两处必须一致
                coefs[tap] = (coefs[tap] - signOf(step * errorSign)).toShort().toInt()
                val applied = if (errorSign > 0) step else -step
                remainingError -= (numActive - tap) * ((applied * difference) shr denominatorShift)
                if (errorSign > 0 && remainingError <= 0) break
                if (errorSign < 0 && remainingError >= 0) break
            }
        }
    }

    /**
     * 按源码字宽截断并保留符号。
     *
     * 参考实现写作 `(value << shift) >> shift`（算术右移），等价于「取低 [channelBits] 位并按补码解释」。
     * 字宽不小于 32 时无需截断。
     */
    private fun truncateToWord(value: Int, channelBits: Int): Int {
        val shift = 32 - channelBits
        if (shift <= 0) return value
        return (value shl shift) shr shift
    }

    /** 参考实现的 `sign_of_int`：正为 1、负为 -1、零为 0 */
    private fun signOf(value: Int): Int = when {
        value > 0 -> 1
        value < 0 -> -1
        else -> 0
    }
}
