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

// 边缘取样带宽占短边 0.14，只取外缘一圈
private const val EDGE_BAND_RATIO = 0.14f

// 纵向分三段，各取一主色作色标
private const val EDGE_SEGMENT_COUNT = 3

// 每段中位切分簇数上限
private const val SEGMENT_CLUSTER_COUNT = 8

// 每通道量化取高 5 位，低 3 位是噪声
private const val QUANTIZE_SHIFT = 3
private const val QUANTIZED_BITS_PER_CHANNEL = 8 - QUANTIZE_SHIFT
private const val QUANTIZED_LEVELS = 1 shl QUANTIZED_BITS_PER_CHANNEL
private const val QUANTIZED_MASK = QUANTIZED_LEVELS - 1
private const val HISTOGRAM_SIZE = QUANTIZED_LEVELS * QUANTIZED_LEVELS * QUANTIZED_LEVELS

// 优质像素下限，过少时改用边缘环全集
private const val MIN_TRUSTED_PIXELS = 24

// 近黑与近白不进候选
private const val LIGHTNESS_FLOOR = 0.06f
private const val LIGHTNESS_CEIL = 0.94f

// 近灰不作主色
private const val SATURATION_FLOOR = 0.08f

// 最低色度门槛，随明度放宽到纯白为 0
private const val DARK_CHROMA_FLOOR = 0.10f

// 簇得分权重：彩度最高，占比次之，明度第三
private const val SCORE_POPULATION_WEIGHT = 0.36f
private const val SCORE_SATURATION_WEIGHT = 0.44f
private const val SCORE_LIGHTNESS_WEIGHT = 0.20f

// 明度评优中心
private const val PREFERRED_LIGHTNESS = 0.5f

// 饱和度补偿：只补偏淡色，灰片不补
private const val SATURATION_TARGET = 0.35f
private const val SATURATION_GAIN = 1.25f
private const val SATURATION_BOOST = 0.06f
private const val SATURATION_GAIN_THRESHOLD = 0.05f

// 色相收敛容差 20°
private const val HUE_TOLERANCE_DEG = 20f

// 明度收敛容差
private const val LIGHTNESS_TOLERANCE = 0.1f

// 三段最小明度差，保证流动可见
private const val LIGHTNESS_MIN_SPREAD = 0.06f

// 参与取色的最低不透明度
private const val OPAQUE_ALPHA_MIN = 128

// 取色前降采样边长
private const val COLOR_SAMPLE_EDGE_PX = 64

/** 封面边缘三段主色，已收敛为相近色 */
internal data class CoverBackgroundColors(
    val top: Color,
    val middle: Color,
    val bottom: Color,
) {
    // 取中段作代表色，供落盘与浮层底色
    val representative: Color = middle
}

/** 取封面边缘三段主色并收敛相近色；内部先降采样 */
internal suspend fun extractCoverBackgroundColors(source: Bitmap): CoverBackgroundColors? =
    withContext(Dispatchers.IO) {
        // 硬件位图须先复制为软件位图
        val bitmap = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
        } else source
        bitmap.scaledToSample().edgeColors()
    }

// 逐级折半降采样，单次大比例缩放会漏像素
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

// 边缘环纵向三段取主色
private fun Bitmap.edgeColors(): CoverBackgroundColors? {
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)

    val band = (minOf(width, height) * EDGE_BAND_RATIO).toInt().coerceAtLeast(1)
    // 段边界向上取整，与缓冲容量一致
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
    // 段内各通道和，用于平均色
    val coveredSum = Array(EDGE_SEGMENT_COUNT) { LongArray(3) }
    val sampleHsl = FloatArray(3)

    for (y in 0 until height) {
        val row = y * width
        val segment = (y * EDGE_SEGMENT_COUNT / height).coerceAtMost(EDGE_SEGMENT_COUNT - 1)
        for (x in 0 until width) {
            // 内圈像素跳过
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
        // 优质像素不足时改用全集
        val useTrusted = trustedCount[index] >= MIN_TRUSTED_PIXELS
        val dominant = quantizer.dominantColor(
            pixels = if (useTrusted) trusted[index] else covered[index],
            count = if (useTrusted) trustedCount[index] else coveredCount[index],
        )
        // 平均色带不住色彩则判为无彩，只留明度
        val meanHsl = coveredSum[index].meanHsl(coveredCount[index])
        segmentColors[index] = dominant?.let { color ->
            if (meanHsl == null || meanHsl.isChromatic()) color else color.neutralized()
        }
    }
    // 无非透明像素则无色调
    val available = segmentColors.filterNotNull()
    if (available.isEmpty()) return null

    val baseHsl = averageHsl(available)
    // 空段退回基准色
    val segments = List(EDGE_SEGMENT_COUNT) { segmentColors[it] ?: baseHsl }
    return harmonizeEdgeColors(segments, baseHsl)
}

// 段内平均色：不透明像素算术平均，无像素返回 null
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

// 是否带得住色彩
private fun FloatArray.isChromatic(): Boolean = chroma() >= minChromaOf(this[2])

