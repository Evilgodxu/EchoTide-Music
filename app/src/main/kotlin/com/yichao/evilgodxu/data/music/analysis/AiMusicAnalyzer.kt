package com.yichao.evilgodxu.data.music.analysis

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// AI 音乐识别器：两级判定。
// ① 生成器署名取证：读取容器元数据，命中生成链自动注入的标识（C2PA 内容凭证、
//    编码器/工具字段中的生成器产品名、显式生成声明）即直接判 AI，不做频谱分析——
//    签名是生成器自报的出身确证，比任何统计推断都强。详见 AiSourceTagProbe。
// ② 频谱征象判定：未取得署名证据时，回退到规则启发式多征象合成，针对神经声码器/
//    合成链路的统计痕迹，覆盖全格式本地文件。
// 单曲入口与批量分析走共享 SpectralDecoder 的分段采样（3 段每段 4 秒）；用户查看过频谱的曲目
// 由 FullSpectrumAnalyzer 在全曲解码上复用同一征象集重跑并锁定，此后分段采样不再改写其结论。
// 征象集（均提取自共享 SpectralDecoder 的同一解码摘要）：
//  ① 立体声相关性：真人混音因摆位/混响左右声道去相关，AI 由单声道骨干扩立体声相关性偏高；
//  ② 12-18kHz 尖锐缺口 + 上方回升：32k 中间格式升频至 44.1k 的成像缺口，区别于持续滚降；
//  ③ 1-8kHz 谐波梳：反卷积零插值在平均谱上留下等间距规则峰列；
//  ④ 无损容器内的非原生带宽：仅无损容器适用，且单独不构成证据（见 detectAiSignals）。
// 合成规则：常规路径要求 ①②③ 至少两条同时命中；补充证据路径要求 ④ 与「带限已归因」
// 并存——带限本身在真实录音中亦存在，须由音质异常判据排除自然限带后才与 AI 判定合并。
// 持久化缓存与批量增量校验复用 TrackVerdictCache，与音质异常识别同语义。
internal object AiMusicAnalyzer {

    // AI 音乐智能歌单过滤键：与本地化展示名解耦，保证序列化歌单 key 跨语言环境稳定
    const val AI_MUSIC_KEY = "ai-music"

    // 识别结果缓存：键含文件大小与时长，文件变化即失效；供合并批量分析共享复用
    internal val cache = TrackVerdictCache(TrackVerdictCache.FILE_NAME_AI_MUSIC)

    // ---- 征象阈值（识别策略升级时经「刷新」对未锁定曲目强制重扫后生效）----
    // 征象①：中高频左右声道相关性下界
    private const val AI_STEREO_CORRELATION_MIN = 0.93f
    // 征象①相关性统计所需最少样本数（约 0.1 秒），不足视为无证据
    private const val AI_STEREO_MIN_SAMPLES = 4096L
    // 征象②：缺口深于两侧包围带的下界与缺口上方回升下界（dB）
    private const val AI_NOTCH_MIN_DIP_DB = 4.5f
    private const val AI_NOTCH_RECOVERY_DB = 2f
    // 征象③：谐波梳所需最少峰数、残差突出度（dB）与峰距一致性方差系数上界
    private const val AI_COMB_MIN_PEAKS = 6
    private const val AI_COMB_RESIDUE_DB = 4f
    private const val AI_COMB_MAX_CV = 0.30f

    // 征象④：内容带宽相对容器奈奎斯特的上界——超过该比例视为内容已用满容器带宽，
    // 不构成「无损容器装箱带限内容」的反常组合
    private const val AI_BANDWIDTH_MAX_RATIO = 0.90f

    // 征象④的截止估计口径：自奈奎斯特向下按带宽窗求均值，取首个高于噪底门限的窗上缘。
    // 逐 bin 取「高于噪底 3dB」会被死区里的窄幅抖动带到奈奎斯特附近——实测死区仅 ±3.5dB 起伏
    // 即足以让逐 bin 估计跳到 23.9kHz，把带限内容误判成带宽用满；带宽平均后抖动被压平，
    // 测得的才是内容真正的能量边界
    private const val BANDWIDTH_BAND_HZ = 500f
    private const val BANDWIDTH_BAND_ABOVE_FLOOR_DB = 20f

    // 征象④与音质异常共用同一套物理定义：稳健噪底取奈奎斯特邻域中位数，
    // 整体动态过小则无从分辨截止。两处各自实现是为了让两条判据的阈值独立可调
    private const val BANDWIDTH_FLOOR_LO_RATIO = 0.97f
    private const val BANDWIDTH_FLOOR_HI_RATIO = 0.995f
    private const val BANDWIDTH_MIN_DYNAMIC_DB = 40f

