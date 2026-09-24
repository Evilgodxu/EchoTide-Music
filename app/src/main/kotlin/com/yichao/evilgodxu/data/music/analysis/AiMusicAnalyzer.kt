package com.yichao.evilgodxu.data.music.analysis

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// AI 音乐识别器：规则启发式多征象从严判定，针对神经声码器/合成链路的统计痕迹，
// 与音质异常的频谱截止判据正交，覆盖全格式本地文件。
// 单曲入口与批量分析走共享 SpectralDecoder 的分段采样（3 段每段 4 秒）；用户查看过频谱的曲目
// 由 FullSpectrumAnalyzer 在全曲解码上复用同一征象集重跑并锁定，此后分段采样不再改写其结论。
// 征象集（均提取自共享 SpectralDecoder 的同一解码摘要）：
//  ① 立体声相关性：真人混音因摆位/混响左右声道去相关，AI 由单声道骨干扩立体声相关性偏高；
//  ② 12-18kHz 尖锐缺口 + 上方回升：32k 中间格式升频至 44.1k 的成像缺口，区别于持续滚降；
//  ③ 1-8kHz 谐波梳：反卷积零插值在平均谱上留下等间距规则峰列。
// 从严策略：三条征象至少两条同时命中才判定，规避对真实强谐波/单声道内容的误报。
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
    // 返回 null 表示不适用（无本地路径，或文件体积不可读而无从建立缓存键）
    suspend fun recordFullAnalysisVerdict(
        context: Context,
        track: MusicTrack,
        summary: SpectralDecoder.DecodeSummary,
    ): Boolean? {
        if (!isDecodableCandidate(track)) return null
        cache.awaitLoaded(context)
        val sizeBytes = TrackAudioInfoReader.readFileSize(context, track) ?: return null
        val verdict = verdictFromSummary(summary)
        cache.map[cacheKey(track, sizeBytes)] = verdict
        cache.schedulePersist(context)
        return verdict
    }

    // 解码摘要判定：多征象从严合成，供合并批量分析（analyzeLibraryCombined）复用已解码摘要，
    // 避免对同一文件与音质异常识别各自解码
    internal fun verdictFromSummary(summary: SpectralDecoder.DecodeSummary): Boolean =
        detectAiSignals(summary)

    // 单曲判定：返回 null 表示无法判定（时长/大小无效或解码不可用），调用方缓存为 false
    private suspend fun analyze(track: MusicTrack, sizeBytes: Long): Boolean? {
        if (track.duration <= 0 || sizeBytes <= 0) return null
        val summary = SpectralDecoder.decodeTrack(track, expectedMime = null) ?: return null
        return detectAiSignals(summary)
    }

    // 多征象从严合成：≥2 条征象同时命中才判定为 AI，单条命中不构成足够证据
    private fun detectAiSignals(s: SpectralDecoder.DecodeSummary): Boolean {
        var hits = 0
        if (detectStereoSimilarity(s)) hits++
        if (detectHighShelfNotch(s)) hits++
        if (detectHarmonicComb(s)) hits++
        return hits >= 2
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
}