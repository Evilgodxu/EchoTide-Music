package com.yichao.evilgodxu.data.music.analysis

import com.yichao.evilgodxu.data.music.model.LyricWord
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// 逐字对齐引擎参数。默认值经合成真值标定，改动任一权重都会同时影响字边界落点与拖腔处理，
// 需重跑标定才能确认收益，故除 percKeep 外不建议调整
internal class AlignConfig(
    /** 分析采样率：16k 已覆盖人声 100~4000Hz 主能量区，同时把计算量压到最低 */
    val sr: Int = 16_000,
    /** 32ms 窗、10ms 帧移 */
    val nFft: Int = 512,
    val hop: Int = 160,
    /** 行前后额外取的音频上下文：供中值滤波取值与频谱估计使用，比 DP 搜索窗口宽 */
    val preCtx: Double = 0.35,
    val postCtx: Double = 0.25,
    /** DP 搜索窗口相对行起止的外扩余量：远小于分析上下文，避免把行间纯伴奏算作歌词 */
    val searchPre: Double = 0.10,
    val searchPost: Double = 0.12,
    /** 起音判决门限 = 噪声底 + gate × (峰值 − 噪声底)。分位阈值会把约三成帧都判成起音，
     *  故按局部极大值挑峰，该门限只负责把峰挡在噪声之上 */
    val onsetGate: Double = 0.45,
    /** 相邻起音的最小间隔：低于一个音节的物理时长即视为同一音的抖动 */
    val onsetGapMs: Double = 60.0,
    /** 起音包围盒后扩：起音帧落在辅音爆发点上，后扩吸收收音 */
    val onsetRelease: Double = 0.15,
    /** 首字起点相对行时间戳允许的偏移：行时间戳是行起唱的直接标注，首字只该吸收它自身的误差 */
    val firstSlack: Double = 0.12,
    /** 首字起点偏离行时间戳的代价系数（每网格） */
    val wFirst: Double = 0.30,
    /** Mid/Side 自适应泄漏消除：人声居中、伴奏声场更宽，据此从 mid 中减去与 side 相关的成分 */
    val useMs: Boolean = true,
    /** 时间轴/频率轴中值滤波长度（帧 / 频点） */
    val hpssTime: Int = 17,
    val hpssFreq: Int = 17,
    /** 软掩码分离余量：越大伴奏去得越干净、人声细节损失越多 */
    val hpssMargin: Double = 2.2,
    val hpssPower: Double = 2.0,
    /** 保留的打击分量比例：留住辅音与起音，调小则更干净 */
    val percKeep: Double = 0.25,
    /** 人声频带整形上下限：低截止抬高以压掉伴奏低频与呼吸噪声 */
    val fLo: Double = 200.0,
    val fHi: Double = 3500.0,
    /** DP 在 2 帧（20ms）网格上推进：精度损失可忽略，搜索量降为四分之一 */
    val dtwStep: Int = 2,
    /** 单字时长物理约束：唱不出比这更快或更慢的字 */
    val minCharMs: Double = 80.0,
    val maxCharMs: Double = 800.0,
    /** DP 三项权重：时长贴近先验 / 边界落在起音上 / 段内确实在发声 */
    val wDur: Double = 1.60,
    val wOnset: Double = 1.00,
    val wEnergy: Double = 0.90,
    /** 终点偏离「字数 × 字长先验」的代价：行区间留白（句尾伴奏）由此判为不划算 */
    val wEnd: Double = 0.50,
    /** 终点相对先验允许的提前 / 延后比例：前者留给提前收尾，后者留给句尾拖腔 */
    val endSlack: Double = 0.35,
    val endPad: Double = 0.60,
    /** 结果可用性下限：低于该置信度说明字边界没落在起音上，逐字时间不可用 */
    val minConfidence: Double = 0.10,
    /** 起音曲线锐化指数：拉开真边界与假起音的区分度 */
    val onsetSharp: Double = 1.5,
    /** 两轮 DP：第一轮估字长中位数，第二轮用其作先验，抵消句尾拖腔对平均字长的系统性拉偏 */
    val twoPass: Boolean = true,
) {
    val hopMs: Double get() = 1000.0 * hop / sr
    val nbins: Int get() = nFft / 2 + 1
}

/**
 * 逐字歌词对齐引擎：由「音频 + 行级时间戳歌词」推导每个字/词的起止时间。
 *
 * 单行链路：
 * 1. 按行起止时间戳切出音频段（前后各留少量上下文），内存占用与整轨时长无关；
 * 2. 人声分离两级串联 —— Mid/Side 自适应泄漏消除（时域）削掉与宽声场伴奏相关的成分，
 *    HPSS 软掩码（时频域）沿时间轴中值滤波取谐波分量作为人声主体，并保留少量打击分量留住辅音；
 * 3. 在分离后的人声谱上取能量包络与半波整流谱通量；
 * 4. 跨度默认取行区间（行时间戳标注的整行起止）；只有当行区间长于「字数 × 单字时长上限」、
 *    不可能再是一句连续演唱时，才改由起音包围盒定跨度；
 * 5. 以「本行有几个音节」为先验，在 20ms 网格上做带时长约束的单调 DP，全局最优地放置字边界。
 *    首字锚在行时间戳上，其余按起音与能量分布展开；结果不可信时放弃逐字，保持行级歌词。
 *
 * 实例持有全部预计算表（窗函数、旋转因子、频带权重），单次对齐任务内独占使用，不共享可变状态。
 */
