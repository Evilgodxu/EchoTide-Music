package com.yichao.evilgodxu.data.music.analysis

import android.content.Context
import com.yichao.evilgodxu.data.music.model.MusicTrack
import kotlin.math.log10
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 音质异常识别器：两级判定。单曲入口（歌单过滤）逐曲执行；整库校验走合并批量分析，
// 由调用方在限并发调度器上推进（并发上限见 LibraryAnalysisRunner）。
// ① 轻量预筛：扩展名 + FLAC 容器头，仅排除非 FLAC 与超低规格（<44.1kHz/<16bit/<2ch）文件；
//    不设码率压缩比/头部规格免检路径——伪造文件可借量化噪声/上采样令码率虚高，
//    头部参数亦不可信，任何候选文件都不得绕过频谱判定；
// ② 频谱判定：全部候选 FLAC 用共享 SpectralDecoder 稀疏窗口解码 3 段（每窗 4 秒），FFT 求平均
//    功率谱，三条物理证据路径分离判定：
//    a) 升频判据：内容真实截止落在某源采样率奈奎斯特保护带内 + 过渡带具砖墙陡峭度 +
//       44.1k 源奈奎斯特上方逐帧能量恒定（死区），三者齐备判升频音质异常；
//       ——不预设墙在固定频率，48k 原生母带自然滚降（截止超出保护带或过渡带平缓）不受误伤；
//    b) 砖墙判据：CD 级硬截止 + 平坦死区表征有损转码，老录音/窄母带等自然限带
//       经去相关性与转码特征（编码器截止网格 / 高频掩蔽空洞）两级佐证区分，佐证不足放行；
//    c) 高解析：非升频的硬墙视为自然滚降，直接放行，避免把母带高频滚降误判为转码。
// 结果持久化缓存与批量增量校验复用 TrackVerdictCache；
// 进度由调用方驱动，协程取消即时释放解码器。
internal object FakeLosslessAnalyzer {

    // 音质异常智能歌单过滤键：与本地化展示名解耦，保证序列化歌单 key 跨语言环境稳定
    const val FAKE_LOSSLESS_KEY = "fake-lossless"

    // 识别结果缓存：键含文件大小与时长，文件变化即失效；供合并批量分析共享复用
    internal val cache = TrackVerdictCache(TrackVerdictCache.FILE_NAME_FAKE_LOSSLESS)

    // 立体声相关性佐证：统计所需最少样本数（约 0.1 秒），不足视为无证据；
    // 砖墙命中且相关性不高于该值时，视为自然限带内容（真去相关）而非转码，降级放行
    private const val STEREO_MIN_SAMPLES = 4096L
    private const val STEREO_CORR_AGAINST_MAX = 0.55f

    // 转码第二佐证（CD 级硬墙且相关性无从反驳时用于区分转码与自然限带）：
    // ① 编码器特征频率网格：LAME/AAC 的硬截止位置由低通预设决定，仅落在少数确定频率
    //    （44.1k/48k 下均为 16k/17.5k/18.5k/20k），自然限带（抗混叠滤波/母带低通）
    //    是连续可调的，硬切恰落网格的概率低；调用方仅在立体声未去相关时评估，
    //    自然限带源多为去相关内容，已在前置分支放行
    private val CODEC_GRID_HZ = floatArrayOf(16000f, 17500f, 18500f, 20000f)
    private const val CODEC_GRID_TOLERANCE_HZ = 400f
    // ② 高频掩蔽空洞：MP3/AAC 心理声学模型在 16k 以上留下窄深缺口（相邻 bin 突然凹陷又回升），
    //    真无损自然限带的高频是平滑单调滚降，罕有孤立窄凹；对平均谱做局部极小 + 两侧带落差统计
    private const val HOLE_LO_BAND_HZ = 16000f
    private const val HOLE_SIDE_BAND_HZ = 180f
    private const val HOLE_DEPTH_DB = 10f
    private const val HOLE_MAX_HALF_WIDTH_HZ = 300f
    private const val HOLE_MIN_COUNT = 3
    // ③ 高频死区全局平坦：转码链（有损解码→重采样→FLAC）的硬截止上方为量化/重采样均匀噪底
    //    或数字静默，自截止至奈奎斯特逐 bin 能量几乎恒定（p95–p05 极窄）；
    //    自然限带源的高频残留带模拟噪声/带内滚降结构，跨整个死区的平坦度远逊于该量级。
    //    平均谱经多块累计后噪声起伏被压低，阈值取固定分贝跨度的稳健分位数差
    private const val DEADZONE_FLAT_MAX_SPREAD_DB = 8f
    private const val DEADZONE_MIN_BINS = 16

