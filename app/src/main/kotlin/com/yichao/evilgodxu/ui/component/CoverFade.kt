package com.yichao.evilgodxu.ui.component

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// 渐隐带占封面边长 0.3，竖屏横屏同口径
internal const val COVER_FADE_RATIO = 0.3f

// 渐隐采样 16 段，单段透明度差不足 1/255
private const val FADE_SAMPLE_SEGMENTS = 16

/**
 * 平滑渐隐曲线，两端导数为 0
 */
internal fun smoothFadeAlpha(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return 1f - x * x * x * (x * (x * 6f - 15f) + 10f)
}

/**
 * 单边渐隐色标，末档同色全透明
 */
private fun fadeOutStops(color: Color, start: Float, end: Float): Array<Pair<Float, Color>> {
    val span = end - start
    val stops = ArrayList<Pair<Float, Color>>(FADE_SAMPLE_SEGMENTS + 2)
    stops += 0f to color
    stops += start to color
    if (span > 0f) {
        for (segment in 1..FADE_SAMPLE_SEGMENTS) {
            val t = segment.toFloat() / FADE_SAMPLE_SEGMENTS
            stops += (start + span * t) to color.copy(alpha = smoothFadeAlpha(t))
        }
    } else {
        stops += start to color.copy(alpha = 0f)
    }
    return stops.toTypedArray()
}

/**
 * 单边渐入色标，与渐隐镜像
 */
private fun fadeInStops(color: Color, end: Float): Array<Pair<Float, Color>> {
    val span = end.coerceIn(0f, 1f)
    if (span <= 0f) return arrayOf(0f to color, 1f to color)
    val stops = ArrayList<Pair<Float, Color>>(FADE_SAMPLE_SEGMENTS + 2)
    stops += 0f to color.copy(alpha = 0f)
    for (segment in 1..FADE_SAMPLE_SEGMENTS) {
        val t = segment.toFloat() / FADE_SAMPLE_SEGMENTS
        stops += (span * t) to color.copy(alpha = smoothFadeAlpha(1f - t))
    }
    // span 取 1 时末档采样即实色，无需再补
    if (span < 1f) stops += 1f to color
    return stops.toTypedArray()
}

/**
 * 双边羽化色标，带宽上限 0.5
 */
private fun edgeFeatherStops(color: Color, ratio: Float): Array<Pair<Float, Color>> {
    val band = ratio.coerceIn(0f, 0.5f)
    if (band <= 0f) return arrayOf(0f to color, 1f to color)
    val stops = ArrayList<Pair<Float, Color>>(2 * FADE_SAMPLE_SEGMENTS + 3)
    stops += 0f to color.copy(alpha = 0f)
    for (segment in 1..FADE_SAMPLE_SEGMENTS) {
        val t = segment.toFloat() / FADE_SAMPLE_SEGMENTS
        stops += (band * t) to color.copy(alpha = smoothFadeAlpha(1f - t))
    }
    for (segment in 0 until FADE_SAMPLE_SEGMENTS) {
        val t = segment.toFloat() / FADE_SAMPLE_SEGMENTS
        stops += (1f - band + band * t) to color.copy(alpha = smoothFadeAlpha(t))
    }
    stops += 1f to color.copy(alpha = 0f)
    return stops.toTypedArray()
}

/**
 * 竖直单边渐隐，用于封面下缘
 */
internal fun coverFadeBrush(color: Color, start: Float, end: Float): Brush =
    Brush.verticalGradient(colorStops = fadeOutStops(color, start, end))

/**
 * 顶边渐入，如展开列表蒙层
 */
internal fun edgeFadeInBrush(color: Color, end: Float): Brush =
    Brush.verticalGradient(colorStops = fadeInStops(color, end))

/** 上下边缘羽化 */
internal fun coverTopBottomFadeBrush(color: Color, ratio: Float = COVER_FADE_RATIO): Brush =
    Brush.verticalGradient(colorStops = edgeFeatherStops(color, ratio))

/** 左右边缘羽化 */
internal fun coverLeftRightFadeBrush(color: Color, ratio: Float = COVER_FADE_RATIO): Brush =
    Brush.horizontalGradient(colorStops = edgeFeatherStops(color, ratio))
