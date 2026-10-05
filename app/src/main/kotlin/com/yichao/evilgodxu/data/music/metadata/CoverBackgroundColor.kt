package com.yichao.evilgodxu.data.music.metadata

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// 边缘取样带宽度占封面短边的比例：只取封面四周的一圈，避开画面中心的主体与文字。
// 背景正是与封面外缘相接的一片，取外缘色调才能与封面自然衔接
private const val EDGE_BAND_RATIO = 0.14f

// 边缘环的纵向等分数：自上而下三段各取一个主色，分别作背景的三个色标
private const val EDGE_SEGMENT_COUNT = 3

// 每段中位切分的簇数上限：段内色域被切成这么多簇，再从各簇里择优
private const val SEGMENT_CLUSTER_COUNT = 8

// 每通道的量化档位：取高 5 位量化到 32 档，与 Palette 的量化精度同档。
// 低 3 位是压缩与传感器噪声，切掉后同一片颜色才落进同一个桶
private const val QUANTIZE_SHIFT = 3
private const val QUANTIZED_BITS_PER_CHANNEL = 8 - QUANTIZE_SHIFT
private const val QUANTIZED_LEVELS = 1 shl QUANTIZED_BITS_PER_CHANNEL
private const val QUANTIZED_MASK = QUANTIZED_LEVELS - 1
private const val HISTOGRAM_SIZE = QUANTIZED_LEVELS * QUANTIZED_LEVELS * QUANTIZED_LEVELS

// 优质像素的最低数量：低于该数说明这一段的色彩过于极端（纯黑封面、灰阶封面），
// 过滤条件会把候选清空，此时改用不过滤的边缘环全集再取色
private const val MIN_TRUSTED_PIXELS = 24

// 脏像素边界：近黑与近白都不是封面外缘的观感色，留在候选里只会把主色拖向两端
private const val LIGHTNESS_FLOOR = 0.06f
private const val LIGHTNESS_CEIL = 0.94f

// 近灰像素不作主色：灰正是「平均值偏灰」这一观感的源头，先把它挡在候选之外
private const val SATURATION_FLOOR = 0.08f

// 可采纳色彩的最低色度：色度取最大与最小通道之差，是与明度无关的「有多少颜色」度量。
// 暗处同样要有最低色度——那里的一点色度多来自压缩噪声，整幅平铺时会被读成一层偏色
// （近黑底上泛出的紫大多如此）；门槛随明度抬高放宽，纯白处为 0，
// 因为浅色处的弱彩是封面真实的淡彩，两者观感不同
private const val DARK_CHROMA_FLOOR = 0.10f

// 簇得分权重：彩度权重最高，因为背景要的是「有颜色」；占比次之，挡住偶发噪声色；
// 明度适中性再次，挡住过暗与过亮的簇
private const val SCORE_POPULATION_WEIGHT = 0.36f
private const val SCORE_SATURATION_WEIGHT = 0.44f
private const val SCORE_LIGHTNESS_WEIGHT = 0.20f

// 明度评优的中心：离它越远的簇得分越低
private const val PREFERRED_LIGHTNESS = 0.5f

// 饱和度补偿：簇内混色仍会削掉一部分彩度，把偏淡的主色补到 [SATURATION_TARGET] 附近。
// 补偿只做加法不越上限，本就鲜明的颜色因此原样保留，不会被补成荧光色；
// 阈值以下视为封面本身无色（灰片、黑白照），取消补偿，不给无彩封面凭空上色
private const val SATURATION_TARGET = 0.35f
private const val SATURATION_GAIN = 1.25f
private const val SATURATION_BOOST = 0.06f
private const val SATURATION_GAIN_THRESHOLD = 0.05f

// 色相收敛容差（度）：三段色的色相相对基准色超出该偏差即拉回，三个色块因此是相近色，
// 叠画后是连续过渡，不会出现两种色调直接相接的跳脱
private const val HUE_TOLERANCE_DEG = 20f

// 明度收敛容差：明暗差异过大同样会在色块相接处形成跳变，与色相同口径收敛
private const val LIGHTNESS_TOLERANCE = 0.1f

// 三段色的最小明度差：三段色调本就接近时（纯色封面）仍按上中下留出层级，
// 流动时色块边界可见，不至于整幅同色而看不出在流动
private const val LIGHTNESS_MIN_SPREAD = 0.06f