internal class LyricWordAligner(private val cfg: AlignConfig = AlignConfig()) {

    private val nbins = cfg.nbins

    // 周期性 Hann 窗：STFT 帧内加权
    private val window = DoubleArray(cfg.nFft) { i ->
        0.5 - 0.5 * cos(2.0 * PI * i / cfg.nFft)
    }

    // 旋转因子按 FFT 长度缓存（2,4,…,nFft/2）：每帧重复求三角函数是主要开销之一
    private val twiddles = HashMap<Int, DoubleArray>()

    // 人声频带整形权重：带内为 1，带外平滑衰减而非直接归零，避免边界处产生振铃
    private val bandWeight = DoubleArray(nbins) { b ->
        val f = b * cfg.sr.toDouble() / cfg.nFft
        when {
            f < cfg.fLo -> max(0.15, f / max(cfg.fLo, 1e-6))
            f > cfg.fHi -> max(0.15, kotlin.math.exp(-(f - cfg.fHi) / 2500.0))
            else -> 1.0
        }
    }

    private val envLoBin = binIndex(200.0)
    private val envHiBin = binIndex(4000.0)

    private fun binIndex(hz: Double): Int {
        val df = cfg.sr.toDouble() / cfg.nFft
        return min(nbins - 1, (hz / df).toInt())
    }

    /**
     * 对齐单行歌词：返回逐字起止时间，无可对齐单元（整行只有标点/空白）时返回空列表。
     *
     * [pcm] 为该行前后各留少量上下文的音频段，[startMs] / [endMs] 为行级起止时间戳，
     * [text] 为该行原文。
     */
    fun alignLine(
        pcm: PcmSegment,
        startMs: Long,
        endMs: Long,
        text: String,
    ): List<LyricWord> {
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return emptyList()

        val frames = pcm.frames
        if (frames < cfg.nFft) return emptyList()
        val baseMs = pcm.startMs.toDouble()
        val left = DoubleArray(frames) { pcm.left[it] / 32768.0 }
        val right = DoubleArray(frames) { pcm.right[it] / 32768.0 }
        val vocalMag = separateVocal(left, right)
        val env = energyEnvelope(vocalMag)
        val flux = spectralFlux(vocalMag)
        val nf = env.size
        if (nf < 8) return emptyList()

        val framesPerSecond = cfg.sr.toDouble() / cfg.hop
        // 行起止在音频段内的帧位置：按实际时间差反推，音频开头截断上下文时仍然成立
        val idxStart = (((startMs - baseMs) / 1000.0) * framesPerSecond).toInt().coerceIn(0, nf - 1)
        val idxEnd = (idxStart + ((endMs - startMs) / 1000.0 * framesPerSecond).toInt())
            .coerceIn(idxStart, nf - 1)
        // DP 搜索窗口：行起止各外扩一点，用于吸收 LRC 行时间戳自身的误差
        val search0 = max(0, idxStart - (cfg.searchPre * framesPerSecond).toInt())
        val search1 = min(nf - 1, idxEnd + (cfg.searchPost * framesPerSecond).toInt())
        val msPerGrid = cfg.hopMs * cfg.dtwStep

        // 起音峰值即音节边界候选。挑峰数量按本行字数约束，使集合既覆盖音节又不被伴奏子拍淹没
        val peaks = pickOnsets(flux, search0, search1, tokens.size)

        // 跨度默认取行区间：行时间戳标的是整行的起止，在没有可靠人声区间估计的前提下，
        // 它仍是「本行歌词占据多少时间轴」的最好依据
        val span0 = search0
        var span1 = max(search0 + 4, search1)

        // 例外：行区间长于「字数 × 单字时长上限」时，它不可能是一句连续演唱的跨度
        // （行间留白、制作信息行都会如此）。此时单字上限会让 DP 无可行路径而退化成均匀铺满，
        // 于是同一句歌词在留白不同的两处会得到完全不同的字长；改用起音包围盒收窄跨度末端。
        // 起点仍取行时间戳 —— 不可信的是留白带来的多余尾巴，不是行的起唱标注。
        // 包围盒本身也可能落在伴奏上，故仍需末尾的结果校验兜底
        val phraseGrids = tokens.size * (cfg.maxCharMs / msPerGrid)
        val longWindow = (search1 - search0) / cfg.dtwStep > phraseGrids
        if (longWindow && peaks.size >= 2) {
            val release = (cfg.onsetRelease * framesPerSecond).toInt()
            val box1 = min(search1, peaks.last() + release)
            if (box1 - span0 >= tokens.size * (cfg.minCharMs / msPerGrid)) span1 = box1
        }

        // 首轮 DP 的末字锚点取最后一个起音：拖腔不该把前面的字挤到过短的区间里
        val anchorLast = if (peaks.size >= 2) peaks.last() else null
        var result = dpAlign(tokens, flux, env, span0, span1, anchorLast, null, idxStart)
        // 次轮：以首轮得到的字长中位数（排除句尾拖腔）作先验重跑，消除拖腔造成的系统性拉偏
        if (cfg.twoPass && result.feasible && result.edges.size >= 4) {
            val durations = ArrayList<Double>(result.edges.size - 2)
            for (i in 0 until result.edges.size - 2) {
                val d = result.edges[i + 1] - result.edges[i]
                if (d > 0) durations.add(d)
            }
            if (durations.isNotEmpty()) {
                val sorted = durations.toDoubleArray().apply { sort() }
                val median = sorted[sorted.size / 2] / cfg.dtwStep
                val second = dpAlign(tokens, flux, env, span0, span1, null, median, idxStart)
                if (second.feasible) result = second
            }
        }
        // DP 无可行路径或边界起音强度过低：宁可保持行级歌词，也不写出必然错误的逐字时序
        if (!result.feasible) return emptyList()
        if (result.confidence < cfg.minConfidence) return emptyList()
        val edges = result.edges
        if (edges.size != tokens.size + 1) return emptyList()

        // 结果校验：跨度与字数不自洽（单个字被迫超过物理时长上限）时，本行的时间戳与
        // 音频不属于同一段演唱 —— 常见于制作信息行、间奏留白，此时放弃逐字
        val meanCharMs = (edges.last() - edges.first()) * cfg.hopMs / tokens.size
        if (meanCharMs > cfg.maxCharMs) return emptyList()

        val edgesMs = DoubleArray(edges.size) { baseMs + edges[it] * cfg.hopMs }
        val words = ArrayList<LyricWord>(tokens.size)
        for (i in tokens.indices) {
            val wordStart = edgesMs[i]
            val wordEnd = if (i + 1 < edgesMs.size) edgesMs[i + 1] else edgesMs.last() + 120.0
            val safeEnd = if (wordEnd > wordStart) wordEnd else wordStart + 80.0
            words.add(
                LyricWord(
                    startMs = wordStart.roundToInt().toLong(),
                    durationMs = (safeEnd.roundToInt() - wordStart.roundToInt()).coerceAtLeast(1).toLong(),
                    text = tokens[i].text,
                )
            )
        }
        // 末字延伸到句尾拖腔结束，同时受两重上限约束：不超过「1.5 倍平均字长 + 300ms」，
        // 也不越过行末 —— 越过行末会让上一行的高亮压住下一行的开头
        if (words.size >= 2) {
            var sum = 0L
            for (i in 0 until words.size - 1) sum += words[i].durationMs
            val average = sum.toDouble() / (words.size - 1)
            val cap = words.last().startMs + 1.5 * average + 300
            val target = minOf(cap, endMs.toDouble())
            words[words.size - 1] = words.last().copy(
                durationMs = (target - words.last().startMs).toLong().coerceAtLeast(1)
            )
        }
        return words
    }

