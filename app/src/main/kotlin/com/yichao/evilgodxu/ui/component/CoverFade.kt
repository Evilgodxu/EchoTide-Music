package com.yichao.evilgodxu.ui.component

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// 封面边缘渐隐带长度占封面边长的比例：封面由此比例起渐隐为透明，融入封面衍生的沉浸背景。
// 竖屏封面下缘与横屏封面四边同取本比例，两种形态的过渡带宽度口径一致
internal const val COVER_FADE_RATIO = 0.3f

// 渐隐曲线的采样段数：色标之间为线性插值，段数越多越逼近解析曲线。
// 16 段时单段透明度变化不足 1/255，肉眼无法分辨；继续加密只增加色标数量，观感不再变化
private const val FADE_SAMPLE_SEGMENTS = 16

/**
 * 平滑过渡曲线：t 为过渡带内的归一化位置，返回由 1 平滑收敛到 0 的透明度，即 smootherstep 的补。
 * 两端的一阶与二阶导数均为 0，与带外的常值段（全不透明、全透明）平滑相接。
 * 线性渐隐只在分段折点处连续，斜率的突变会被读成一条分界带，故过渡一律按本曲线采样。
 * 单边渐隐、双边羽化与背景压暗层缓动共用本曲线，衔接处不会出现折点。
 */
internal fun smoothFadeAlpha(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return 1f - x * x * x * (x * (x * 6f - 15f) + 10f)
}

/**
 * 单边渐隐色标：0 到 [start] 为 [color] 的实色段，[start] 到 [end] 按 [smoothFadeAlpha] 采样淡出至全透明。
 * 末档取自采样的终点：与 [color] 同色而透明度为 0，避免 RGB 在末段向黑色插值而渗出灰调。
 * [end] 不大于 [start]（没有可过渡的距离）时退化为一条实色到透明的最小色标。
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
 * 双边渐隐色标：轴线两端各留 [ratio] 的渐隐带，中间为实色段，得到贴靠两侧的对称羽化。
 * 起始带按 1 - t 采样而非另取曲线：[smoothFadeAlpha] 关于带中心对称（f(1 - t) = 1 - f(t)），
 * 两侧的过渡轮廓因此互为镜像，同一轴向的两条边观感一致。
 * [ratio] 上限取 0.5：再宽两条渐隐带会相互侵入，中段实色消失。
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
 * 构造竖直单边渐隐渐变：0 到 [start] 为 [color] 的实色段，[start] 到 [end] 平滑淡出至全透明。
 * 用于只在一条边贴背景的场景（竖屏封面下缘）。
 */
internal fun coverFadeBrush(color: Color, start: Float, end: Float): Brush =
    Brush.verticalGradient(colorStops = fadeOutStops(color, start, end))

/** 构造上下边缘羽化渐变：顶边与底边各按 [ratio] 的带宽渐隐，供四边羽化的竖直方向使用。 */
internal fun coverTopBottomFadeBrush(color: Color, ratio: Float = COVER_FADE_RATIO): Brush =
    Brush.verticalGradient(colorStops = edgeFeatherStops(color, ratio))

/** 构造左右边缘羽化渐变：左边与右边各按 [ratio] 的带宽渐隐，供四边羽化的水平方向使用。 */
internal fun coverLeftRightFadeBrush(color: Color, ratio: Float = COVER_FADE_RATIO): Brush =
    Brush.horizontalGradient(colorStops = edgeFeatherStops(color, ratio))