// 参与取色的最低不透明度：透明区域（抠图留白、圆形封面外）不代表封面边缘色调
private const val OPAQUE_ALPHA_MIN = 128

// 取色前的降采样边长：边缘环主色在这一档上已稳定，与显示端取色所用的略缩图同档
private const val COLOR_SAMPLE_EDGE_PX = 64

/**
 * 封面背景取色结果：封面四周边缘环自上而下的三段主色，三段已收敛为相近色。
 *
 * 背景流动的三个色块、关闭流动时的静态渐变、落盘与浮层底色全部取自这一份结果，
 * 背景因此在任何状态下都同源，切换流动开关只改变呈现方式而不改变色调。
 */
internal data class CoverBackgroundColors(
    val top: Color,
    val middle: Color,
    val bottom: Color,
) {
    // 自上而下的色序：静态渐变按此顺序铺开，流动帧按图层顺序取用
    val stops: List<Color> = listOf(top, middle, bottom)

    // 单一取值：三段色相近，任取一段都能代表整体色调，取中段供落盘与浮层底色复用
    val representative: Color = middle
}

/**
 * 封面背景取色：只采封面四周的边缘环，按纵向等分三段，每段在环内像素上取主色。
 *
 * 取样区域限定在外缘：背景正是与封面外缘相接的一片，取外缘色调才能与封面自然衔接，
 * 横竖屏封面的羽化边缘因此都接得上同色系的背景。
 *
 * 每段主色由中位切分（median cut）得出：像素先按 5 位/通道量化压掉噪声，再在量化直方图上
 * 反复取色域最宽的盒、沿其最长通道按像素中位二等分，直到切成 [SEGMENT_CLUSTER_COUNT] 个簇，
 * 各簇取加权平均色后按「占比 + 彩度 + 明度适中性」打分取最高者。
 * 不用段内算术平均：平均会把段内互补的色相相互抵消，饱和度塌陷成灰，
 * 边缘环里的黑边、白底与文字都会把平均色一步步拖向灰；量化择优取的是段内出现最多、
 * 最有彩度的那一簇，色调因此贴合封面外缘的实际观感。
 *
 * 近黑、近白与近灰像素不进候选（见 ColorQuantizer.isTrustedSample），
 * 但一段内可用像素过少时会回退到边缘环全集，纯黑与灰阶封面仍能取到色。
 * 「取到色」之后还要过一道色度的门槛：段内平均色达不到其明度下的最低色度（见 minChromaOf）时，
 * 该段判为无彩，主色只保留明度——择优挑出的可能是噪声里最有彩的那一撮，
 * 它的色相不代表这段的边缘色（见 [DARK_CHROMA_FLOOR]）。
 * 三段色随后向基准色收敛色相与明度并做饱和度补偿（见 harmonizeEdgeColors），
 * 背景流动的三个色块因此是相近色，叠画后过渡自然。
 *
 * 入参可以是任意尺寸的封面：内部先降到取样档再采边缘环，
 * 全尺寸封面（首页大图档长边 2048）因此不会按像素数分配出十几 MB 的数组。
 * 显示端（SongImmersiveBackground）与切歌预取共用本入口，使实时取色与预存取色同源。
 */
internal suspend fun extractCoverBackgroundColors(source: Bitmap): CoverBackgroundColors? =
    withContext(Dispatchers.IO) {
        // 硬件位图不可直接 getPixels，复制为软件位图后再取色
        val bitmap = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
        } else source
        bitmap.scaledToSample().edgeColors()
    }

// 降到取样档：逐级折半再落到目标边长。
// 单次大比例缩放会漏掉大量参与取色的像素，分级折半才让边缘环的主色稳定
private fun Bitmap.scaledToSample(): Bitmap {
    var current = this
    while (maxOf(current.width, current.height) > COLOR_SAMPLE_EDGE_PX * 2) {
        current = current.scale(
            (current.width / 2).coerceAtLeast(1),
            (current.height / 2).coerceAtLeast(1),
            filter = true,
        )
    }
    val longest = maxOf(current.width, current.height)
    if (longest <= COLOR_SAMPLE_EDGE_PX) return current
    val ratio = COLOR_SAMPLE_EDGE_PX.toFloat() / longest
    return current.scale(
        (current.width * ratio).toInt().coerceAtLeast(1),
        (current.height * ratio).toInt().coerceAtLeast(1),
        filter = true,
    )
}