    // 稳健噪声底：取靠近奈奎斯特区间的中位数（而非全局最小值），避免真文件 dither 噪底
    // 与升频数字零死区之间 68dB 级别的基准漂移；宽区间内频带自身动态小，中位数即噪底中心
    private const val FLOOR_LO_RATIO = 0.97f
    private const val FLOOR_HI_RATIO = 0.995f

    // 内容截止与过渡带宽度：升频判据的物理量，与电平、采样率、FFT 尺寸无关。
    // 截止 = 高于底噪 3dB 的最高频率；过渡带宽度 = 相对底噪从 20dB 降到 3dB 的频宽，
    // 重采样砖墙极陡（实测 70–117Hz），自然滚降平缓（实测 598Hz+）
    private const val CUT_ABOVE_FLOOR_DB = 3f
    private const val WALL_REF_DB = 20f

    // 升频判据：候选源采样率的奈奎斯特。重采样保护带使真实墙落在源奈奎斯特附近；
    // 过渡带须具砖墙陡峭度；源奈奎斯特上方探带的逐帧能量须恒定（死区）
    private val UPSAMPLE_SRC_NYQUIST_HZ = floatArrayOf(22050f, 24000f, 44100f)
    private const val GUARD_LOW_HZ = 900f
    // 保护带上限放宽至 1kHz：重采样过渡带/砖墙振铃可使实测内容截止略高于源奈奎斯特
    // （实测 48k→96k 截止 24.26k，仅超 24000 保护带上缘 58Hz），余量过小导致升频漏判；
    // 墙宽与死区动态判据仍独立把关，自然滚降不受影响
    private const val GUARD_HIGH_HZ = 1000f
    private const val MAX_WALL_WIDTH_HZ = 400f
    // 升频死区逐帧动态上界：真实内容随乐句起伏（实测 >45dB），重采样死区恒定（<2dB）
    private const val MIN_DEADZONE_DYN_DB = 10f
    // 死区动态统计所需最少帧数，不足视为无证据
    private const val MIN_DEADZONE_FRAMES = 16

    // 是否为可校验的 FLAC 文件：扩展名口径，与曲库分析的 FLAC 格式类目一致
    fun isFlacCandidate(track: MusicTrack): Boolean =
        track.path.substringAfterLast('.', "").uppercase() == "FLAC"

    // 缓存键：路径 + 文件大小 + 时长齐备，文件内容变化即失效
    internal fun cacheKey(track: MusicTrack, sizeBytes: Long): String =
        "FLAC\u0000${track.path}\u0000$sizeBytes\u0000${track.duration}"

    // 判定入口：非 FLAC 或无法读取大小直接排除；缓存命中直接复用（含重启前持久化结果）
    suspend fun isSuspectedFakeLossless(context: Context, track: MusicTrack): Boolean {
        if (!isFlacCandidate(track)) return false
        cache.awaitLoaded(context)
        val sizeBytes = TrackAudioInfoReader.readFileSize(context, track) ?: return false
        val key = cacheKey(track, sizeBytes)
        cache.get(key)?.let { return it }
        val result = withContext(Dispatchers.IO) { analyze(context, track, sizeBytes) }
        // 无法判定的结果也缓存为 false：避免歌单过滤时对未判定文件重复做昂贵的频谱分析，
        // 导致音质异常歌单切换看似无响应；识别策略升级后由「刷新」清空缓存强制重新校验
        cache.map[key] = result ?: false
        cache.schedulePersist(context)
        return result ?: false
    }