    // 是否为 AI 识别候选：本地文件路径音频（解码需真实路径）；与音质异常仅限 FLAC 不同，AI 识别不限格式
    fun isDecodableCandidate(track: MusicTrack): Boolean = track.path.isNotBlank()

    // 缓存键：路径 + 文件大小 + 时长齐备，文件内容变化即失效
    internal fun cacheKey(track: MusicTrack, sizeBytes: Long): String =
        "AI\u0000${track.path}\u0000$sizeBytes\u0000${track.duration}"

    // 判定入口：非本地路径或无法读取大小直接排除；缓存命中直接复用（含重启前持久化结果）
    suspend fun isSuspectedAiMusic(context: Context, track: MusicTrack): Boolean {
        if (!isDecodableCandidate(track)) return false
        cache.awaitLoaded(context)
        val sizeBytes = TrackAudioInfoReader.readFileSize(context, track) ?: return false
        val key = cacheKey(track, sizeBytes)
        cache.get(key)?.let { return it }
        // 生成器署名取证先于频谱分析，也先于全曲锁定：标签是生成链注入的确证，
        // 与是否做过完整频谱分析无关，命中即定论
        val evidence = AiSourceTagProbe.probe(context, track)
        if (evidence != null) {
            cache.map[key] = true
            cache.schedulePersist(context)
            return true
        }
        // 全曲分析锁定的曲目不再参与分段快速采样：结论只由 FullSpectrumAnalyzer 写入，
        // 缓存意外缺失时按未检出处理，不用分段结论顶替完整分析结论
        FullAnalysisLock.awaitLoaded(context)
        if (FullAnalysisLock.isLocked(track, sizeBytes)) return false
        val result = withContext(Dispatchers.IO) { analyze(track, sizeBytes) }
        // 无法判定的结果也缓存为 false：避免歌单过滤时对未判定文件重复做昂贵的频谱分析；
        // 识别策略升级后由「刷新」对未锁定曲目强制重算
        cache.map[key] = result ?: false
        cache.schedulePersist(context)
        return result ?: false
    }

    // 全曲分析的判定写入入口：把频谱页完整分析的结论落为可复用判定。
    // 署名证据优先于频谱结论：生成器自报的出身不受频谱取样范围影响，
    // 频谱页重跑不得用它覆盖标签已确认的判定。
    // 返回 null 表示不适用（无本地路径，或文件体积不可读而无从建立缓存键）
    suspend fun recordFullAnalysisVerdict(
        context: Context,
        track: MusicTrack,
        summary: SpectralDecoder.DecodeSummary,
    ): Boolean? {
        if (!isDecodableCandidate(track)) return null
        cache.awaitLoaded(context)
        val sizeBytes = TrackAudioInfoReader.readFileSize(context, track) ?: return null
        val verdict = if (AiSourceTagProbe.probe(context, track) != null) {
            true
        } else {
            verdictFromSummary(track, summary)
        }
        cache.map[cacheKey(track, sizeBytes)] = verdict
        cache.schedulePersist(context)
        return verdict
    }

    // 解码摘要判定：多征象从严合成，供合并批量分析（analyzeLibraryCombined）复用已解码摘要，
    // 避免对同一文件与音质异常识别各自解码。征象④须按容器类型适用，故需曲目本身
    internal fun verdictFromSummary(track: MusicTrack, summary: SpectralDecoder.DecodeSummary): Boolean =
        detectAiSignals(track, summary)

    // 单曲判定：返回 null 表示无法判定（时长/大小无效或解码不可用），调用方缓存为 false
    private suspend fun analyze(track: MusicTrack, sizeBytes: Long): Boolean? {
        if (track.duration <= 0 || sizeBytes <= 0) return null
        val summary = SpectralDecoder.decodeTrack(track, expectedMime = null) ?: return null
        return detectAiSignals(track, summary)
    }

    // 多征象合成：两条路径。
    //   a) ≥2 条统计征象同时命中——单条统计命中不足以定论；
    //   b) 补充证据路径：内容带宽未用满无损容器（征象④），且该带限已被音质异常判据
    //      归因为非原生链路（转码/重采样残迹）。带限本身在真实录音中也存在（老录音、
    //      窄母带），故单独不构成 AI 证据；只有「无损容器却装箱带限内容」这一反常组合
    //      配上转码归因，才指向生成链路的模型带宽上限。
    private fun detectAiSignals(track: MusicTrack, s: SpectralDecoder.DecodeSummary): Boolean {
        var hits = 0
        if (detectStereoSimilarity(s)) hits++
        if (detectHighShelfNotch(s)) hits++
        if (detectHarmonicComb(s)) hits++
        if (hits >= 2) return true
        return detectNonNativeBandwidth(track, s) &&
            FakeLosslessAnalyzer.verdictFromSummary(s)
    }

