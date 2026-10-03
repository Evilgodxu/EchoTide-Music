package com.yichao.evilgodxu.data.music.metadata

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 边缘取样带宽度占封面短边的比例：只取封面四周的一圈，避开画面中心的主体与文字。
// 背景正是与封面外缘相接的一片，取外缘色调才能与封面自然衔接
private const val EDGE_BAND_RATIO = 0.14f

// 边缘环的纵向等分数：自上而下三段平均色，分别作背景的三个色标
private const val EDGE_SEGMENT_COUNT = 3

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

// 取色前的降采样边长：边缘平均色在这一档上已稳定，与显示端取色所用的略缩图同档
private const val COLOR_SAMPLE_EDGE_PX = 64

/**
 * 封面边缘取色结果：封面四周边缘环自上而下的三段色，三段已收敛为相近色。
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
 * 封面背景取色：只采封面四周的边缘环，按纵向等分三段各取平均色。
 *
 * 不用全图直方图主色调：主色调判定会被画面中心的主体色块左右，脏色过滤又会剔掉低彩度色，
 * 两者都可能让背景色调偏离封面外缘的实际观感，而背景正是与封面外缘相接的一片。
 * 三段色再向整环平均色收敛色相与明度（见 harmonizeEdgeColors），
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
// 单次大比例缩放会漏掉大量参与平均的像素，分级折半才让边缘环的平均色稳定
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

// 边缘环三段平均色：逐像素只累计四周一圈，按纵向等分归段；整环平均色作三段的收敛基准
private fun Bitmap.edgeColors(): CoverBackgroundColors? {
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)

    val band = (minOf(width, height) * EDGE_BAND_RATIO).toInt().coerceAtLeast(1)
    val counts = LongArray(EDGE_SEGMENT_COUNT)
    val sumR = LongArray(EDGE_SEGMENT_COUNT)
    val sumG = LongArray(EDGE_SEGMENT_COUNT)
    val sumB = LongArray(EDGE_SEGMENT_COUNT)
    for (y in 0 until height) {
        val row = y * width
        val segment = (y * EDGE_SEGMENT_COUNT / height).coerceAtMost(EDGE_SEGMENT_COUNT - 1)
        for (x in 0 until width) {
            // 行列同时落在内圈即非边缘，跳过
            if (x >= band && x < width - band && y >= band && y < height - band) continue
            val pixel = pixels[row + x]
            if (((pixel ushr 24) and 0xFF) < OPAQUE_ALPHA_MIN) continue
            counts[segment]++
            sumR[segment] += (pixel ushr 16) and 0xFF
            sumG[segment] += (pixel ushr 8) and 0xFF
            sumB[segment] += pixel and 0xFF
        }
    }

    var totalCount = 0L
    var totalR = 0L
    var totalG = 0L
    var totalB = 0L
    for (index in 0 until EDGE_SEGMENT_COUNT) {
        totalCount += counts[index]
        totalR += sumR[index]
        totalG += sumG[index]
        totalB += sumB[index]
    }
    // 全图无非透明像素时没有可用色调
    if (totalCount == 0L) return null

    val baseHsl = averageColor(totalR, totalG, totalB, totalCount).toHsl()
    val segments = List(EDGE_SEGMENT_COUNT) { index ->
        // 该段无非透明像素时退回整环平均色，三段仍同源
        if (counts[index] == 0L) {
            baseHsl
        } else {
            averageColor(sumR[index], sumG[index], sumB[index], counts[index]).toHsl()
        }
    }
    return harmonizeEdgeColors(segments, baseHsl)
}

// 三段色的收敛：色相与明度相对基准色的偏差各自截断到容差内，三段因此相近而仍保留层次
private fun harmonizeEdgeColors(segments: List<FloatArray>, baseHsl: FloatArray): CoverBackgroundColors {
    val clamped = segments.map { hsl ->
        val hue = baseHsl[0] +
            shortestHueDelta(hsl[0], baseHsl[0]).coerceIn(-HUE_TOLERANCE_DEG, HUE_TOLERANCE_DEG)
        val lightness = baseHsl[2] +
            (hsl[2] - baseHsl[2]).coerceIn(-LIGHTNESS_TOLERANCE, LIGHTNESS_TOLERANCE)
        floatArrayOf((hue + 360f) % 360f, hsl[1], lightness)
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

// 色相是环形量：取两色之间的最短转角，避免 350° 与 10° 被判成 340° 的差
private fun shortestHueDelta(from: Float, to: Float): Float = ((from - to + 540f) % 360f) - 180f

private fun averageColor(r: Long, g: Long, b: Long, count: Long): Color = Color(
    red = (r / count).toFloat() / 255f,
    green = (g / count).toFloat() / 255f,
    blue = (b / count).toFloat() / 255f,
)

private fun Color.toHsl(): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(toArgb(), it) }

private fun FloatArray.toColor(): Color = Color(ColorUtils.HSLToColor(this))