// 边缘环三段主色：逐像素只采四周一圈，按纵向等分归段，每段交给量化器中位切分后择优
private fun Bitmap.edgeColors(): CoverBackgroundColors? {
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)

    val band = (minOf(width, height) * EDGE_BAND_RATIO).toInt().coerceAtLeast(1)
    // 段边界与下面的分段口径同为向上取整，缓冲容量才与写入的像素数一致
    val segmentStartRow = IntArray(EDGE_SEGMENT_COUNT) {
        (it * height + EDGE_SEGMENT_COUNT - 1) / EDGE_SEGMENT_COUNT
    }
    val segmentEndRow = IntArray(EDGE_SEGMENT_COUNT) {
        ((it + 1) * height + EDGE_SEGMENT_COUNT - 1) / EDGE_SEGMENT_COUNT
    }
    val trusted = Array(EDGE_SEGMENT_COUNT) {
        IntArray((segmentEndRow[it] - segmentStartRow[it]) * width)
    }
    val covered = Array(EDGE_SEGMENT_COUNT) { IntArray(trusted[it].size) }
    val trustedCount = IntArray(EDGE_SEGMENT_COUNT)
    val coveredCount = IntArray(EDGE_SEGMENT_COUNT)
    // 段内各通道的和，用于求段内平均色：判定该段带不带得住色彩只依据平均色
    val coveredSum = Array(EDGE_SEGMENT_COUNT) { LongArray(3) }
    val sampleHsl = FloatArray(3)

    for (y in 0 until height) {
        val row = y * width
        val segment = (y * EDGE_SEGMENT_COUNT / height).coerceAtMost(EDGE_SEGMENT_COUNT - 1)
        for (x in 0 until width) {
            // 行列同时落在内圈即非边缘，跳过
            if (x >= band && x < width - band && y >= band && y < height - band) continue
            val pixel = pixels[row + x]
            if (((pixel ushr 24) and 0xFF) < OPAQUE_ALPHA_MIN) continue
            covered[segment][coveredCount[segment]++] = pixel
            coveredSum[segment][0] += (pixel ushr 16) and 0xFF
            coveredSum[segment][1] += (pixel ushr 8) and 0xFF
            coveredSum[segment][2] += pixel and 0xFF
            if (ColorQuantizer.isTrustedSample(pixel, sampleHsl)) {
                trusted[segment][trustedCount[segment]++] = pixel
            }
        }
    }

    val quantizer = ColorQuantizer(width * height)
    val segmentColors = arrayOfNulls<FloatArray>(EDGE_SEGMENT_COUNT)
    for (index in 0 until EDGE_SEGMENT_COUNT) {
        // 优质像素不足时改用全集：纯黑与灰阶封面不会被过滤条件清空
        val useTrusted = trustedCount[index] >= MIN_TRUSTED_PIXELS
        val dominant = quantizer.dominantColor(
            pixels = if (useTrusted) trusted[index] else covered[index],
            count = if (useTrusted) trustedCount[index] else coveredCount[index],
        )
        // 段内平均色带不住色彩时本段判为无彩：色相与饱和度都不采纳，只留明度，底色因此是中性灰而不是偏色。
        // 判据取整段平均色而非择优簇——噪声在暗处的色度集中在最有彩的那一撮上，择优恰好会挑中它
        val meanHsl = coveredSum[index].meanHsl(coveredCount[index])
        segmentColors[index] = dominant?.let { color ->
            if (meanHsl == null || meanHsl.isChromatic()) color else color.neutralized()
        }
    }
    // 全段无非透明像素时没有可用色调
    val available = segmentColors.filterNotNull()
    if (available.isEmpty()) return null

    val baseHsl = averageHsl(available)
    // 该段无非透明像素时退回基准色，三段仍同源
    val segments = List(EDGE_SEGMENT_COUNT) { segmentColors[it] ?: baseHsl }
    return harmonizeEdgeColors(segments, baseHsl)
}

