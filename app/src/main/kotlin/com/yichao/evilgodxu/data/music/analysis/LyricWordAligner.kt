package com.yichao.evilgodxu.data.music.analysis

import com.yichao.evilgodxu.data.music.model.LyricWord
import kotlin.math.PI
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
    /** 行前后额外取的音频上下文：供中值滤波与 VAD 使用，比 DP 搜索窗口宽 */
    val preCtx: Double = 0.35,
    val postCtx: Double = 0.25,
    /** DP 搜索窗口相对行起止的外扩余量：远小于分析上下文，避免把行间纯伴奏算作歌词 */
    val searchPre: Double = 0.10,
    val searchPost: Double = 0.12,
    val vadPad: Double = 0.06,
    /** VAD 阈值 = 噪声底 + thr × (峰值 − 噪声底) */
    val vadThr: Double = 0.40,
    /** VAD 允许合并的静音间隙（帧） */
    val vadGap: Int = 8,
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
 * 4. 区间定位用两套互补策略 —— 能量 VAD 兜住拖腔尾巴，起音包围盒对稳态伴奏免疫、负责定句首句尾；
 * 5. 以「本行有几个音节」为先验，在 20ms 网格上做带时长约束的单调 DP，全局最优地放置字边界。
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
        val spanDefault = search0 to max(search0 + 4, search1)

        // 稳态伴奏（pad/和弦）在能量上与持续人声一样连续，但在谱通量上几乎不动，
        // 故用起音包围盒定句首句尾、用能量 VAD 兜住拖腔尾巴，两者取并集
        val (vad0, vad1) = vadSpan(env, spanDefault, search0, search1)
        val searchOnset = DoubleArray(search1 - search0 + 1) { flux[search0 + it] }
        val strongThreshold = percentile(searchOnset, 0.72)
        val strong = ArrayList<Int>()
        for (i in searchOnset.indices) if (searchOnset[i] >= strongThreshold) strong.add(i)
        val hasStrong = strong.size >= 2
        val onset0 = if (hasStrong) search0 + strong.first() - 2 else null
        val onset1 = if (hasStrong) search0 + strong.last() + (0.15 * framesPerSecond).toInt() else null

        var s0 = if (onset0 != null) min(vad0, onset0) else vad0
        s0 = max(search0, s0)
        // VAD 与起音都明显晚于行时间戳：说明句首是弱起音被漏检，此处改信行时间戳
        if (s0 > idxStart + (0.30 * framesPerSecond).toInt()) {
            s0 = max(search0, idxStart - (0.10 * framesPerSecond).toInt())
        }
        var s1 = if (onset1 != null) max(vad1, onset1) else vad1
        s1 = min(search1, s1)
        if (s1 < idxEnd - (0.20 * framesPerSecond).toInt()) s1 = idxEnd

        var span0 = s0
        var span1 = max(s0 + 4, min(search1, s1))
        if (span1 - span0 < 4) {
            span0 = spanDefault.first
            span1 = spanDefault.second
        }

        // 首轮 DP 的末字锚点取最后一个强起音：拖腔不该把前面的字挤到过短的区间里
        val anchorLast = if (hasStrong) search0 + strong.last() else null
        var (edges, conf) = dpAlign(tokens, flux, env, span0, span1, anchorLast, null)
        // 次轮：以首轮得到的字长中位数（排除句尾拖腔）作先验重跑，消除拖腔造成的系统性拉偏
        if (cfg.twoPass && edges.size >= 4) {
            val durations = ArrayList<Double>(edges.size - 2)
            for (i in 0 until edges.size - 2) {
                val d = edges[i + 1] - edges[i]
                if (d > 0) durations.add(d)
            }
            if (durations.isNotEmpty()) {
                val sorted = durations.toDoubleArray().apply { sort() }
                val median = sorted[sorted.size / 2] / cfg.dtwStep
                val second = dpAlign(tokens, flux, env, span0, span1, null, median)
                if (second.first.isNotEmpty()) {
                    edges = second.first
                    conf = second.second
                }
            }
        }
        if (edges.isEmpty()) return emptyList()

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
        // 末字延伸到行末以覆盖尾音拖腔，但不超过「1.5 倍平均字长 + 300ms」，
        // 否则长拖腔会把最后一个字撑成整行
        if (words.size >= 2) {
            var sum = 0L
            for (i in 0 until words.size - 1) sum += words[i].durationMs
            val average = sum.toDouble() / (words.size - 1)
            val cap = (words.last().startMs + 1.5 * average + 300).toInt()
            val tail = min(endMs + 60, cap.toLong())
            if (words.last().startMs + words.last().durationMs < tail) {
                words[words.size - 1] = words.last().copy(durationMs = (tail - words.last().startMs).coerceAtLeast(1))
            }
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

    /** 半波整流谱通量：能量上升处即新的发音起点，稳态伴奏在此特征上几乎不动。 */
    private fun spectralFlux(magnitude: DoubleArray): DoubleArray {
        val frameCount = magnitude.size / nbins
        val flux = DoubleArray(frameCount)
        val previous = DoubleArray(envHiBin - envLoBin + 1)
        for (f in 0 until frameCount) {
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
     * 在搜索窗口内定位人声活动区间：噪声底加相对阈值二值化 → 合并短间隙 →
     * 取最长连续段 → 外扩。取最长覆盖段而非首尾超阈点，可避免行间伴奏的孤立峰值把区间撑大。
     */
    private fun vadSpan(env: DoubleArray, default: Pair<Int, Int>, search0: Int, search1: Int): Pair<Int, Int> {
        if (env.isEmpty()) return default
        val lo = search0.coerceIn(0, env.size - 1)
        val hi = search1.coerceIn(0, env.size - 1)
        // 窗口过窄时不足以估计噪声底与峰值，直接沿用默认区间
        if (hi - lo < 2) return default
        val segment = DoubleArray(hi - lo + 1) { env[lo + it] }
        val smoothed = boxcarSmooth(segment, 5)
        val floor = percentile(smoothed, 0.20)
        val top = percentile(smoothed, 0.98)
        if (top - floor < 1e-9) return default
        val threshold = floor + cfg.vadThr * (top - floor)

        val runs = ArrayList<IntArray>()
        var current: IntArray? = null
        for (i in smoothed.indices) {
            if (smoothed[i] >= threshold) {
                val run = current
                if (run == null) current = intArrayOf(i, i) else run[1] = i
            } else if (current != null) {
                runs.add(current)
                current = null
            }
        }
        current?.let { runs.add(it) }
        if (runs.isEmpty()) return default

        // 合并间隙不超过 vadGap 的相邻段：人声在字间会有极短停顿，不应把一行切成多段
        val merged = ArrayList<IntArray>()
        merged.add(runs.first())
        for (i in 1 until runs.size) {
            val run = runs[i]
            val last = merged.last()
            if (run[0] - last[1] <= cfg.vadGap) last[1] = run[1] else merged.add(run)
        }
        val best = merged.maxByOrNull { it[1] - it[0] } ?: return default

        val pad = (cfg.vadPad * cfg.sr / cfg.hop).toInt()
        val start = max(0, best[0] - pad) + lo
        val end = min(env.size - 1, best[1] + pad + lo)
        if (end - start < 3) return default
        return start to end
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
     * 把 N 个音节对齐到 [span0, span1) 的帧网格上，返回 (边界帧序列, 置信度)。
     *
     * 目标函数（越小越好）：
     * ```
     * Σ_i [ w_dur · ((d_i − d̄_i)/d̄_i)²      # 时长贴近先验，惩罚被拖腔拉长的字
     *     + w_onset · (1 − onset[b_i])      # 边界落在起音上
     *     + w_energy · (1 − ē_segment) ]    # 段内确实在发声
     * ```
     * 用带带宽约束的单调 DP 求全局最优，等价于受限 DTW。返回的序列首元素为首字起点，
     * 其后依次为每个字的终点（共 N+1 项）。
     */
    private fun dpAlign(
        tokens: List<Token>,
        onset: DoubleArray,
        env: DoubleArray,
        span0: Int,
        span1: Int,
        anchorLast: Int?,
        avgOverride: Double?,
    ): Pair<DoubleArray, Double> {
        val step = cfg.dtwStep
        val onsetDown = downsampleMax(onset, step)
        val envDown = downsampleMean(env, step)
        val g0 = span0 / step
        val g1 = max(g0 + 1, span1 / step)
        val grids = g1 - g0
        val count = tokens.size
        if (count == 0 || grids < 2) return DoubleArray(0) to 0.0

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

        fun averageFor(weight: Double): Double = max(minGrid + 0.5, avgUnits * weight)

        val infinity = Double.POSITIVE_INFINITY
        val dp = Array(count) { DoubleArray(grids) { infinity } }
        val back = Array(count) { IntArray(grids) { -1 } }

        // 首字起点允许在行起点附近小幅滑动，用于吸收 LRC 行时间戳自身的误差
        val slack = max(1, (0.12 * cfg.sr / cfg.hop / step).toInt())
        val firstAverage = averageFor(tokens[0].weight)
        for (s in 0 until min(slack, grids - 1)) {
            val from = s + minGrid
            val to = min(grids, s + maxGrid + 1)
            for (j in from until to) {
                val d = (j - s).toDouble()
                val duration = ((d - firstAverage) / firstAverage).pow(2)
                val bound = 1.0 - on[s]
                val meanEnergy = (prefix[j] - prefix[s]) / max(d, 1.0)
                var cost = cfg.wDur * duration + cfg.wOnset * bound + cfg.wEnergy * (1.0 - meanEnergy)
                // 推迟起唱要付一点代价：避免弱起句把首字整体后移
                if (s > 0) cost += 0.5 * s
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
            for (j in (i + 1) * minGrid until grids) {
                var best = infinity
                var bestK = -1
                val from = max(0, j - maxGrid)
                val to = j - minGrid
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

        // 终点：末字允许提前收尾，但越靠近 VAD 末端越好
        val last = count - 1
        var bestEnd = -1
        var bestCost = infinity
        val tailSlack = max(1, (0.25 * cfg.sr / cfg.hop / step).toInt())
        for (j in max(0, grids - 1 - tailSlack) until grids) {
            val cost = dp[last][j]
            if (cost == infinity) continue
            val total = cost + 0.8 * (grids - 1 - j) / tailSlack
            if (total < bestCost) {
                bestCost = total
                bestEnd = j
            }
        }
        if (bestEnd < 0) {
            // 兜底：DP 无可达路径时退化为均匀切分，置信度给低值提示结果不可信
            val edges = DoubleArray(count) { i -> (g0 + (grids * (i + 1).toDouble() / count).roundToInt()) * step.toDouble() }
            return edges to 0.2
        }

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
        return edges to confidence
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