// 只留明度，去掉噪声色相
private fun FloatArray.neutralized(): FloatArray = floatArrayOf(this[0], 0f, this[2])

// 色度：最大与最小通道之差，与明度无关
private fun FloatArray.chroma(): Float = 2f * minOf(this[2], 1f - this[2]) * this[1]

// 某明度下最低色度
private fun minChromaOf(lightness: Float): Float = DARK_CHROMA_FLOOR * (1f - lightness)

/** 段内取色量化器，缓冲跨段复用，单线程使用 */
private class ColorQuantizer(capacity: Int) {

    private val histogram = IntArray(HISTOGRAM_SIZE)
    private val candidates = IntArray(capacity)
    private val scratch = IntArray(capacity)
    private val channelCounts = IntArray(QUANTIZED_LEVELS)

    /** 取段主色：量化建直方图，中位切分后择优 */
    fun dominantColor(pixels: IntArray, count: Int): FloatArray? {
        if (count <= 0) return null
        histogram.fill(0)
        var candidateCount = 0
        for (index in 0 until count) {
            val quantized = quantize(pixels[index])
            // 同一量化色只登记一次
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

        // 脏像素判定；hsl 为复用中转数组
        fun isTrustedSample(pixel: Int, hsl: FloatArray): Boolean {
            ColorUtils.colorToHSL(pixel, hsl)
            return hsl[2] in LIGHTNESS_FLOOR..LIGHTNESS_CEIL && hsl[1] >= SATURATION_FLOOR
        }
    }

    // 中位切分：取最宽盒沿最长通道二分
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

    // 切分点：最长通道排序后取像素中位
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

    // 按通道计数排序，32 档无需比较
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

    // 簇平均色与像素数，还原量化色补半档偏移
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

    // 簇得分：占比/彩度/明度加权
    private fun clusterScore(count: Int, total: Int, hsl: FloatArray): Float {
        val population = count.toFloat() / total
        val lightnessFit = 1f - abs(hsl[2] - PREFERRED_LIGHTNESS) / PREFERRED_LIGHTNESS
        return population * SCORE_POPULATION_WEIGHT +
            hsl[1] * SCORE_SATURATION_WEIGHT +
            lightnessFit * SCORE_LIGHTNESS_WEIGHT
    }
}

// 中位切分的一个色盒：区间与各通道跨度
private class CandidateBox(
    val start: Int,
    val end: Int,
    val rangeR: Int,
    val rangeG: Int,
    val rangeB: Int,
) {
    // 最长通道，沿它切分
    val longestChannel: Int = when {
        rangeR >= rangeG && rangeR >= rangeB -> 0
        rangeG >= rangeB -> 1
        else -> 2
    }

    val longestRange: Int = maxOf(rangeR, rangeG, rangeB)
}

// 一个簇的平均色与像素数
private class ClusterColor(val count: Int, val hsl: FloatArray)

// 量化：每通道留高 5 位
private fun quantize(pixel: Int): Int {
    val r = ((pixel ushr 16) and 0xFF) shr QUANTIZE_SHIFT
    val g = ((pixel ushr 8) and 0xFF) shr QUANTIZE_SHIFT
    val b = (pixel and 0xFF) shr QUANTIZE_SHIFT
    return (r shl (2 * QUANTIZED_BITS_PER_CHANNEL)) or (g shl QUANTIZED_BITS_PER_CHANNEL) or b
}

// 量化还原补半档偏移
private fun dequantize(level: Int): Int = (level shl QUANTIZE_SHIFT) + (1 shl (QUANTIZE_SHIFT - 1))

// 基准色：色相按色度加权圆形平均，明度/饱和取算术平均
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

// 三段收敛到容差内并补彩度
private fun harmonizeEdgeColors(segments: List<FloatArray>, baseHsl: FloatArray): CoverBackgroundColors {
    val clamped = segments.map { hsl ->
        val hue = baseHsl[0] +
            shortestHueDelta(hsl[0], baseHsl[0]).coerceIn(-HUE_TOLERANCE_DEG, HUE_TOLERANCE_DEG)
        val lightness = (baseHsl[2] +
            (hsl[2] - baseHsl[2]).coerceIn(-LIGHTNESS_TOLERANCE, LIGHTNESS_TOLERANCE)).coerceIn(0f, 1f)
        floatArrayOf((hue + 360f) % 360f, boostSaturation(hsl[1]), lightness)
    }
    // 明度接近时按上中下展开到最小差
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

// 只补偏淡色，鲜明色原样返回
private fun boostSaturation(saturation: Float): Float {
    if (saturation < SATURATION_GAIN_THRESHOLD) return saturation
    return maxOf(saturation, minOf(SATURATION_TARGET, saturation * SATURATION_GAIN + SATURATION_BOOST))
}

// 环形色相最短转角，避免 350° 与 10° 误判
internal fun shortestHueDelta(from: Float, to: Float): Float = ((from - to + 540f) % 360f) - 180f

private fun FloatArray.toColor(): Color = Color(ColorUtils.HSLToColor(this))