    // -----------------------------------------------------------------------
    // 人声分离
    // -----------------------------------------------------------------------

    /**
     * 两级人声分离后返回人声幅度谱（按 [帧 × 频点] 展平）。
     *
     * Mid/Side 泄漏消除削掉 mid 中与 side 相关的成分；HPSS 软掩码以谐波分量为
     * 人声主体，并掺入少量打击分量保住辅音起音，最后乘人声频带整形。
     */
    private fun separateVocal(left: DoubleArray, right: DoubleArray): DoubleArray {
        val n = left.size
        val mid = DoubleArray(n) { (left[it] + right[it]) * 0.5 }
        val side = DoubleArray(n) { (left[it] - right[it]) * 0.5 }

        var sideEnergy = 0.0
        var midEnergy = 0.0
        for (i in 0 until n) {
            sideEnergy += side[i] * side[i]
            midEnergy += mid[i] * mid[i]
        }
        val sideRatio = sqrt(sideEnergy / max(n, 1)) / max(sqrt(midEnergy / max(n, 1)), 1e-9)

        // 双单声道音源（Side 近零，部分 mp3 如此）下 Mid/Side 分离无意义，直接取 mid
        val vocal = if (cfg.useMs && sideRatio > 0.02) msLeakCancel(mid, side) else mid

        val (frameCount, magnitude) = stftMagnitude(vocal)
        val (maskHarmonic, maskPercussive) = hpssMasks(magnitude, frameCount)

        val out = DoubleArray(magnitude.size)
        for (f in 0 until frameCount) {
            val row = f * nbins
            for (b in 0 until nbins) {
                val mh = maskHarmonic[row + b]
                val mp = maskPercussive[row + b]
                val gain = (mh + cfg.percKeep * (1.0 - mh) * mp) * bandWeight[b]
                out[row + b] = magnitude[row + b] * gain
            }
        }
        return out
    }

