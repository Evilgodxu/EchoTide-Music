package com.yichao.evilgodxu.ui.component

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import com.yichao.evilgodxu.data.music.metadata.shortestHueDelta

// 色相锚定容差（度）：帧内每个有色像素的色相沿色环最短方向向底色主色收拢到这个范围内。
// 背景帧的最后一步是通道算术平均（模糊），它与「两个色标直接线性渐变」是同一件事：
// 橙色与蓝紫直接平均，落点正在色环中段、饱和度最低处，画面于是浮出一层灰浊色，色相跨度越大越明显。
// 收拢到容差内之后，任意两像素的色相夹角不超过两倍容差，平均路径始终走在色环同侧，不再穿过灰心。
// 取 30°：两像素最远相差 60°，平均点的饱和度仍保留约 87%，既除掉灰浊又留住封面的多色结构；
// 更窄会压成单色滤镜，更宽则开始有画面重新落入浑浊区
internal const val HUE_ANCHOR_TOLERANCE_DEG = 30f

// 可锚定的最低饱和度：低于此值视为无彩（黑边、白底、画面里的白字），没有色相可锚，原样保留。
// 阈值与取色模块的 SATURATION_FLOOR 同口径
internal const val HUE_ANCHOR_MIN_SATURATION = 0.08f

// 被锚定像素的彩度回补：色相被夹向主色的像素，观感上彩度会跟着淡一档，按此系数补回一点。
// 只作用于真正被夹取的像素，本就在容差内的像素分毫不动，因此不会把整幅画面推向荧光
private const val HUE_ANCHOR_SATURATION_GAIN = 1.08f

/**
 * 把一个色相锚定到 [anchorHue] 的容差范围内：偏差在容差内原样返回，超出则夹到边界。
 *
 * 夹取而非整体替换是关键——整体替换会把封面压成单色，夹取保住容差内原有的色相差异，
 * 画面因此仍是「同一色系里的几个色」，而不是一片同色。
 * 色相是环形量，偏差走最短转角（见 [shortestHueDelta]），350° 与 10° 之间不会被判成 340°。
 */
internal fun anchorFrameHue(
    hue: Float,
    anchorHue: Float,
    toleranceDeg: Float = HUE_ANCHOR_TOLERANCE_DEG,
): Float {
    val delta = shortestHueDelta(hue, anchorHue)
    val clamped = delta.coerceIn(-toleranceDeg, toleranceDeg)
    if (clamped == delta) return hue
    return (anchorHue + clamped + 360f) % 360f
}

/**
 * 就地锚定一整幅帧的色相：逐个有色像素向 [anchorHue] 收拢，无彩与透明像素原样保留。
 *
 * 必须在模糊之前调用（见 [anchorFrameHue] 的容差说明）。锚定放在模糊之后没有意义：
 * 灰浊色是平均的产物，那时饱和度已经塌掉，改回色相也补不回彩度。
 *
 * 只改色相、不动明度，封面的明暗结构因此原样保留——画面读起来仍是这张封面的光影，
 * 只是收进了一个色系，而不是被重新着色成另一张图。
 */
internal fun anchorFrameHues(bitmap: Bitmap, anchorHue: Float) {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val hsl = FloatArray(3)
    var anchored = false
    for (index in pixels.indices) {
        val pixel = pixels[index]
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha == 0) continue
        ColorUtils.colorToHSL(pixel, hsl)
        if (hsl[1] < HUE_ANCHOR_MIN_SATURATION) continue
        val hue = anchorFrameHue(hsl[0], anchorHue)
        if (hue == hsl[0]) continue
        hsl[0] = hue
        hsl[1] = (hsl[1] * HUE_ANCHOR_SATURATION_GAIN).coerceAtMost(1f)
        pixels[index] = (alpha shl 24) or (ColorUtils.HSLToColor(hsl) and 0x00FFFFFF)
        anchored = true
    }
    // 一个像素都没被夹时不必回写：这一帧的色相本来就落在容差内
    if (!anchored) return
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
}
