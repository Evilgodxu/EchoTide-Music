package com.yichao.evilgodxu.data.music.playback.alac

/**
 * 单帧解码：元素解包 → 熵解码 → 预测 → 解相关
 * 元素与帧不共享状态，可随机定位，每次从零起算
 * 输出为按源码字宽取补码的整数样本，落盘由输出编码决定。
 * 本类不做线程安全保证：实例由单个解码线程独占。
 */
internal class AlacFrameDecoder(private val config: AlacSpecificConfig) {

    /** 逐声道样本缓冲，有效长度见 sampleCount */
    val channels: Array<IntArray> = Array(config.channelCount) { IntArray(config.frameLength) }

    /** 本次解码得到的每声道样本数 */
    var sampleCount: Int = 0
        private set

    /** 熵解码残差缓冲；二阶模式下原地复用为预测器输入 */
    private val residual = IntArray(config.frameLength)

    /** 解相关前左右声道，单声道只用前者 */
    private val mixedLeft = IntArray(config.frameLength)
    private val mixedRight = IntArray(config.frameLength)

    /** 位深之外的附加低位，立体声时按左右交错存放 */
    private val extraLowBits = IntArray(config.frameLength * CHANNELS_PER_PAIR)

    /** 左右声道预测系数，按元素从位流读入，不跨帧沿用 */
    private val leftCoefficients = IntArray(MAX_COEFFICIENTS)
    private val rightCoefficients = IntArray(MAX_COEFFICIENTS)

    /**
     * 解一帧；length 为本帧在 data 中的字节数。
     * 帧不完整或参数越界即抛异常，不补零。
     */
    fun decode(data: ByteArray, length: Int) {
        require(length > 0) { "空样本" }
        val reader = AlacBitReader(data, length)
        var channelIndex = 0
        sampleCount = 0
        while (true) {
            if (reader.position >= reader.sizeBits) {
                throw IllegalArgumentException("位流在解出全部声道之前结束")
            }
            when (val tag = reader.read(TAG_BITS)) {
                ID_SCE, ID_LFE -> {
                    decodeSingleChannelElement(reader, channelIndex)
                    channelIndex += 1
                }
                ID_CPE -> {
                    // 声道放不下整个元素时忽略，元素与声道并非一一对应
                    if (channelIndex + CHANNELS_PER_PAIR <= config.channelCount) {
                        decodeChannelPairElement(reader, channelIndex)
                        channelIndex += CHANNELS_PER_PAIR
                    }
                }
                // 结束元素后只剩填充位，本帧起止由容器给出，不再对齐
                ID_END -> return
                else -> throw IllegalArgumentException("不支持的语法元素 $tag")
            }
            if (channelIndex >= config.channelCount) return
        }
    }

    /** 单声道元素：一套参数，不解相关 */
    private fun decodeSingleChannelElement(reader: AlacBitReader, channelIndex: Int) {
        skipElementHeader(reader)
        val flags = readElementFlags(reader)
        val samples = resolveSampleCount(reader, flags)
        val channelBits = config.bitDepth - flags.bytesShifted * Byte.SIZE_BITS
        checkChannelBits(channelBits)

        val target = channels[channelIndex]
        if (flags.escape) {
            readUncompressed(reader, samples, channelBits, mixedRightOrNull = null)
            for (index in 0 until samples) {
                target[index] = truncateToWord(mixedLeft[index], config.bitDepth)
            }
            return
        }

        reader.read(8) // 解相关权重
        reader.read(8) // 解相关移位量，单声道读到即弃
        val left = readChannelParameters(reader, samples, leftCoefficients)
        val shiftStart = skipExtraLowBits(reader, flags, samples)
        readResiduals(reader, samples, channelBits, left)
        AlacPredictor.restore(
            residual, mixedLeft, samples, leftCoefficients, left.numActive,
            channelBits, left.denominatorShift,
        )
        readExtraLowBits(reader, shiftStart, flags, samples)

        for (index in 0 until samples) {
            target[index] = truncateToWord(
                combineExtraLowBits(mixedLeft[index], index, flags),
                config.bitDepth,
            )
        }
    }