    /**
     * Mid/Side 自适应泄漏消除：逐块最小二乘求 `α = <mid,side> / <side,side>`，
     * 输出 `mid − α·side`。被削掉的是 mid 中与宽声场伴奏相关的成分，居中原位的人声得以保留。
     */
    private fun msLeakCancel(mid: DoubleArray, side: DoubleArray): DoubleArray {
        val block = 512
        val out = DoubleArray(mid.size)
        var i = 0
        while (i < mid.size) {
            val end = min(mid.size, i + block)
            var num = 0.0
            var den = 0.0
            for (j in i until end) {
                num += mid[j] * side[j]
                den += side[j] * side[j]
            }
            if (den < 1e-8) {
                mid.copyInto(out, i, i, end)
            } else {
                // α 限幅：伴奏侧链能量波动时 α 可能异常放大，放任会误伤居中的人声
                val alpha = (num / den).coerceIn(-1.5, 1.5)
                for (j in i until end) out[j] = mid[j] - alpha * side[j]
            }
            i = end
        }
        return out
    }

    /**
     * HPSS 软掩码：沿时间轴中值滤波得谐波增强谱（人声元音/基频在谱图上是水平线），
     * 沿频率轴中值滤波得打击增强谱（鼓、镲、辅音是竖直瞬态），再生成软掩码。
     */
    private fun hpssMasks(magnitude: DoubleArray, frameCount: Int): Pair<DoubleArray, DoubleArray> {
        val harmonic = medianFilterTime(magnitude, frameCount, cfg.hpssTime)
        val percussive = medianFilterFreq(magnitude, frameCount, cfg.hpssFreq)
        val margin = cfg.hpssMargin
        val maskHarmonic = DoubleArray(magnitude.size)
        val maskPercussive = DoubleArray(magnitude.size)
        for (i in magnitude.indices) {
            val h = harmonic[i]
            val q = percussive[i]
            val hp = powP(h)
            val qm = powP(q * margin)
            val denomH = hp + qm
            maskHarmonic[i] = if (denomH > 1e-12) hp / denomH else 0.0
            val qp = powP(q)
            val hm = powP(h * margin)
            val denomP = qp + hm
            maskPercussive[i] = if (denomP > 1e-12) qp / denomP else 0.0
        }
        return maskHarmonic to maskPercussive
    }

    // 掩码幂次：默认幂为 2，退化为乘法以避开逐频点调用幂函数的开销
    private fun powP(x: Double): Double =
        if (cfg.hpssPower == 2.0) x * x else x.pow(cfg.hpssPower)

    // -----------------------------------------------------------------------
    // 极小 DSP 内核
    // -----------------------------------------------------------------------

    /** 短时傅里叶变换，返回 (帧数, 幅度谱[帧 × 频点] 展平)。 */
    private fun stftMagnitude(signal: DoubleArray): Pair<Int, DoubleArray> {
        val n = cfg.nFft
        val hop = cfg.hop
        val total = signal.size
        val frameCount = if (total >= n) 1 + (total - n) / hop else 1
        val magnitude = DoubleArray(frameCount * nbins)
        val frame = DoubleArray(n)
        val re = DoubleArray(nbins)
        val im = DoubleArray(nbins)
        for (f in 0 until frameCount) {
            val offset = f * hop
            for (i in 0 until n) {
                val sample = if (offset + i < total) signal[offset + i] else 0.0
                frame[i] = sample * window[i]
            }
            rfft(frame, re, im)
            val row = f * nbins
            for (b in 0 until nbins) magnitude[row + b] = sqrt(re[b] * re[b] + im[b] * im[b])
        }
        return frameCount to magnitude
    }

    /**
     * 实数序列 FFT：把 N 点实序列打包成 N/2 点复序列，一次 N/2 点复 FFT 即得半谱，
     * 比直接做 N 点复 FFT 少一半运算量。
     */
    private fun rfft(input: DoubleArray, outRe: DoubleArray, outIm: DoubleArray) {
        val n = input.size
        val m = n shr 1
        val zre = DoubleArray(m)
        val zim = DoubleArray(m)
        for (i in 0 until m) {
            zre[i] = input[2 * i]
            zim[i] = input[2 * i + 1]
        }
        fft(zre, zim)
        for (k in 0..m) {
            val a = k % m
            val b = (m - k) % m
            val zkr = zre[a]
            val zki = zim[a]
            val zcr = zre[b]
            val zci = -zim[b]
            val evenRe = (zkr + zcr) * 0.5
            val evenIm = (zki + zci) * 0.5
            val oddRe = (zkr - zcr) * 0.5
            val oddIm = (zki - zci) * 0.5
            // w × (−i) × odd，w = exp(−2πik/n)
            val angle = -2.0 * PI * k / n
            val wr = cos(angle)
            val wi = sin(angle)
            outRe[k] = evenRe + (wr * oddIm + wi * oddRe)
            outIm[k] = evenIm + (wi * oddIm - wr * oddRe)
        }
    }