// 段内平均色：整段不透明像素的算术平均，段内无像素时返回 null
private fun LongArray.meanHsl(count: Int): FloatArray? {
    if (count <= 0) return null
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(
        0xFF shl 24 or ((this[0] / count).toInt() shl 16) or ((this[1] / count).toInt() shl 8) or
            (this[2] / count).toInt(),
        hsl,
    )
    return hsl
}

// 该色是否带得住色彩：明度越低要求的最低色度越高，详见 minChromaOf
private fun FloatArray.isChromatic(): Boolean = chroma() >= minChromaOf(this[2])

// 去掉色彩只留明度：色相与饱和度都来自噪声，留下只会让底色偏色
private fun FloatArray.neutralized(): FloatArray = floatArrayOf(this[0], 0f, this[2])

// 色度：最大通道与最小通道之差，与明度无关，同色度下明度高低都不改变它的值
private fun FloatArray.chroma(): Float = 2f * minOf(this[2], 1f - this[2]) * this[1]

// 某明度下可采纳的最低色度：近黑的底色带不住色彩，门槛随明度抬高线性放宽到纯白处的 0
private fun minChromaOf(lightness: Float): Float = DARK_CHROMA_FLOOR * (1f - lightness)

/**
 * 段内取色的量化器：直方图与各临时缓冲跨段复用，避免每段重新分配。
 * 只在单次取色的单线程内使用，故缓冲可直接复用而不加同步。
 */
private class ColorQuantizer(capacity: Int) {

    private val histogram = IntArray(HISTOGRAM_SIZE)
    private val candidates = IntArray(capacity)
    private val scratch = IntArray(capacity)
    private val channelCounts = IntArray(QUANTIZED_LEVELS)

    /**
     * 取段主色：像素先量化建直方图，再对非空量化色做中位切分，各簇取加权平均色后按得分择优。
     * 段内没有可用像素时返回 null，由调用方退回基准色。
     */
    fun dominantColor(pixels: IntArray, count: Int): FloatArray? {
        if (count <= 0) return null
        histogram.fill(0)
        var candidateCount = 0
        for (index in 0 until count) {
            val quantized = quantize(pixels[index])
            // 同一量化色只登记一次，出现次数记在直方图里
            if (histogram[quantized] == 0) candidates[candidateCount++] = quantized
            histogram[quantized]++
        }

        var best: FloatArray? = null
        var bestScore = -1f
        for (box in medianCut(candidateCount)) {
            val cluster = summarize(box)
            val score = clusterScore(cluster.count, count, cluster.hsl)
            if (score > bestScore) {
                bestScore = score
                best = cluster.hsl
            }
        }
        return best
    }

    companion object {

        // 脏像素判定：近黑、近白与近灰都不是封面外缘的观感色。
        // [hsl] 为调用方复用的中转数组，避免逐像素分配
        fun isTrustedSample(pixel: Int, hsl: FloatArray): Boolean {
            ColorUtils.colorToHSL(pixel, hsl)
            return hsl[2] in LIGHTNESS_FLOOR..LIGHTNESS_CEIL && hsl[1] >= SATURATION_FLOOR
        }
    }

    // 中位切分：反复取色域最宽的盒，沿其最长通道按像素中位二等分，直到盒数达到簇数上限。
    // 盒只记量化色数组上的区间，切分靠计数排序就地重排，不复制像素；
    // 色域已收窄到一个量化档的盒不再参与切分，避免切出无意义的碎簇
    private fun medianCut(candidateCount: Int): List<CandidateBox> {
        if (candidateCount <= 0) return emptyList()
        val initial = candidateBox(0, candidateCount)
        val boxes = ArrayList<CandidateBox>(SEGMENT_CLUSTER_COUNT)
        boxes += initial
        val cuttable = ArrayList<CandidateBox>(SEGMENT_CLUSTER_COUNT)
        if (initial.longestRange > 0) cuttable += initial
        while (boxes.size < SEGMENT_CLUSTER_COUNT) {
            val box = cuttable.maxByOrNull { it.longestRange } ?: break
            cuttable.remove(box)
            val split = splitPosition(box)
            if (split <= box.start || split >= box.end) continue
            val left = candidateBox(box.start, split)
            val right = candidateBox(split, box.end)
            boxes.remove(box)
            boxes += left
            boxes += right
            if (left.longestRange > 0) cuttable += left
            if (right.longestRange > 0) cuttable += right
        }
        return boxes
    }