    // 征象①：中高频（约 250Hz 以上）左右声道长时间相关性。
    // 真人混音因摆位/混响去相关通常低于阈值；单声道源或统计样本不足视为无证据
    private fun detectStereoSimilarity(s: SpectralDecoder.DecodeSummary): Boolean =
        s.channels == 2 && s.stereoCorrSamples >= AI_STEREO_MIN_SAMPLES &&
            s.stereoCorrelation >= AI_STEREO_CORRELATION_MIN

    // 平均功率谱转相对峰值的分贝谱，供 ②③ 征象共享
    private fun toDb(powerSum: FloatArray, blocks: Int): FloatArray {
        val n = powerSum.size - 1
        val db = FloatArray(n + 1)
        var peak = Float.NEGATIVE_INFINITY
        for (i in 0..n) {
            db[i] = 10f * log10((powerSum[i] / blocks + 1e-12f).toDouble()).toFloat()
            if (db[i] > peak) peak = db[i]
        }
        for (i in 0..n) db[i] -= peak
        return db
    }

    // 征象②：12-18kHz 范围内存在「深于两侧、且上方回升」的窄凹点——
    // 中间采样率升频的成像缺口特征，与持续高频滚降（无回升）可区分。
    // 真人母带罕有窄深缺口 + 回升的组合形态
    private fun detectHighShelfNotch(s: SpectralDecoder.DecodeSummary): Boolean {
        // 需奈奎斯特足够高，为「缺口 + 上方恢复带」留出观察空间
        if (s.sampleRate < 40000) return false
        val db = toDb(s.powerSum, s.blocks)
        val n = db.size - 1
        val binHz = s.sampleRate.toFloat() / SpectralDecoder.FFT_SIZE
        val loBin = (12000f / binHz).toInt().coerceAtLeast(1)
        val hiBin = (18000f / binHz).toInt().coerceAtMost(n)
        if (hiBin - loBin < 64) return false
        val half = (800f / binHz).toInt().coerceAtLeast(24)
        val side = (250f / binHz).toInt().coerceAtLeast(8)
        val recoverTo = (1500f / binHz).toInt().coerceAtLeast(48)
        var bestDip = 0f
        var m = loBin + half
        while (m <= hiBin - half) {
            // 须为局部最小：±half 范围内的最凹点
            var minInRange = true
            for (i in (m - half).coerceAtLeast(0)..(m + half).coerceAtMost(n)) {
                if (db[i] < db[m]) {
                    minInRange = false
                    break
                }
            }
            if (minInRange) {
                var leftSum = 0f
                var leftCnt = 0
                var i = (m - 2 * side).coerceAtLeast(0)
                while (i < m - side) { leftSum += db[i]; leftCnt++; i++ }
                var rightSum = 0f
                var rightCnt = 0
                i = m + side
                while (i <= (m + recoverTo).coerceAtMost(n)) { rightSum += db[i]; rightCnt++; i++ }
                if (leftCnt > 0 && rightCnt > 0) {
                    val leftAvg = leftSum / leftCnt
                    val rightAvg = rightSum / rightCnt
                    val dip = maxOf(leftAvg, rightAvg) - db[m]
                    // 缺口需够深，且上方存在回升（右带均值高于缺口底）
                    if (dip > bestDip && rightAvg >= db[m] + AI_NOTCH_RECOVERY_DB) {
                        bestDip = dip
                    }
                }
            }
            m++
        }
        return bestDip >= AI_NOTCH_MIN_DIP_DB
    }

