package com.yichao.evilgodxu.data.music.playback.alac

/**
 * 单帧 ALAC 的解码：语法元素解包 → 熵解码 → 自适应预测 → 立体声解相关 → 逐声道样本。
 *
 * 一帧由若干「语法元素」组成，每个元素承载一个或两个声道。元素之间彼此独立：预测系数、自适应 Golomb 的
 * 均值与预测器历史都在元素内部从位流重新建立，帧与帧之间亦然——这正是 ALAC 可随机定位的原因，
 * 也让本类无需跨帧保存状态，[decode] 每次调用都从零起算，冲刷与跳转因此无需额外处理。
 *
 * 输出统一为「按源码字宽取补码」的整数样本，而不是像参考实现那样就地拼成某一种字节布局：
 * 那四套 16/20/24/32 位写法是同一批样本的四种落盘形式，落盘由输出编码决定（见 [AlacPcmEncoder]）。
 *
 * 本类不做线程安全保证：实例由单个解码线程独占。
 */
internal class AlacFrameDecoder(private val config: AlacSpecificConfig) {

    /** 逐声道的样本缓冲，每声道有效长度由 [sampleCount] 给出 */
    val channels: Array<IntArray> = Array(config.channelCount) { IntArray(config.frameLength) }

    /** 本次解码得到的每声道样本数 */
    var sampleCount: Int = 0
        private set

    /** 熵解码的残差缓冲；「二阶模式」下原地复用为预测器的输入 */
    private val residual = IntArray(config.frameLength)

    /** 解相关前的左右声道，单声道元素只用前者 */
    private val mixedLeft = IntArray(config.frameLength)
    private val mixedRight = IntArray(config.frameLength)

    /** 位深之外的附加低位，立体声时按左右交错存放 */
    private val extraLowBits = IntArray(config.frameLength * CHANNELS_PER_PAIR)

    /** 左右声道各自的预测系数，与参考实现一样按元素从位流读入而不跨帧沿用 */
    private val leftCoefficients = IntArray(MAX_COEFFICIENTS)
    private val rightCoefficients = IntArray(MAX_COEFFICIENTS)

    /**
     * 解一帧；[length] 是本帧在 [data] 中的字节数。
     *
     * 读到的内容不构成一条可解的流时抛异常：帧不完整、参数越界、字宽超出实现范围都属此类，
     * 交由上层决定如何处置，不在此处悄悄补零或截断。
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
                    // 声道对放不下整个元素时忽略它：多声道 ALAC 的元素与声道并非一一对应，
                    // 参考实现同样以「放不下就丢弃」收场
                    if (channelIndex + CHANNELS_PER_PAIR <= config.channelCount) {
                        decodeChannelPairElement(reader, channelIndex)
                        channelIndex += CHANNELS_PER_PAIR
                    }
                }
                // 结束元素之后只剩填充位：本帧的起止由容器给出（按样本表切分），故不必再对齐或校验余量
                ID_END -> return
                else -> throw IllegalArgumentException("不支持的语法元素 $tag")
            }
            if (channelIndex >= config.channelCount) return
        }
    }

    /** 单声道元素：一个声道一套参数，且不做解相关 */
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
        reader.read(8) // 解相关移位量：单声道无从解相关，读到即弃
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

    /** 声道对元素：两个声道各一套参数，解出后按位流给出的权重做解相关 */
    private fun decodeChannelPairElement(reader: AlacBitReader, channelIndex: Int) {
        skipElementHeader(reader)
        val flags = readElementFlags(reader)
        val samples = resolveSampleCount(reader, flags)
        val leftChannel = channels[channelIndex]
        val rightChannel = channels[channelIndex + 1]

        if (flags.escape) {
            // 未压缩元素不带解相关，样本直接按源码字宽左右交错排布
            readUncompressed(reader, samples, config.bitDepth, mixedRightOrNull = mixedRight)
            for (index in 0 until samples) {
                leftChannel[index] = truncateToWord(mixedLeft[index], config.bitDepth)
                rightChannel[index] = truncateToWord(mixedRight[index], config.bitDepth)
            }
            return
        }

        // 声道对的预测字宽比成品多一位：解相关会把两声道之差放大到该位宽
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
     * 读一个声道的预测参数，系数填入 [coefficients]。
     *
     * 阶数必须小于本元素的样本数：参考实现的前「阶数」个样本由残差直接累加得出，阶数不小于样本数时
     * 那个累加会越过本元素的样本范围。
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
            // 定点小数位数为 0 时四舍五入量无从计算，参考实现会在此走入未定义分支
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
     *
     * 预测模式非零时先原地做一遍「一阶差分」还原：那条分支的位流不以常规预测器为起点，
     * 参考实现正是以「先按 31 阶原地跑一遍再交给常规预测器」实现，此处照做。
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
     * 跳过元素头里的实例标签与保留位。
     *
     * 保留的 12 位当前恒为 0，此处不校验：两份公开实现对此也不一致（一份要求为 0，一份直接跳过），
     * 而该字段不参与任何后续计算，拒收非零值只会把编码器的书写习惯误判成不可解。
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

    /** 帧内样本数：自带样本数的帧以位流为准，否则取配置里的帧长 */
    private fun resolveSampleCount(reader: AlacBitReader, flags: ElementFlags): Int {
        val samples = if (flags.partial) reader.read(FRAME_SAMPLE_COUNT_BITS) else config.frameLength
        if (samples < 1 || samples > config.frameLength) {
            throw IllegalArgumentException("样本数 $samples 超出 1..${config.frameLength}")
        }
        sampleCount = samples
        return samples
    }

    /**
     * 跳过附加低位段并返回其起始位；无该段时返回 [NO_SHIFT_REGION]。
     *
     * 低位排在压缩数据之后，且要等预测解完才知道该补到哪个样本上，故先记位置再跳过，解完预测后回来读。
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
     *
     * 只有 24 位与 32 位两档的还原写法会并入低位；16 位与 20 位的写法没有这一形式，故读入后丢弃
     * （位深 16 本就恒无低位段，此处是照搬参考实现的分支划分）。
     */
    private fun combineExtraLowBits(value: Int, lowBitIndex: Int, flags: ElementFlags): Int {
        if (flags.escape || !carriesExtraLowBits()) return value
        return (value shl (flags.bytesShifted * Byte.SIZE_BITS)) or extraLowBits[lowBitIndex]
    }

    private fun carriesExtraLowBits(): Boolean =
        config.bitDepth == 24 || config.bitDepth == 32

    /** 未压缩元素：样本按源码字宽直接排布；[mixedRightOrNull] 为 null 表示单声道元素 */
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

        /** 位流里预测阶数占 5 位，参考实现的系数缓冲即按 32 个设 */
        const val MAX_COEFFICIENTS = 32

        const val NO_SHIFT_REGION = -1

        // 语法元素类型，取值与参考实现的 ELEMENT_TYPE 一致
        const val ID_SCE = 0
        const val ID_CPE = 1
        const val ID_LFE = 3
        const val ID_END = 7

        /** 按字宽取补码：低 [bits] 位重新解释为有符号数 */
        fun truncateToWord(value: Int, bits: Int): Int {
            val shift = Int.SIZE_BITS - bits
            if (shift <= 0) return value
            return (value shl shift) shr shift
        }
    }
}