    // 盒的通道跨度
    private fun candidateBox(start: Int, end: Int): CandidateBox {
        var minR = QUANTIZED_MASK
        var maxR = 0
        var minG = QUANTIZED_MASK
        var maxG = 0
        var minB = QUANTIZED_MASK
        var maxB = 0
        for (index in start until end) {
            val quantized = candidates[index]
            val r = (quantized ushr (2 * QUANTIZED_BITS_PER_CHANNEL)) and QUANTIZED_MASK
            val g = (quantized ushr QUANTIZED_BITS_PER_CHANNEL) and QUANTIZED_MASK
            val b = quantized and QUANTIZED_MASK
            if (r < minR) minR = r
            if (r > maxR) maxR = r
            if (g < minG) minG = g
            if (g > maxG) maxG = g
            if (b < minB) minB = b
            if (b > maxB) maxB = b
        }
        return CandidateBox(start, end, maxR - minR, maxG - minG, maxB - minB)
    }

    // 盒的切分点：按最长通道排序后取像素中位，切出的两盒权重相当。
    // 落在端点上说明切不动，返回原区间边界由调用方判定
    private fun splitPosition(box: CandidateBox): Int {
        sortByChannel(box.start, box.end, box.longestChannel)
        val total = (box.start until box.end).sumOf { histogram[candidates[it]] }
        var accumulated = 0
        for (index in box.start until box.end) {
            accumulated += histogram[candidates[index]]
            if (accumulated * 2 >= total && index + 1 < box.end) return index + 1
        }
        return box.end
    }

    // 区间内按通道计数排序：通道只有 32 档，计数排序无需比较也不装箱
    private fun sortByChannel(start: Int, end: Int, channel: Int) {
        val offset = (2 - channel) * QUANTIZED_BITS_PER_CHANNEL
        channelCounts.fill(0)
        for (index in start until end) {
            channelCounts[(candidates[index] ushr offset) and QUANTIZED_MASK]++
        }
        var position = start
        for (level in 0 until QUANTIZED_LEVELS) {
            val count = channelCounts[level]
            channelCounts[level] = position
            position += count
        }
        for (index in start until end) {
            val value = candidates[index]
            scratch[channelCounts[(value ushr offset) and QUANTIZED_MASK]++] = value
        }
        scratch.copyInto(candidates, destinationOffset = start, startIndex = start, endIndex = end)
    }

    // 簇的平均色与像素数：按直方图计数加权，量化色还原时补回半档偏移
    private fun summarize(box: CandidateBox): ClusterColor {
        var count = 0
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        for (index in box.start until box.end) {
            val quantized = candidates[index]
            val weight = histogram[quantized]
            count += weight
            sumR += weight.toLong() * dequantize((quantized ushr (2 * QUANTIZED_BITS_PER_CHANNEL)) and QUANTIZED_MASK)
            sumG += weight.toLong() * dequantize((quantized ushr QUANTIZED_BITS_PER_CHANNEL) and QUANTIZED_MASK)
            sumB += weight.toLong() * dequantize(quantized and QUANTIZED_MASK)
        }
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(
            0xFF shl 24 or ((sumR / count).toInt() shl 16) or ((sumG / count).toInt() shl 8) or
                (sumB / count).toInt(),
            hsl,
        )
        return ClusterColor(count, hsl)
    }

    // 簇得分：占比、彩度、明度适中性三者加权，各项都已归一到 0..1，得分即三者加权和
    private fun clusterScore(count: Int, total: Int, hsl: FloatArray): Float {
        val population = count.toFloat() / total
        val lightnessFit = 1f - abs(hsl[2] - PREFERRED_LIGHTNESS) / PREFERRED_LIGHTNESS
        return population * SCORE_POPULATION_WEIGHT +
            hsl[1] * SCORE_SATURATION_WEIGHT +
            lightnessFit * SCORE_LIGHTNESS_WEIGHT
    }
}

// 中位切分的一个色盒：只记量化色数组上的区间与该区间各通道的跨度
private class CandidateBox(
    val start: Int,
    val end: Int,
    val rangeR: Int,
    val rangeG: Int,
    val rangeB: Int,
) {
    // 最长通道：沿它切分，色域划分最均匀
    val longestChannel: Int = when {
        rangeR >= rangeG && rangeR >= rangeB -> 0
        rangeG >= rangeB -> 1
        else -> 2
    }

    val longestRange: Int = maxOf(rangeR, rangeG, rangeB)
}