    // 清除全部校验缓存（内存 + 落盘）：识别策略升级或用户主动刷新时用于强制全量重新分析，
    // 避免旧版本判定结果（如放宽标准时的「真无损」）被持久化缓存复用而漏掉音质异常
    suspend fun resetCache(context: Context) = cache.reset(context)

    // 低规格豁免判断（纯函数）：容器头可读且规格不足（<44.1kHz/<16bit/<2ch）时，
    // 带宽受限天然带高频截止，非音质异常伪装目标，无需解码即可排除；
    // 容器头不可读时不豁免，交由频谱分析以解码格式参数兜底
    internal fun isLowSpecFakeLossless(format: TrackAudioInfoReader.ContainerFormat?): Boolean =
        format != null && (format.sampleRate < 44100 || format.bitDepth < 16 || format.channels < 2)

    // 低规格豁免入口：读容器头后按纯函数判定，供合并批量分析在解码前排除
    internal fun isLowSpecFakeLossless(context: Context, track: MusicTrack): Boolean =
        isLowSpecFakeLossless(TrackAudioInfoReader.readFlacContainerFormat(context, track))

    // 解码摘要判定：音质异常判定核心（砖墙 / 升频锚点 + CD 级相关性反驳），
    // 供合并批量分析（analyzeLibraryCombined）复用已解码摘要，避免与单曲入口重复解码
    internal fun verdictFromSummary(summary: SpectralDecoder.DecodeSummary): Boolean =
        detectFakeSignals(summary)

    // 单曲判定：返回 null 表示无法判定（时长/大小无效或解码整体不可用），调用方缓存为 false。
    // 容器头不可读（如带 ID3v2 前置标签的 FLAC）不直接放弃：SpectralDecoder 仍可定位音频流，
    // 交由频谱分析以提取器格式参数兜底；仅低规格参数明确时才直接排除
    private suspend fun analyze(context: Context, track: MusicTrack, sizeBytes: Long): Boolean? {
        if (track.duration <= 0 || sizeBytes <= 0) return null
        val format = TrackAudioInfoReader.readFlacContainerFormat(context, track)
        if (isLowSpecFakeLossless(format)) return false
        // 频谱分析为强制判定环节：头部规格与码率压缩比不提供免检放行（伪造文件可借量化噪声/
        // 上采样令码率虚高，码率判据会失真）；容器头值作为提取器缺参兜底；null 表示解码不可用
        val summary = SpectralDecoder.decodeTrack(
            track,
            expectedMime = "audio/flac",
            fallbackSampleRate = format?.sampleRate ?: 0,
            fallbackChannels = format?.channels ?: 0,
        ) ?: return null
        return detectFakeSignals(summary)
    }

