package com.yichao.evilgodxu.ui.component

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// 封面下缘渐隐带长度占封面高度的比例：封面底部由此比例起渐隐为透明，融入封面衍生的沉浸背景
internal const val COVER_FADE_RATIO = 0.3f

// 渐隐曲线的采样段数：色标之间为线性插值，段数越多越逼近解析曲线。
// 16 段时单段透明度变化不足 1/255，肉眼无法分辨；继续加密只增加色标数量，观感不再变化
private const val FADE_SAMPLE_SEGMENTS = 16

/**
 * 平滑过渡曲线：t 为过渡带内的归一化位置，返回由 1 平滑收敛到 0 的透明度，即 smootherstep 的补。
 * 两端的一阶与二阶导数均为 0，与带外的常值段（全不透明、全透明）平滑相接。
 * 线性渐隐只在分段折点处连续，斜率的突变会被读成一条分界带，故过渡一律按本曲线采样。
 * 封面下缘渐隐与背景压暗层缓动共用本曲线，衔接处不会出现折点。
 */
internal fun smoothFadeAlpha(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return 1f - x * x * x * (x * (x * 6f - 15f) + 10f)
}

/**
 * 构造竖直渐隐渐变：0 到 [start] 为 [color] 的实色段，[start] 到 [end] 按 [smoothFadeAlpha] 采样淡出至全透明。
 * 末档取自采样的终点：与 [color] 同色而透明度为 0，避免 RGB 在末段向黑色插值而渗出灰调。
 * [end] 不大于 [start]（没有可过渡的距离）时退化为一条实色到透明的最小色标。
 */
internal fun coverFadeBrush(color: Color, start: Float, end: Float): Brush {
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
    return Brush.verticalGradient(colorStops = stops.toTypedArray())
}