// 一个簇的平均色与像素数
private class ClusterColor(val count: Int, val hsl: FloatArray)

// 量化：每通道只留高 5 位，低 3 位是噪声
private fun quantize(pixel: Int): Int {
    val r = ((pixel ushr 16) and 0xFF) shr QUANTIZE_SHIFT
    val g = ((pixel ushr 8) and 0xFF) shr QUANTIZE_SHIFT
    val b = (pixel and 0xFF) shr QUANTIZE_SHIFT
    return (r shl (2 * QUANTIZED_BITS_PER_CHANNEL)) or (g shl QUANTIZED_BITS_PER_CHANNEL) or b
}

// 量化色还原为通道值：补回半档偏移，截断取整不会让整幅色调偏暗半档
private fun dequantize(level: Int): Int = (level shl QUANTIZE_SHIFT) + (1 shl (QUANTIZE_SHIFT - 1))

// 三段主色的基准：色相取圆形平均（色相是环形量，直接算术平均在 0°/360° 交界处会出错），
// 各段的色相按其色度加权——无彩色的色相只是噪声，不该左右三段共同的基准；
// 明度与饱和度取算术平均。基准只作三段收敛的中心与空段的回退色
private fun averageHsl(colors: List<FloatArray>): FloatArray {
    var x = 0f
    var y = 0f
    var saturation = 0f
    var lightness = 0f
    colors.forEach { hsl ->
        val weight = hsl.chroma()
        val radians = hsl[0] * PI / 180.0
        x += weight * cos(radians).toFloat()
        y += weight * sin(radians).toFloat()
        saturation += hsl[1]
        lightness += hsl[2]
    }
    val hue = (atan2(y.toDouble(), x.toDouble()) * 180.0 / PI).toFloat() + 360f
    return floatArrayOf(hue % 360f, saturation / colors.size, lightness / colors.size)
}

// 三段色的收敛：色相与明度相对基准色的偏差各自截断到容差内，三段因此相近而仍保留层次；
// 彩度按 [boostSaturation] 补偿，抵掉簇内混色带来的偏淡
private fun harmonizeEdgeColors(segments: List<FloatArray>, baseHsl: FloatArray): CoverBackgroundColors {
    val clamped = segments.map { hsl ->
        val hue = baseHsl[0] +
            shortestHueDelta(hsl[0], baseHsl[0]).coerceIn(-HUE_TOLERANCE_DEG, HUE_TOLERANCE_DEG)
        val lightness = (baseHsl[2] +
            (hsl[2] - baseHsl[2]).coerceIn(-LIGHTNESS_TOLERANCE, LIGHTNESS_TOLERANCE)).coerceIn(0f, 1f)
        floatArrayOf((hue + 360f) % 360f, boostSaturation(hsl[1]), lightness)
    }
    // 三段明度过于接近时按上、中、下对称展开到最小明度差，保证流动时色块边界可见
    if (clamped.maxOf { it[2] } - clamped.minOf { it[2] } < LIGHTNESS_MIN_SPREAD) {
        val half = LIGHTNESS_MIN_SPREAD / 2f
        for (index in 0 until EDGE_SEGMENT_COUNT) {
            clamped[index][2] = (baseHsl[2] + (1 - index) * half).coerceIn(0f, 1f)
        }
    }
    return CoverBackgroundColors(
        top = clamped[0].toColor(),
        middle = clamped[1].toColor(),
        bottom = clamped[2].toColor(),
    )
}

// 饱和度补偿：只把偏淡的主色补到目标附近，本就鲜明的主色原样返回
private fun boostSaturation(saturation: Float): Float {
    if (saturation < SATURATION_GAIN_THRESHOLD) return saturation
    return maxOf(saturation, minOf(SATURATION_TARGET, saturation * SATURATION_GAIN + SATURATION_BOOST))
}

// 色相是环形量：取两色之间的最短转角，避免 350° 与 10° 被判成 340° 的差
private fun shortestHueDelta(from: Float, to: Float): Float = ((from - to + 540f) % 360f) - 180f

private fun FloatArray.toColor(): Color = Color(ColorUtils.HSLToColor(this))
