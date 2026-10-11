package com.yichao.evilgodxu.data.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.PcmAudioUtil
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

// 奇数相位抽头数：延迟（半程）与过渡带宽度都由它一并决定，取值理由见文件末尾的设计说明
private const val PHASE_TAPS = 96

// Kaiser 窗形状参数：按约 -100dB 的阻带设计
private const val KAISER_BETA = 10.0

// 贝塞尔级数项数：β=10 时已落到双精度极限之下，再多项不影响系数取值
private const val BESSEL_TERMS = 25

/**
 * 同族整数倍：设备声明的采样率相对源采样率的倍率，不属于同族整数倍时为 null。
 *
 * 判据只有两条：一是**整除**（否则本就不存在整数倍关系），二是**倍率为 2 的幂**——44.1 族的
 * 88.2/176.4/352.8 与 48 族的 96/192/384 都落在这一支，两支各自成族、时钟同源。44.1→96、
 * 44.1→132.3 这类跨族或非 2 的幂的比值不做：那已经是真正的重采样，本应用的滤器换不来好处，
 * 宁可把这件事交回系统（见 [SameFamilyUpsampler]）。
 *
 * 倍率上限见 [SameFamilyUpsampler.MAX_MULTIPLE]：再高已无实际设备，数据量却成倍往上翻。
 */
internal fun sameFamilyMultiple(sourceSampleRate: Int, declaredSampleRate: Int): Int? {
    if (sourceSampleRate <= 0 || declaredSampleRate <= sourceSampleRate) return null
    if (declaredSampleRate % sourceSampleRate != 0) return null
    val multiple = declaredSampleRate / sourceSampleRate
    if (multiple > SameFamilyUpsampler.MAX_MULTIPLE) return null
    if (multiple and (multiple - 1) != 0) return null
    return multiple
}

/**
 * 本应用能插值的线性 PCM 编码：浮点与三种打包整型。
 *
 * 8 位、大端变体与双精度不在其中——它们不由本应用的输出链路产出，真出现说明链路已被改动，
 * 此时不该按倍率档去做一件做不到的事（见 [selectSameFamilyUpsample] 的门槛）。
 */
internal fun canInterpolatePcm(encoding: Int): Boolean = when (encoding) {
    C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT -> true
    else -> false
}

/**
 * 同族整数倍插值：把解码输出按 2 的幂倍率放大，供设备未声明源采样率、却声明了同族倍率的档位使用。
 *
 * 要解决的问题：设备（USB 解码器的动态混音端口）只声明 88.2kHz 而不声明 44.1kHz 时，源格式直出挑不出
 * 档位，只能交回系统混音——而系统混音器会把 44.1kHz 重采样到设备当前那档采样率（通常是 48kHz，**跨族**）。
 * 本类按同族倍率把数据补齐到设备声明的采样率上，使直出在这种设备上也能成立：数据不经系统重采样器，
 * 输出率又落在设备确实支持的档位上（时钟同族，设备侧不必再做一次异步转换）。
 *
 * **倍率数据由本应用补齐，这是全部前提**：只把偏好设成 88.2 而仍写 44.1 的帧，既挂不上专用输出流
 * （写出格式与混音器属性三项须逐字段一致，否则 AudioFlinger 静默混音，见 [UsbDirectOutput]），
 * 也就没有绕开任何东西。
 *
 * 保真的边界要说清楚（`docs/注意事项.md` 的「同族倍率档」一节）：
 * - **原样本逐位保留**，且不靠系数取整去兑现——半带结构把偶数相位做成「照抄半程之前那一个」，
 *   奇数相位才是插值出来的新样本。故这不是「近似还原」，而是构造上的保证；
 * - **不是位完美**：输出确实多了样本，DAC 也按倍率跑。换到的是「不经系统任意比重采样」与「不跨时钟族」；
 * - 解码输出的中间态用双精度：浮点与 32 位整型都能被它无损容纳，原样本因此可以原样写回
 *   （内部若用单精度，32 位整型源会在这里丢低位）。
 *
 * 倍率为 2 的幂，逐级级联同一级半带（×4 即两级）：每级都把偶数相位做实为照抄，故原样本在每一级都
 * 原样落在偶位，级联之后落在每第 `倍率` 个输出样点上，逐位不变。
 *
 * 延迟与收尾：半带滤波器有 [PHASE_TAPS]/2 个源样本的固有延迟，输出因此比输入晚约 1ms（44.1kHz 下），
 * 且**收尾时须调一次 [drain]**——尾段的原样本还在延迟线里，不冲出来就是丢样本。延迟期间不写出任何
 * 东西，故从零历史开始的第一段是静音渐入（与媒体3 的处理器同类，界面上表现为曲首略短的一段淡入）。
 *
 * 线程：与写出同线程（音频线程），不加锁。
 */