    // 征象③：1-8kHz 平均谱剥离下包络后呈等间距规则峰列（谐波梳）。
    // 反卷积零插值镜像的峰距恒定；真实音乐的平均谱峰距随音符/和声变化，一致性明显更差
    private fun detectHarmonicComb(s: SpectralDecoder.DecodeSummary): Boolean {
        val binHz = s.sampleRate.toFloat() / SpectralDecoder.FFT_SIZE
        val db = toDb(s.powerSum, s.blocks)
        val n = db.size - 1
        val loBin = (1000f / binHz).toInt().coerceAtLeast(1)
        val hiBin = (8000f / binHz).toInt().coerceAtMost(n)
        if (hiBin - loBin < 256) return false
        // 滚动最小下包络：以 ±500Hz 半径形态学侵蚀，剥离音乐性宽带纹理
        val radius = (500f / binHz).toInt().coerceAtLeast(24)
        val envelope = FloatArray(n + 1)
        for (i in 0..n) {
            var mn = Float.MAX_VALUE
            var k = (i - radius).coerceAtLeast(0)
            val to = (i + radius).coerceAtMost(n)
            while (k <= to) {
                if (db[k] < mn) mn = db[k]
                k++
            }
            envelope[i] = mn
        }
        // 局部极大 + 残差突出即候选峰；相邻峰至少相隔 6 桶，防止同一峰双记
        val peaks = ArrayList<Int>()
        var prev = Int.MIN_VALUE
        var i = loBin
        while (i < hiBin) {
            if (db[i] >= db[i - 1] && db[i] >= db[i + 1] &&
                db[i] - envelope[i] >= AI_COMB_RESIDUE_DB && i - prev >= 6
            ) {
                peaks.add(i)
                prev = i
            }
            i++
        }
        if (peaks.size < AI_COMB_MIN_PEAKS) return false
        // 峰距一致性：方差系数小于阈值视为等距谐波梳
        var prevBin = peaks[0]
        var sum = 0.0
        var sumSq = 0.0
        var cnt = 0
        for (k in 1 until peaks.size) {
            val d = (peaks[k] - prevBin).toDouble()
            sum += d
            sumSq += d * d
            cnt++
            prevBin = peaks[k]
        }
        val mean = sum / cnt
        if (mean <= 0.0) return false
        val variance = sumSq / cnt - mean * mean
        return sqrt(variance) / mean <= AI_COMB_MAX_CV
    }

    // 征象④：无损容器内的非原生带宽——容器声明无损（FLAC），但内容带宽明显未用满容器
    // 采样率。说明内容并非在本采样率下原生录制，而是由带限更低的链路产出：生成模型
    // 输出端的带宽上限，或重采样/有损转码的残留。
    // 有损容器（MP3/AAC/OGG）天然带限，本征象对其不适用，一律返回 false。
    // 本征象只回答「内容有没有用满容器带宽」，硬截止与转码归属由音质异常判据负责，
    // 故须两者并存才定论，见 detectAiSignals
    private fun detectNonNativeBandwidth(track: MusicTrack, s: SpectralDecoder.DecodeSummary): Boolean {
        if (!FakeLosslessAnalyzer.isFlacCandidate(track)) return false
        // 低规格豁免：规格不足的容器带宽天然受限，非原生带宽不构成证据。
        // 与音质异常的低规格豁免同口径；摘要未携带位深，此处校验可得的采样率与声道
        if (s.sampleRate < 44100 || s.channels < 2) return false
        val binHz = s.sampleRate.toFloat() / SpectralDecoder.FFT_SIZE
        val nyquist = s.sampleRate / 2f
        val n = s.powerSum.size - 1
        val db = toDb(s.powerSum, s.blocks)
        // 稳健噪声底：奈奎斯特邻域中位数，口径与音质异常判据一致
        val loBin = (BANDWIDTH_FLOOR_LO_RATIO * nyquist / binHz).toInt().coerceIn(0, n)
        val hiBin = (BANDWIDTH_FLOOR_HI_RATIO * nyquist / binHz).toInt().coerceIn(loBin, n)
        val seg = db.copyOfRange(loBin, hiBin + 1)
        seg.sort()
        val floor = seg[seg.size / 2]
        // 整体动态过小则无从分辨截止，视为无证据
        if (-floor < BANDWIDTH_MIN_DYNAMIC_DB) return false
        // 自奈奎斯特向下逐带宽窗求均值，取首个高于噪底门限的窗上缘为内容截止：
        // 只认成片的能量，不受死区逐 bin 抖动干扰
        val bandBins = (BANDWIDTH_BAND_HZ / binHz).toInt().coerceAtLeast(1)
        var cutHz = 0f
        var end = n
        while (end > 0) {
            val from = (end - bandBins).coerceAtLeast(0)
            var sum = 0f
            for (i in from..end) sum += db[i]
            if (sum / (end - from + 1) - floor > BANDWIDTH_BAND_ABOVE_FLOOR_DB) {
                cutHz = end * binHz
                break
            }
            end -= bandBins
        }
        if (cutHz <= 0f) return false
        return cutHz <= AI_BANDWIDTH_MAX_RATIO * nyquist
    }
}