    /** 迭代基 2 Cooley-Tukey FFT，原地运算。 */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val half = length shr 1
            val tw = twiddle(length)
            var i = 0
            while (i < n) {
                for (k in 0 until half) {
                    val wr = tw[2 * k]
                    val wi = tw[2 * k + 1]
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val xr = re[i + k + half]
                    val xi = im[i + k + half]
                    val vr = xr * wr - xi * wi
                    val vi = xr * wi + xi * wr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + half] = ur - vr
                    im[i + k + half] = ui - vi
                }
                i += length
            }
            length = length shl 1
        }
    }

    private fun twiddle(length: Int): DoubleArray = twiddles.getOrPut(length) {
        val half = length shr 1
        DoubleArray(length) { i ->
            val k = i shr 1
            val angle = -2.0 * PI * k / length
            if (i and 1 == 0) cos(angle) else sin(angle)
        }
    }

    /**
     * 1D 滑动中值滤波。窗口用有序数组维护，插入与删除均为 O(k) 内存搬移，
     * 优于逐窗口重新排序的 O(k log k)；边界按首尾样本填充。
     */
    private fun slidingMedian(seq: DoubleArray, k: Int): DoubleArray {
        if (k <= 1) return seq.copyOf()
        val half = k / 2
        val n = seq.size
        val padded = DoubleArray(n + 2 * half)
        for (i in 0 until half) padded[i] = seq[0]
        seq.copyInto(padded, half)
        for (i in 0 until half) padded[half + n + i] = seq[n - 1]
        val out = DoubleArray(padded.size - (k - 1))
        // 插入发生在删除之前，故窗口峰值长度为 k + 1
        val win = DoubleArray(k + 1)
        var winSize = 0
        for (i in padded.indices) {
            val value = padded[i]
            var lo = 0
            var hi = winSize
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (win[mid] <= value) lo = mid + 1 else hi = mid
            }
            System.arraycopy(win, lo, win, lo + 1, winSize - lo)
            win[lo] = value
            winSize++
            if (i >= k) {
                val removed = padded[i - k]
                var at = 0
                while (at < winSize && win[at] != removed) at++
                if (at < winSize) {
                    System.arraycopy(win, at + 1, win, at, winSize - at - 1)
                    winSize--
                }
            }
            if (i >= k - 1) {
                out[i - (k - 1)] = if (winSize % 2 == 1) {
                    win[winSize / 2]
                } else {
                    (win[winSize / 2 - 1] + win[winSize / 2]) * 0.5
                }
            }
        }
        return out
    }

    /** 沿时间轴（逐频点）中值滤波：谐波成分在谱图上是水平线，被保留下来。 */
    private fun medianFilterTime(magnitude: DoubleArray, frameCount: Int, k: Int): DoubleArray {
        val out = DoubleArray(magnitude.size)
        val column = DoubleArray(frameCount)
        for (b in 0 until nbins) {
            for (f in 0 until frameCount) column[f] = magnitude[f * nbins + b]
            val median = slidingMedian(column, k)
            for (f in 0 until frameCount) out[f * nbins + b] = median[f]
        }
        return out
    }

    /** 沿频率轴（逐帧）中值滤波：打击瞬态在谱图上是竖直线，被保留下来。 */
    private fun medianFilterFreq(magnitude: DoubleArray, frameCount: Int, k: Int): DoubleArray {
        val out = DoubleArray(magnitude.size)
        val row = DoubleArray(nbins)
        for (f in 0 until frameCount) {
            magnitude.copyInto(row, 0, f * nbins, f * nbins + nbins)
            val median = slidingMedian(row, k)
            median.copyInto(out, f * nbins, 0, min(median.size, nbins))
        }
        return out
    }

    // -----------------------------------------------------------------------
    // 特征提取
    // -----------------------------------------------------------------------

    /** 人声频带内的逐帧能量包络（RMS）。 */
    private fun energyEnvelope(magnitude: DoubleArray): DoubleArray {
        val frameCount = magnitude.size / nbins
        val span = max(envHiBin - envLoBin + 1, 1)
        return DoubleArray(frameCount) { f ->
            val row = f * nbins
            var sum = 0.0
            for (b in envLoBin..envHiBin) {
                val v = magnitude[row + b]
                sum += v * v
            }
            sqrt(sum / span)
        }
    }

    /**
     * 半波整流谱通量：能量上升处即新的发音起点，稳态伴奏在此特征上几乎不动。
     *
     * 首帧没有可比的参考帧，逐频点差值之和等于整帧能量，是个必然出现的孤立极大值。
     * 留着会污染起音挑峰（把它当成全窗最强起音），故首帧恒记 0，前帧基准取首帧自身。
     */
    private fun spectralFlux(magnitude: DoubleArray): DoubleArray {
        val frameCount = magnitude.size / nbins
        val flux = DoubleArray(frameCount)
        val span = envHiBin - envLoBin + 1
        val previous = DoubleArray(span)
        if (frameCount > 0) {
            for (b in 0 until span) previous[b] = magnitude[envLoBin + b]
        }
        for (f in 1 until frameCount) {
            val row = f * nbins
            var sum = 0.0
            for (b in envLoBin..envHiBin) {
                val value = magnitude[row + b]
                val delta = value - previous[b - envLoBin]
                if (delta > 0) sum += delta
                previous[b - envLoBin] = value
            }
            flux[f] = sum
        }
        return flux
    }

    /** 居中箱式平滑，边界按首尾样本填充。 */
    private fun boxcarSmooth(seq: DoubleArray, k: Int): DoubleArray {
        if (k <= 1) return seq.copyOf()
        val half = k / 2
        val n = seq.size
        val padded = DoubleArray(n + 2 * half)
        for (i in 0 until half) padded[i] = seq[0]
        seq.copyInto(padded, half)
        for (i in 0 until half) padded[half + n + i] = seq[n - 1]
        return DoubleArray(n) { i ->
            var sum = 0.0
            for (j in 0 until k) sum += padded[i + j]
            sum / k
        }
    }

    private fun percentile(values: DoubleArray, q: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.copyOf().apply { sort() }
        val index = min(sorted.size - 1, max(0, (q * (sorted.size - 1)).toInt()))
        return sorted[index]
    }

    /**
     * 在 [from, to] 内挑出起音帧，返回绝对帧号（升序），最多 [expected] 个。
     *
     * 判据是「在噪声之上、且彼此间隔不小于一个音节时长」的最强局部极大值 —— 按强度贪心取，
     * 取满即止。这样做的原因是谱通量里同时存在人声与伴奏的起振，单纯设阈要么漏掉弱起音、
     * 要么把伴奏的子拍也算进来；而本行的音节数是已知的，用它可以反过来约束检出数量，
     * 使起音集合既是边界候选、又能直接给出字长估计。
     */
    private fun pickOnsets(flux: DoubleArray, from: Int, to: Int, expected: Int): IntArray {
        val lo = max(0, from)
        val hi = min(flux.size - 1, to)
        val n = hi - lo + 1
        if (n < 5 || expected <= 0) return IntArray(0)
        val segment = DoubleArray(n) { flux[lo + it] }
        val smoothed = boxcarSmooth(segment, 3)
        val floor = percentile(smoothed, 0.50)
        val top = percentile(smoothed, 0.98)
        if (top - floor < 1e-12) return IntArray(0)
        val threshold = floor + cfg.onsetGate * (top - floor)
        val gap = max(1, (cfg.onsetGapMs / cfg.hopMs).toInt())

        // 工作量约为 expected × n，行内帧数在千级、字数在十级，代价可忽略
        val taken = BooleanArray(n)
        val peaks = ArrayList<Int>(expected)
        while (peaks.size < expected) {
            var bestIndex = -1
            var bestValue = threshold
            for (i in 1 until n - 1) {
                if (taken[i]) continue
                val value = smoothed[i]
                if (value <= bestValue) continue
                if (value < smoothed[i - 1] || value < smoothed[i + 1]) continue
                bestValue = value
                bestIndex = i
            }
            if (bestIndex < 0) break
            peaks.add(lo + bestIndex)
            for (i in max(0, bestIndex - gap)..min(n - 1, bestIndex + gap)) taken[i] = true
        }
        peaks.sort()
        return peaks.toIntArray()
    }

    // -----------------------------------------------------------------------
    // DP 强制对齐
    // -----------------------------------------------------------------------

    private fun downsampleMax(seq: DoubleArray, step: Int): DoubleArray =
        DoubleArray((seq.size + step - 1) / step) { i ->
            val from = i * step
            val to = min(seq.size, from + step)
            var best = Double.NEGATIVE_INFINITY
            for (j in from until to) if (seq[j] > best) best = seq[j]
            best
        }

    private fun downsampleMean(seq: DoubleArray, step: Int): DoubleArray =
        DoubleArray((seq.size + step - 1) / step) { i ->
            val from = i * step
            val to = min(seq.size, from + step)
            var sum = 0.0
            for (j in from until to) sum += seq[j]
            sum / (to - from)
        }

    /**
     * DP 对齐结果。
     *
     * [edges] 满足「首元素为首字起点，其后依次为每个字的终点」（共 N+1 项），仅在
     * [feasible] 为真时有效；不可行时 [edges] 的内容无意义，调用方必须放弃本行。
     */
    private class AlignResult(
        val edges: DoubleArray,
        val confidence: Double,
        val feasible: Boolean,
    )

    /**
     * 把 N 个音节对齐到 [span0, span1) 的帧网格上。
     *
     * 目标函数（越小越好）：
     * ```
     * Σ_i [ w_dur · ((d_i − d̄_i)/d̄_i)²      # 时长贴近先验，惩罚被拖腔拉长的字
     *     + w_onset · (1 − onset[b_i])      # 边界落在起音上
     *     + w_energy · (1 − ē_segment) ]    # 段内确实在发声
     * + w_first · |首字起点 − 行时间戳|        # 首字贴住行起唱标注
     * + w_end · |终点 − 字数 × 先验| / 先验   # 终点落在先验推算的收尾处附近
     * ```
     * 用带带宽约束的单调 DP 求全局最优，等价于受限 DTW。[lineStart] 为行时间戳在音频段内的帧位置。
     */
    private fun dpAlign(
        tokens: List<Token>,
        onset: DoubleArray,
        env: DoubleArray,
        span0: Int,
        span1: Int,
        anchorLast: Int?,
        avgOverride: Double?,
        lineStart: Int,
    ): AlignResult {
        val step = cfg.dtwStep
        val onsetDown = downsampleMax(onset, step)
        val envDown = downsampleMean(env, step)
        val g0 = span0 / step
        val g1 = max(g0 + 1, span1 / step)
        val grids = g1 - g0
        val count = tokens.size
        if (count == 0 || grids < 2) return AlignResult(DoubleArray(0), 0.0, false)

        val on = DoubleArray(grids) { onsetDown.getOrElse(g0 + it) { 0.0 } }
        val en = DoubleArray(grids) { envDown.getOrElse(g0 + it) { 0.0 } }
        // 用 95 分位而非最大值归一化，避免一个极端峰值把整行的起音曲线压扁。
        // 归一化结果刻意不截断到 1.0：强起音与「刚好过阈值的假起音」一旦一起饱和，
        // 起音项就失去区分度，字边界会明显变差
        val onsetScale = percentile(on, 0.95).takeIf { it > 0.0 } ?: 1e-9
        for (i in on.indices) on[i] = min(3.0, (on[i] / onsetScale).pow(cfg.onsetSharp))
        val energyScale = percentile(en, 0.95).takeIf { it > 0.0 } ?: 1e-9
        for (i in en.indices) en[i] = min(1.0, en[i] / energyScale)

        // 段内能量前缀和：DP 内以 O(1) 查询任意区间的平均能量
        val prefix = DoubleArray(grids + 1)
        for (i in 0 until grids) prefix[i + 1] = prefix[i] + en[i]

        val msPerGrid = cfg.hopMs * step
        val totalWeight = tokens.sumOf { it.weight }
        val lastAnchorGrid = anchorLast?.let { anchor ->
            val grid = anchor / step - g0
            if (count >= 2 && grid >= 0.45 * grids && grid <= grids - 2) grid else null
        }
        // 平均字长先验：句尾字常带拖腔，直接用「整段长度 ÷ 字数」会被拖腔拉大，
        // 导致每个字都被系统性唱长；有末字锚点时改用「首字起点 ~ 末字起点」这段主体区间估算
        val avgUnits = when {
            avgOverride != null && avgOverride > 0.0 -> avgOverride
            lastAnchorGrid != null -> {
                val headWeight = max(totalWeight - tokens.last().weight, 0.5)
                lastAnchorGrid / headWeight
            }
            else -> grids / totalWeight
        }
        val minGrid = max(1, (cfg.minCharMs / msPerGrid).roundToInt())
        val maxGrid = max(minGrid + 1, (cfg.maxCharMs / msPerGrid).roundToInt())
        // 跨度与字数不自洽时固定上下界会让 DP 无可行路径：跨度远大于「字数 × 单字上限」时
        // 需要放宽上界，远小于「字数 × 单字下限」时需要放宽下界，否则只能退化成均匀切分。
        // 放宽只是保证存在可行解，物理合理性由调用方对结果做校验
        val maxGridLine = max(maxGrid, ceil(grids.toDouble() / count).toInt())
        val minGridLine = min(minGrid, max(1, grids / (count + 1)))

        fun averageFor(weight: Double): Double =
            (avgUnits * weight).coerceIn(minGridLine + 0.5, maxGridLine - 0.5)

        val infinity = Double.POSITIVE_INFINITY
        val dp = Array(count) { DoubleArray(grids) { infinity } }
        val back = Array(count) { IntArray(grids) { -1 } }

        // 首字起点以行时间戳为锚，两侧各留 [firstSlack]。行时间戳是行起唱的直接标注，
        // 首字只该吸收它自身的误差；若只以跨度起点为限，整行音频的起音分布会把首字
        // 统一拖到时间戳之前，形成系统性提前
        val slack = max(1, (cfg.firstSlack * cfg.sr / cfg.hop / step).toInt())
        val anchorGrid = (lineStart / step - g0).coerceIn(0, grids - 1)
        val firstFrom = max(0, anchorGrid - slack)
        val firstTo = min(grids - 2, anchorGrid + slack)
        val firstAverage = averageFor(tokens[0].weight)
        for (s in firstFrom..firstTo) {
            val from = s + minGridLine
            val to = min(grids, s + maxGridLine + 1)
            for (j in from until to) {
                val d = (j - s).toDouble()
                val duration = ((d - firstAverage) / firstAverage).pow(2)
                val bound = 1.0 - on[s]
                val meanEnergy = (prefix[j] - prefix[s]) / max(d, 1.0)
                var cost = cfg.wDur * duration + cfg.wOnset * bound + cfg.wEnergy * (1.0 - meanEnergy)
                cost += cfg.wFirst * abs(s - anchorGrid)
                if (cost < dp[0][j]) {
                    dp[0][j] = cost
                    back[0][j] = s
                }
            }
        }
        for (i in 1 until count) {
            val average = averageFor(tokens[i].weight)
            val current = dp[i]
            val previous = dp[i - 1]
            val currentBack = back[i]
            for (j in (i + 1) * minGridLine until grids) {
                var best = infinity
                var bestK = -1
                val from = max(0, j - maxGridLine)
                val to = j - minGridLine
                for (k in from..to) {
                    val prev = previous[k]
                    if (prev == infinity) continue
                    val d = (j - k).toDouble()
                    val duration = ((d - average) / average).pow(2)
                    val bound = 1.0 - on[k]
                    val meanEnergy = (prefix[j] - prefix[k]) / max(d, 1.0)
                    val cost = prev + cfg.wDur * duration + cfg.wOnset * bound + cfg.wEnergy * (1.0 - meanEnergy)
                    if (cost < best) {
                        best = cost
                        bestK = k
                    }
                }
                current[j] = best
                currentBack[j] = bestK
            }
        }

        // 终点围绕「字数 × 字长先验」展开。原先偏好贴着跨度末端，而跨度又被 LRC 区间撑满，
        // 于是句尾伴奏必须由某些字来消纳，字长被顶到上限；改为围绕先验后，跨度长于实际演唱时
        // DP 会提前收尾，余下时间留给留白
        val last = count - 1
        val expectedEnd = count * avgUnits
        val endLo = max(0, (expectedEnd - cfg.endSlack * expectedEnd).roundToInt())
        val endHi = min(grids - 1, (expectedEnd + cfg.endPad * expectedEnd).roundToInt())
        var bestEnd = -1
        var bestCost = infinity
        // 先验窗内无可行解（末字锚点与跨度不自洽）时扩大到整段，故两段候选一并遍历，
        // 重叠区间被重复评估不影响结果
        for (j in (endLo..endHi) + (0 until grids)) {
            val cost = dp[last][j]
            if (cost == infinity) continue
            val total = cost + cfg.wEnd * abs(j - expectedEnd) / max(avgUnits, 1.0)
            if (total < bestCost) {
                bestCost = total
                bestEnd = j
            }
        }
        // DP 确实无可行路径：返回不可用结果，由调用方放弃本行的逐字时序。
        // 此处不能退化为均匀切分 —— 那会写出看上去正常、实际全错的逐字时间
        if (bestEnd < 0) return AlignResult(DoubleArray(0), 0.0, false)

        val boundaries = IntArray(count)
        var cursor = bestEnd
        for (i in count - 1 downTo 0) {
            boundaries[i] = cursor
            cursor = back[i][cursor]
            if (cursor < 0) break
        }
        val startGrid = if (cursor >= 0) cursor else 0

        // 置信度：边界处起音强度的均值，混入整体代价的一致性
        var onsetSum = 0.0
        var onsetCount = 0
        for (b in boundaries) {
            if (b < on.size) {
                onsetSum += on[b]
                onsetCount++
            }
        }
        val meanOnset = onsetSum / max(onsetCount, 1)
        val consistency = 1.0 - min(1.0, bestCost / max(count * 2.0, 1.0))
        val confidence = (0.7 * meanOnset + 0.3 * consistency).coerceIn(0.0, 1.0)

        val edges = DoubleArray(count + 1)
        edges[0] = (g0 + startGrid) * step.toDouble()
        for (i in 0 until count) edges[i + 1] = (g0 + boundaries[i]) * step.toDouble()
        return AlignResult(edges, confidence, true)
    }

    // -----------------------------------------------------------------------
    // 文本切分
    // -----------------------------------------------------------------------

    /**
     * 音节单元：中文/日文/韩文一字一音，拉丁词按元音组估算音节数，数字按位数占时。
     *
     * [text] 为原文片段（含挂靠的标点与空白），各单元文本首尾相接恒等于整行原文，
     * 保证逐字渲染与增强 LRC 回写不丢字符。
     */
    private class Token(val text: String, val weight: Double)

    private fun tokenize(text: String): List<Token> {
        val tokens = ArrayList<Token>()
        val pending = StringBuilder()
        for (match in TOKEN_REGEX.findAll(text)) {
            val cjk = match.groups[1]?.value
            val latin = match.groups[2]?.value
            val number = match.groups[3]?.value
            val base = cjk ?: latin ?: number
            if (base == null) {
                // 空白与标点不单独占时：挂到前一个单元上，行首时挂到下一个单元上。
                // 标点单独成单元会让字数虚高，空白独立成单元会丢掉词间间距
                if (tokens.isEmpty()) {
                    pending.append(match.value)
                } else {
                    val last = tokens.removeAt(tokens.size - 1)
                    tokens.add(Token(last.text + match.value, last.weight))
                }
                continue
            }
            val weight = when {
                cjk != null -> 1.0
                latin != null -> estSyllables(latin).toDouble()
                else -> max(1, number!!.length).toDouble()
            }
            tokens.add(Token(pending.toString() + base, weight))
            pending.clear()
        }
        // 整行只有标点或空白：没有可对齐单元
        return tokens
    }

    /** 英文音节数粗估：元音组计数，并去掉不发音的词尾 e。 */
    private fun estSyllables(word: String): Int {
        val cleaned = word.lowercase().filter { it in 'a'..'z' || it == '\'' }
        if (cleaned.isEmpty()) return 0
        var groups = 0
        var inVowel = false
        for (c in cleaned) {
            val isVowel = c in VOWELS
            if (isVowel && !inVowel) groups++
            inVowel = isVowel
        }
        if (groups > 1 && cleaned.endsWith("e") &&
            !cleaned.endsWith("le") && !cleaned.endsWith("ee") && !cleaned.endsWith("ye")
        ) {
            groups--
        }
        return max(1, groups)
    }

    private companion object {
        const val VOWELS = "aeiouy"
        const val CJK_RANGES = "\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\u3040-\u30ff\uac00-\ud7af"
        val TOKEN_REGEX = Regex("([$CJK_RANGES])|([A-Za-z']+)|([0-9]+)|(\\s+)|([^\\s])")
    }
}