    // 音质异常合成判定。三条路径按物理证据分离：
    // ① 升频：内容截止落在某个源采样率奈奎斯特保护带内 + 过渡带具砖墙陡峭度 +
    //    44.1k 源奈奎斯特上方逐帧能量恒定（死区），三条件齐备判升频音质异常。
    //    完全基于物理量，不预设墙在固定频率——48k 原生母带内容自然滚降
    //    （截止超出保护带或过渡带平缓）不会被误伤，44.1k→48k 升频可被正确捕获。
    //    死区动态共用 22050 上方探带：44.1k 源升频的死区恒定；对 48k/96k 源升频，
    //    探带若落在真母带内容区则动态大而放行（内容无损、仅采样率虚标的升采样不混入音质异常）。
    // ② 砖墙转码（CD 级 44.1k/48k）：硬截止 + 平坦死区表征有损转码，
    //    但老录音/窄母带等自然限带也可能撞出硬墙，需依次排除：
    //    a) 左右声道明确去相关（≤0.55）→ 自然限带，放行；
    //    b) 无去相关证据时，须有转码专属佐证才判真——截止落在编码器特征频率网格
    //       （16k/17.5k/18.5k/20k，MP3/AAC 预设位置）、高频存在 ≥3 个掩蔽空洞，
    //       或截止后整个高频死区全局平坦（量化/重采样均匀噪底或数字静默）；
    //    c) 三者皆无 → 更可能是自然限带（抗混叠/母带低通硬切），放行（宁漏勿误）。
    // ③ 高解析（>48k）：内容截止落入对应源奈奎斯特保护带且死区恒定的升频在 ① 命中；
    //    其余硬墙与 CD 级同等过转码佐证（特征网格仅限 CD 级，高解析以平坦死区为主），
    //    原生高解析的自然滚降因不满足 detectCliff 的平坦前提而放行，母带滚降不误判。
    private fun detectFakeSignals(s: SpectralDecoder.DecodeSummary): Boolean {
        val stats = computeSpectralStats(s.powerSum, s.blocks, s.sampleRate) ?: return false
        // ① 升频：物理证据独立判定，命中即音质异常，不依赖砖墙命中与否
        if (detectUpsample(stats, s.probe22050)) return true
        // ② ③ 砖墙：先抓「未升频转码」，再按规格做防误判分流
        val cutoffHz = detectCliff(stats)
        if (cutoffHz != null) {
            // 去相关内容撞出硬墙更可能来自自然限带（老录音/窄母带），放行；
            // 高解析不再无条件放行：detectCliff 要求截止上方 3kHz 内相对噪底平坦（≤8dB），
            // 原生高解析的自然滚降不满足该前提，撞出硬墙的多为升频/转码的重采样墙
            val hasStereoEvidence = s.channels == 2 && s.stereoCorrSamples >= STEREO_MIN_SAMPLES
            if (hasStereoEvidence && s.stereoCorrelation <= STEREO_CORR_AGAINST_MAX) return false
            // 无去相关证据：须有转码专属佐证才判真（特征网格截止 / 高频掩蔽空洞 /
            // 截止后整体高频死区全局平坦），三者构成或逻辑；网格仅对 CD 级 44.1k/48k
            // 启用，高解析的转码墙由「平坦死区」佐证捕获
            return isCodecGridCutoff(stats, cutoffHz) ||
                detectTranscodeHoles(stats) ||
                detectFlatDeadZone(stats, cutoffHz)
        }
        return false
    }

    // 频谱统计中间量：砖墙/升频判据共享的相对峰值分贝谱、区间均值前缀和、稳健噪声底、
    // 频率刻度，以及内容截止与过渡带宽度（截至相对底噪的物理量）
    private class SpectralStats(
        val db: FloatArray,
        val prefix: FloatArray,
        val floor: Float,
        val binHz: Float,
        val nyquist: Float,
        val n: Int,
        val sampleRate: Int,
        val contentCutHz: Float,
        val wallWidthHz: Float,
    )