@OptIn(UnstableApi::class)
internal class SameFamilyUpsampler(
    /** 倍率：2 的幂 */
    val multiple: Int,
) {

    init {
        require(multiple in 2..MAX_MULTIPLE && multiple and (multiple - 1) == 0) {
            "同族倍率须为 2 的幂且不超过 $MAX_MULTIPLE：$multiple"
        }
    }

    /** 级数：每级 ×2，级联即得倍率 */
    private val stageCount = Integer.numberOfTrailingZeros(multiple)

    /**
     * 各级每声道的延迟线，长度 [PHASE_TAPS]。
     *
     * 一条环同时供两个相位读：偶数相位取半程之前的那一个，奇数相位以当前样点为起点反向绕整圈。
     * 环长取抽头数即够——两个相位要读的最远位置都在整圈之内。
     */
    private var rings = Array(stageCount) { emptyArray<DoubleArray>() }

    /** 各级的写出游标：环内下一个待写位置 */
    private var cursors = IntArray(stageCount)

    /** 各级的落点：第 0 级存输入，其后每级是上一级的输出（帧数逐级翻倍） */
    private var levels = Array(stageCount + 1) { emptyArray<DoubleArray>() }

    /**
     * 收尾要冲出的源帧数。
     *
     * 每级延迟为半程抽头，但以**自身输入**计：折算到源采样率即逐级减半，故按 `48 + 24 + 12 …` 累加。
     * 逐级向上取整（多冲一点点）而不是向下：欠冲会把尾段的原样本留在延迟线里丢掉，
     * 多冲只是多出几个零样点插值出来的静音。
     */
    private val tailFrames: Int =
        (0 until stageCount).sumOf { (DELAY_SAMPLES + (1 shl it) - 1) shr it }

    /** 收尾冲段恰好写出的输出帧数：按倍率放大后的延迟段 */
    val drainFrames: Int get() = tailFrames * multiple

    /** [inputFrames] 帧输入按倍率展开所需的输出缓冲字节数 */
    fun outputBytes(inputFrames: Int, channelCount: Int, encoding: Int): Int =
        inputFrames * multiple * Util.getPcmFrameSize(encoding, channelCount)

    /**
     * 读完整块输入，把插值结果写进 [output]；输入被消费到末位，返回写出的输出帧数。
     *
     * [output] 的剩余空间须不小于 [outputBytes]。
     */
    fun process(input: ByteBuffer, channelCount: Int, encoding: Int, output: ByteBuffer): Int {
        val frames = input.remaining() / Util.getPcmFrameSize(encoding, channelCount)
        prepare(channelCount, frames)
        readInterleaved(input, levels[0], frames, channelCount, encoding)
        return interpolateAll(frames, channelCount, encoding, output)
    }

    /**
     * 收尾：把延迟线里的尾段冲出来，返回写出的输出帧数（恒为 [drainFrames]）。
     *
     * 输入侧按静音补齐——曲末之后本就是无声，与「假设信号在此结束」一致；不冲则最后约 1ms 的
     * 原样本不会写出（见类注释的收尾一节）。
     */
    fun drain(channelCount: Int, encoding: Int, output: ByteBuffer): Int {
        prepare(channelCount, tailFrames)
        fillSilence(levels[0], tailFrames, channelCount)
        return interpolateAll(tailFrames, channelCount, encoding, output)
    }

    /**
     * 复位：延迟线与游标归零，下一条流从零历史开始。
     *
     * 必须清内容而不只是挪游标：奇数相位要绕整圈读，未写过的格子留着上一条流的样点就会混进输出。
     */
    fun reset() {
        cursors.fill(0)
        for (stage in 0 until stageCount) {
            for (line in rings[stage]) line.fill(0.0)
        }
    }

    private fun prepare(channelCount: Int, frames: Int) {
        if (channelCount <= 0) return
        for (stage in 0 until stageCount) {
            val current = rings[stage]
            if (current.size != channelCount) {
                rings[stage] = Array(channelCount) { DoubleArray(PHASE_TAPS) }
            }
        }
        for (level in levels.indices) {
            val levelFrames = frames shl level
            val current = levels[level]
            if (current.size != channelCount || current[0].size < levelFrames) {
                levels[level] = Array(channelCount) { DoubleArray(levelFrames) }
            }
        }
    }

    private fun interpolateAll(
        frames: Int,
        channelCount: Int,
        encoding: Int,
        output: ByteBuffer,
    ): Int {
        var produced = frames
        for (stage in 0 until stageCount) {
            produced = interpolate(stage, levels[stage], levels[stage + 1], produced, channelCount)
        }
        writeInterleaved(levels[stageCount], output, produced, channelCount, encoding)
        return produced
    }

    /**
     * 一级 ×2：偶数位照抄半程之前的样点，奇数位由奇数相位系数插得。
     *
     * 两个相位的延迟一致（都是半程），故输出是均匀的 2 倍采样——若两个相位各按自己的窗口中心对齐，
     * 输出就变成两种延迟交替的相位调制，那不是插值。
     */
    private fun interpolate(
        stage: Int,
        source: Array<DoubleArray>,
        dest: Array<DoubleArray>,
        frames: Int,
        channelCount: Int,
    ): Int {
        val ring = rings[stage]
        val ringSize = ring[0].size
        var cursor = cursors[stage]
        for (frame in 0 until frames) {
            for (channel in 0 until channelCount) {
                val line = ring[channel]
                line[cursor] = source[channel][frame]
                var odd = 0.0
                var index = cursor
                for (tap in phaseCoefficients.indices) {
                    odd += phaseCoefficients[tap] * line[index]
                    index = if (index == 0) ringSize - 1 else index - 1
                }
                dest[channel][2 * frame] = line[(cursor - DELAY_SAMPLES + ringSize) % ringSize]
                dest[channel][2 * frame + 1] = odd
            }
            cursor = if (cursor + 1 == ringSize) 0 else cursor + 1
        }
        cursors[stage] = cursor
        return frames * 2
    }

    private fun readInterleaved(
        source: ByteBuffer,
        dest: Array<DoubleArray>,
        frames: Int,
        channelCount: Int,
        encoding: Int,
    ) {
        for (frame in 0 until frames) {
            for (channel in 0 until channelCount) {
                dest[channel][frame] = readSample(source, encoding)
            }
        }
    }

    private fun writeInterleaved(
        source: Array<DoubleArray>,
        dest: ByteBuffer,
        frames: Int,
        channelCount: Int,
        encoding: Int,
    ) {
        for (frame in 0 until frames) {
            for (channel in 0 until channelCount) {
                writeSample(dest, encoding, source[channel][frame])
            }
        }
    }

    private fun fillSilence(dest: Array<DoubleArray>, frames: Int, channelCount: Int) {
        for (channel in 0 until channelCount) dest[channel].fill(0.0, 0, frames)
    }

    // 读入即双精度：浮点与 32 位整型都能被它无损容纳，原样本因此可以原样写回
    private fun readSample(source: ByteBuffer, encoding: Int): Double =
        if (encoding == C.ENCODING_PCM_FLOAT) {
            source.float.toDouble()
        } else {
            PcmAudioUtil.readAs32BitIntPcm(source, encoding).toDouble()
        }

    private fun writeSample(dest: ByteBuffer, encoding: Int, value: Double) {
        if (encoding == C.ENCODING_PCM_FLOAT) {
            dest.putFloat(value.toFloat())
        } else {
            PcmAudioUtil.write32BitIntPcm(dest, quantize(value, encoding), encoding)
        }
    }

    /**
     * 按目标编码的位深取整。
     *
     * 取整而非截断：截断会给每个插值样本带进半个最低位的负偏置（16 位下约 -90dBFS 的直流），
     * 取整只把误差摊在 ±半位内。原样本本就是精确整数，除以步长再乘回逐位还原，不受此处影响。
     */
    private fun quantize(value: Double, encoding: Int): Int {
        val step = when (encoding) {
            C.ENCODING_PCM_16BIT -> 1 shl 16
            C.ENCODING_PCM_24BIT -> 1 shl 8
            else -> 1
        }
        return ((value / step).roundToLong() * step)
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
    }

    companion object {
        /** 倍率上限：再高已无实际设备，数据量与延迟却成倍往上翻 */
        const val MAX_MULTIPLE = 8

        /** 半带原型的中心落在半程处，延迟样本数即半程抽头 */
        private const val DELAY_SAMPLES = PHASE_TAPS / 2

        /** 奇数相位系数：一次算好，各级各级流共用 */
        private val phaseCoefficients: DoubleArray by lazy { buildPhaseCoefficients() }
    }
}