    /** 声道对元素：两套参数，解出后按位流权重解相关 */
    private fun decodeChannelPairElement(reader: AlacBitReader, channelIndex: Int) {
        skipElementHeader(reader)
        val flags = readElementFlags(reader)
        val samples = resolveSampleCount(reader, flags)
        val leftChannel = channels[channelIndex]
        val rightChannel = channels[channelIndex + 1]

        if (flags.escape) {
            // 未压缩元素不解相关，按源码字宽左右交错排布
            readUncompressed(reader, samples, config.bitDepth, mixedRightOrNull = mixedRight)
            for (index in 0 until samples) {
                leftChannel[index] = truncateToWord(mixedLeft[index], config.bitDepth)
                rightChannel[index] = truncateToWord(mixedRight[index], config.bitDepth)
            }
            return
        }

        // 预测字宽比成品多一位，容纳解相关后的声道差
        val channelBits = config.bitDepth - flags.bytesShifted * Byte.SIZE_BITS + CHANNELS_PER_PAIR - 1
        checkChannelBits(channelBits)

        val mixBits = reader.read(8)
        val mixWeight = reader.read(8).toByte().toInt()
        val left = readChannelParameters(reader, samples, leftCoefficients)
        val right = readChannelParameters(reader, samples, rightCoefficients)
        val shiftStart = skipExtraLowBits(reader, flags, samples * CHANNELS_PER_PAIR)
        readResiduals(reader, samples, channelBits, left)
        AlacPredictor.restore(
            residual, mixedLeft, samples, leftCoefficients, left.numActive,
            channelBits, left.denominatorShift,
        )
        readResiduals(reader, samples, channelBits, right)
        AlacPredictor.restore(
            residual, mixedRight, samples, rightCoefficients, right.numActive,
            channelBits, right.denominatorShift,
        )
        readExtraLowBits(reader, shiftStart, flags, samples * CHANNELS_PER_PAIR)

        for (index in 0 until samples) {
            val leftValue: Int
            val rightValue: Int
            if (mixWeight != 0) {
                leftValue = mixedLeft[index] + mixedRight[index] -
                    ((mixWeight * mixedRight[index]) shr mixBits)
                rightValue = leftValue - mixedRight[index]
            } else {
                leftValue = mixedLeft[index]
                rightValue = mixedRight[index]
            }
            leftChannel[index] = truncateToWord(
                combineExtraLowBits(leftValue, index * CHANNELS_PER_PAIR, flags),
                config.bitDepth,
            )
            rightChannel[index] = truncateToWord(
                combineExtraLowBits(rightValue, index * CHANNELS_PER_PAIR + 1, flags),
                config.bitDepth,
            )
        }
    }

    /**
     * 读声道预测参数，系数填入 coefficients
     * 阶数须小于样本数，否则累加越界。
     */
    private fun readChannelParameters(
        reader: AlacBitReader,
        samples: Int,
        coefficients: IntArray,
    ): ChannelParameters {
        val modeAndShift = reader.read(8)
        val meanScaleAndOrder = reader.read(8)
        val numActive = meanScaleAndOrder and 0x1F
        if (numActive >= samples) {
            throw IllegalArgumentException("预测阶数 $numActive 不小于本元素样本数 $samples")
        }
        val denominatorShift = modeAndShift and 0x0F
        if (denominatorShift == 0) {
            // 小数位数为 0 时舍入量无从计算，按未定义处理
            throw IllegalArgumentException("系数定点小数位数为 0")
        }
        for (index in 0 until numActive) {
            coefficients[index] = reader.read(16).toShort().toInt()
        }
        return ChannelParameters(
            mode = modeAndShift ushr 4,
            denominatorShift = denominatorShift,
            meanScale = meanScaleAndOrder ushr 5,
            numActive = numActive,
        )
    }

    /**
     * 熵解码本元素的残差。
     * 预测模式非零时先原地做一遍一阶差分，再交常规预测器。
     */
    private fun readResiduals(
        reader: AlacBitReader,
        samples: Int,
        channelBits: Int,
        parameters: ChannelParameters,
    ) {
        val meanScale = (config.pb * parameters.meanScale) / 4
        AlacEntropyDecoder.decode(
            reader, residual, samples, channelBits, config.mb, meanScale, config.kb,
        )
        if (parameters.mode != 0) {
            AlacPredictor.restore(
                residual, residual, samples, null, AlacPredictor.FIRST_ORDER_MODE,
                channelBits, denominatorShift = 0,
            )
        }
    }

    /**
     * 跳过元素头的实例标签与保留位。
     * 保留 12 位恒为 0，校验只会误判。
     */
    private fun skipElementHeader(reader: AlacBitReader) {
        reader.skip(ELEMENT_INSTANCE_TAG_BITS)
        reader.skip(UNUSED_HEADER_BITS)
    }