    // 平均功率谱转相对峰值分贝谱并提取共享统计；整体动态过小返回 null（无判定能力）
    private fun computeSpectralStats(
        powerSum: FloatArray,
        blocks: Int,
        sampleRate: Int,
    ): SpectralStats? {
        val n = SpectralDecoder.FFT_SIZE / 2
        val db = FloatArray(n + 1)
        for (i in 0..n) {
            db[i] = 10f * log10((powerSum[i] / blocks + 1e-12f).toDouble()).toFloat()
        }
        var peakDb = db[0]
        for (i in 1..n) if (db[i] > peakDb) peakDb = db[i]
        for (i in 0..n) db[i] -= peakDb
        // 前缀和：窗口均值 O(1) 查询
        val prefix = FloatArray(n + 2)
        for (i in 0..n) prefix[i + 1] = prefix[i] + db[i]
        val binHz = sampleRate.toFloat() / SpectralDecoder.FFT_SIZE
        val nyquist = sampleRate / 2f
        // 稳健噪声底：靠近奈奎斯特区间（0.97–0.995·nyq）的中位数。
        // 该区间不含音乐主体但含 dither/量化噪底，中位数对偶发尖刺不敏感，跨文件基准稳定
        val loBin = (FLOOR_LO_RATIO * nyquist / binHz).toInt().coerceIn(0, n)
        val hiBin = (FLOOR_HI_RATIO * nyquist / binHz).toInt().coerceIn(loBin, n)
        val seg = db.copyOfRange(loBin, hiBin + 1)
        seg.sort()
        val floor = seg[seg.size / 2]
        // 整体动态过小则无法判定
        if (-floor < 40f) return null
        // 内容截止：高于底噪 3dB 的最高频率
        var cutBin = -1
        for (i in n downTo 0) {
            if (db[i] - floor > CUT_ABOVE_FLOOR_DB) {
                cutBin = i
                break
            }
        }
        val contentCutHz = if (cutBin > 0) cutBin * binHz else 0f
        // 过渡带宽度：相对底噪从 20dB 降到 3dB 的频宽；无 20dB 段视为极宽（自然滚降）
        var w20 = -1
        if (cutBin > 0) {
            for (i in cutBin downTo 0) {
                if (db[i] - floor > WALL_REF_DB) {
                    w20 = i
                    break
                }
            }
        }
        val wallWidthHz = if (w20 >= 0 && cutBin > w20) (cutBin - w20) * binHz else Float.MAX_VALUE
        return SpectralStats(db, prefix, floor, binHz, nyquist, n, sampleRate, contentCutHz, wallWidthHz)
    }

    // 砖墙截止判定：找显著下降沿 + 上方死区平坦且明显低于奈奎斯特，
    // 表征有损转码的硬截止；命中返回截止频率（Hz），未命中返回 null，
    // 由合成判定按规格分流：高解析直接判真，CD 级转入转码第二佐证
    private fun detectCliff(st: SpectralStats): Float? {
        val n = st.n
        fun mean(from: Int, to: Int): Float =
            ((st.prefix[to + 1] - st.prefix[from]) / (to - from + 1).toFloat())

        val leftBins = (1500f / st.binHz).toInt().coerceAtLeast(8)
        val rightBins = (3000f / st.binHz).toInt().coerceAtLeast(16)
        val minCutoffBin = (3000f / st.binHz).toInt() + 1
        var b = n - 1
        while (b > minCutoffBin) {
            // 右侧死区：截止点到右端应为平坦噪声底
            val rightTo = (b + rightBins).coerceAtMost(n)
            val rightAvg = mean(b, rightTo)
            val leftFrom = (b - leftBins).coerceAtLeast(1)
            val leftAvg = mean(leftFrom, b - 1)
            // 硬切判据：下降沿 ≥35dB 且上方死区平坦（≤8dB 相对噪声底）
            if (leftAvg - st.floor >= 35f && rightAvg - st.floor <= 8f) {
                val cutoffHz = b * st.binHz
                if (cutoffHz <= 0.9f * st.nyquist) return cutoffHz
            }
            b--
        }
        return null
    }

    // 编码器特征网格佐证：截止频率落在 LAME/AAC 的预设截止点（多边形容差）附近。
    // 44.1k 与 48k 共用同一 Hz 网格——低通截止由编码器按 Hz 设定，与源采样率解耦；
    // 48k 下 scale factor band 分布虽略有偏移，但本佐证仅在立体声未去相关时评估，
    // 自然限带源（多为去相关内容）已在前置分支放行，误伤风险可控
    private fun isCodecGridCutoff(st: SpectralStats, cutoffHz: Float): Boolean {
        if (st.sampleRate != 44100 && st.sampleRate != 48000) return false
        return CODEC_GRID_HZ.any { kotlin.math.abs(it - cutoffHz) <= CODEC_GRID_TOLERANCE_HZ }
    }