/**
 * 奇数相位系数：加窗 sinc 在半样本偏移处的取值，归一到合计 1。
 *
 * 抽头取奇数相位（半带的偶数抽头恒为 0，只有中心抽头 0.5 参与偶数相位），偏移即样本相对插值点的
 * 时间差、单位为源样本——中心落在抽头序号的半整数处，故取 `tap + 0.5 - 抽头数/2`。
 * 归一到合计 1 是**必需**的：插值相位的直流增益若不是 1，常值输入会在插值样本上留下固定偏置，
 * 那不是「不改变信号」。
 */
private fun buildPhaseCoefficients(): DoubleArray {
    val raw = DoubleArray(PHASE_TAPS) { tap ->
        val offset = tap + 0.5 - PHASE_TAPS / 2.0
        sinc(offset) * kaiser(2 * offset / PHASE_TAPS)
    }
    val sum = raw.sum()
    return DoubleArray(PHASE_TAPS) { raw[it] / sum }
}

private fun sinc(t: Double): Double = if (t == 0.0) 1.0 else sin(PI * t) / (PI * t)

/** Kaiser 窗：[x] 为归一化位置（两端为 ±1） */
private fun kaiser(x: Double): Double =
    besselI0(KAISER_BETA * sqrt(1.0 - x * x)) / besselI0(KAISER_BETA)

/** 零阶修正贝塞尔函数：标准级数展开 */
private fun besselI0(x: Double): Double {
    var sum = 1.0
    var term = 1.0
    val half = x / 2
    for (k in 1..BESSEL_TERMS) {
        term *= (half / k) * (half / k)
        sum += term
    }
    return sum
}