    private fun readElementFlags(reader: AlacBitReader): ElementFlags {
        val headerByte = reader.read(ELEMENT_FLAGS_BITS)
        val bytesShifted = (headerByte ushr 1) and 0x3
        if (bytesShifted == 3) throw IllegalArgumentException("附加低位字节数非法")
        return ElementFlags(
            partial = (headerByte ushr 3) != 0,
            bytesShifted = bytesShifted,
            escape = (headerByte and 0x1) != 0,
        )
    }

    /** 帧内样本数：自带则取位流，否则取配置帧长 */
    private fun resolveSampleCount(reader: AlacBitReader, flags: ElementFlags): Int {
        val samples = if (flags.partial) reader.read(FRAME_SAMPLE_COUNT_BITS) else config.frameLength
        if (samples < 1 || samples > config.frameLength) {
            throw IllegalArgumentException("样本数 $samples 超出 1..${config.frameLength}")
        }
        sampleCount = samples
        return samples
    }

    /**
     * 跳过附加低位段，返回起始位；无则 -1
     * 低位排在压缩数据后，解完预测才知归属，先记位置
     */
    private fun skipExtraLowBits(reader: AlacBitReader, flags: ElementFlags, count: Int): Int {
        if (flags.bytesShifted == 0) return NO_SHIFT_REGION
        val start = reader.position
        reader.skip(flags.bytesShifted * Byte.SIZE_BITS * count)
        return start
    }

    private fun readExtraLowBits(reader: AlacBitReader, start: Int, flags: ElementFlags, count: Int) {
        if (start == NO_SHIFT_REGION) return
        val resume = reader.position
        reader.position = start
        val bits = flags.bytesShifted * Byte.SIZE_BITS
        for (index in 0 until count) {
            extraLowBits[index] = reader.read(bits)
        }
        reader.position = resume
    }

    /**
     * 把附加低位并回样本。
     * 24/32 位并入低位，16/20 位读入后丢弃
     */
    private fun combineExtraLowBits(value: Int, lowBitIndex: Int, flags: ElementFlags): Int {
        if (flags.escape || !carriesExtraLowBits()) return value
        return (value shl (flags.bytesShifted * Byte.SIZE_BITS)) or extraLowBits[lowBitIndex]
    }

    private fun carriesExtraLowBits(): Boolean =
        config.bitDepth == 24 || config.bitDepth == 32

    /** 未压缩元素：按源码字宽排布；null 即单声道 */
    private fun readUncompressed(
        reader: AlacBitReader,
        samples: Int,
        channelBits: Int,
        mixedRightOrNull: IntArray?,
    ) {
        for (index in 0 until samples) {
            mixedLeft[index] = truncateToWord(reader.read(channelBits), channelBits)
            mixedRightOrNull?.let {
                it[index] = truncateToWord(reader.read(channelBits), channelBits)
            }
        }
    }

    private fun checkChannelBits(channelBits: Int) {
        if (channelBits < 1 || channelBits > Int.SIZE_BITS) {
            throw IllegalArgumentException("源码字宽 $channelBits 超出实现范围")
        }
    }

    private data class ElementFlags(
        val partial: Boolean,
        val bytesShifted: Int,
        val escape: Boolean,
    )

    private data class ChannelParameters(
        val mode: Int,
        val denominatorShift: Int,
        val meanScale: Int,
        val numActive: Int,
    )

    private companion object {
        const val TAG_BITS = 3
        const val ELEMENT_INSTANCE_TAG_BITS = 4
        const val UNUSED_HEADER_BITS = 12
        const val ELEMENT_FLAGS_BITS = 4
        const val FRAME_SAMPLE_COUNT_BITS = 32
        const val CHANNELS_PER_PAIR = 2

        /** 预测阶数占 5 位，系数缓冲按 32 个设 */
        const val MAX_COEFFICIENTS = 32

        const val NO_SHIFT_REGION = -1

        // 语法元素类型
        const val ID_SCE = 0
        const val ID_CPE = 1
        const val ID_LFE = 3
        const val ID_END = 7

        /** 按字宽取补码：低 bits 位按有符号解释 */
        fun truncateToWord(value: Int, bits: Int): Int {
            val shift = Int.SIZE_BITS - bits
            if (shift <= 0) return value
            return (value shl shift) shr shift
        }
    }
}