    // 高频掩蔽空洞佐证：16k 至奈奎斯特之间统计「窄而深」的孤立缺口——
    // MP3/AAC 心理声学掩蔽在平均谱上残留的稳定窄槽；真无损自然限带的高频
    // 为平滑单调滚降，罕有此类形态。要求局部极小、两侧带落差 ≥10dB、宽度
    // ≤600Hz，且计数达到阈值才判转码；死区平坦段处处等电平，不会产生空洞
    private fun detectTranscodeHoles(st: SpectralStats): Boolean {
        val binHz = st.binHz
        val db = st.db
        val n = st.n
        val loBin = (HOLE_LO_BAND_HZ / binHz).toInt().coerceAtLeast(1)
        val sideBin = (HOLE_SIDE_BAND_HZ / binHz).toInt().coerceAtLeast(6)
        val maxHalfWidthBin = (HOLE_MAX_HALF_WIDTH_HZ / binHz).toInt().coerceAtLeast(sideBin + 4)
        var count = 0
        var i = loBin + sideBin
        var lastHoleBin = Int.MIN_VALUE
        while (i < n - sideBin) {
            // 局部极小：±sideBin 内最凹点
            var isLocalMin = true
            val iLo = (i - sideBin).coerceAtLeast(0)
            val iHi = (i + sideBin).coerceAtMost(n)
            for (k in iLo..iHi) {
                if (db[k] < db[i]) {
                    isLocalMin = false
                    break
                }
            }
            if (isLocalMin) {
                // 两侧带均值（跳过凹点自身 ±sideBin）：取较低一侧作落差基准
                var leftSum = 0f
                var leftCnt = 0
                var k = (i - 2 * sideBin).coerceAtLeast(0)
                val leftEnd = (i - sideBin).coerceAtLeast(0)
                while (k < leftEnd) { leftSum += db[k]; leftCnt++; k++ }
                var rightSum = 0f
                var rightCnt = 0
                k = (i + sideBin).coerceAtMost(n)
                val rightEnd = (i + 2 * sideBin).coerceAtMost(n)
                while (k <= rightEnd) { rightSum += db[k]; rightCnt++; k++ }
                if (leftCnt > 0 && rightCnt > 0) {
                    val sideAvg = minOf(leftSum / leftCnt, rightSum / rightCnt)
                    if (sideAvg - db[i] >= HOLE_DEPTH_DB) {
                        // 宽度：自凹点向两侧走，直到回升 3dB 或超出最大半宽
                        var halfLeft = 0
                        while (halfLeft < maxHalfWidthBin && i - 1 - halfLeft >= 0 &&
                            db[i - 1 - halfLeft] < db[i] + 3f
                        ) {
                            halfLeft++
                        }
                        var halfRight = 0
                        while (halfRight < maxHalfWidthBin && i + 1 + halfRight <= n &&
                            db[i + 1 + halfRight] < db[i] + 3f
                        ) {
                            halfRight++
                        }
                        val width = halfLeft + halfRight + 1
                        // 窄凹（≤ 最大半宽两倍）+ 与前一空洞保持间距去重
                        if (width <= 2 * maxHalfWidthBin &&
                            i - lastHoleBin >= sideBin
                        ) {
                            count++
                            lastHoleBin = i
                        }
                    }
                }
            }
            i++
        }
        return count >= HOLE_MIN_COUNT
    }

    // 高频死区平坦佐证：硬截止至奈奎斯特之间的逐 bin 能量几乎恒定（稳健分位数差极小）。
    // 有损解码→重采样→FLAC 链路的死区是量化/重采样均匀噪底或数字静默，统计上齐平；
    // 自然限带源（无论是否去相关）的高频残留带模拟噪声/带内滚降结构，跨整个死区
    // 起伏明显。平均谱经多块累计，噪声的逐 bin 抖动被压低到 2–4dB，阈值取 8dB
    // 留足裕度；死区过窄（bin 不足）判定能力不足，放行。
    // 仅在砖墙命中且未触发去相关放行后评估——此时死区平坦与转码在可测物理量上不可区分
    private fun detectFlatDeadZone(st: SpectralStats, cutoffHz: Float): Boolean {
        val fromBin = (cutoffHz / st.binHz).toInt().coerceIn(0, st.n)
        if (st.n - fromBin < DEADZONE_MIN_BINS) return false
        val seg = st.db.copyOfRange(fromBin, st.n + 1)
        seg.sort()
        val p95 = seg[(seg.size * 0.95).toInt().coerceIn(0, seg.size - 1)]
        val p05 = seg[(seg.size * 0.05).toInt().coerceIn(0, seg.size - 1)]
        return p95 - p05 <= DEADZONE_FLAT_MAX_SPREAD_DB
    }

    // 升频音质异常判据：先测内容真实截止频率，再判断它是否落在某个源采样率奈奎斯特的
    // 保护带内（重采样器必有保护带，真实墙总在源奈奎斯特略下方），且过渡带具砖墙陡峭度，
    // 且 44.1k 源奈奎斯特上方探带的逐帧能量恒定（死区）。
    // 三者齐备才判升频——自然滚降的 48k 原生母带（截止超出保护带或过渡带平缓）不命中；
    // 48k/96k 源升频若探带落在真母带内容区（动态大）放行，不把「内容无损、仅采样率虚标」
    // 的升采样误判为音质异常
    private fun detectUpsample(st: SpectralStats, probe22050: FloatArray): Boolean {
        val cutHz = st.contentCutHz
        if (cutHz <= 0f) return false
        for (srcNyquist in UPSAMPLE_SRC_NYQUIST_HZ) {
            // 文件采样率必须显著高于源采样率才可能是升频
            if (st.sampleRate <= srcNyquist * 2f) continue
            // 源奈奎斯特需深入文件奈奎斯特以下，留足死区宽度供判定
            if (srcNyquist + 800f > st.nyquist) continue
            val inGuard = cutHz in (srcNyquist - GUARD_LOW_HZ)..(srcNyquist + GUARD_HIGH_HZ)
            if (!inGuard) continue
            // 过渡带须具重采样砖墙陡峭度；自然滚降（数百 Hz 以上）直接排除
            if (st.wallWidthHz > MAX_WALL_WIDTH_HZ) continue
            // 死区动态：22050 上方探带逐帧能量起伏——44.1k 源升频死区为常量（<10dB），
            // 真母带内容随乐句起伏；探带不适用（采样率不足以容纳）或帧数不足时视为无证据，
            // 放行（宁漏勿误）
            if (probe22050.isEmpty() || deadzoneDynDb(probe22050) > MIN_DEADZONE_DYN_DB) return false
            return true
        }
        return false
    }

    // 探带逐帧能量的动态范围（p95–p05，dB）：真实内容随音乐包络起伏差异大，
    // 重采样死区为常量噪声；帧数不足返回 MAX 视为无证据
    private fun deadzoneDynDb(powers: FloatArray): Float {
        if (powers.size < MIN_DEADZONE_FRAMES) return Float.MAX_VALUE
        val n = powers.size
        val s = FloatArray(n)
        for (i in 0 until n) {
            s[i] = 10f * log10(powers[i] + 1e-12f)
        }
        s.sort()
        val p95 = s[(n * 0.95).toInt().coerceIn(0, n - 1)]
        val p05 = s[(n * 0.05).toInt().coerceIn(0, n - 1)]
        return (p95 - p05).coerceAtLeast(0f)
    }
}