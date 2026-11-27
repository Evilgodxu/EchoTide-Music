package com.yichao.evilgodxu.ui.component

import android.graphics.Bitmap
import androidx.core.graphics.ColorUtils
import com.yichao.evilgodxu.data.music.metadata.shortestHueDelta

// 色相容差 30°：两像素差 60°，留约 87% 彩度
internal const val HUE_ANCHOR_TOLERANCE_DEG = 30f

// 低于此饱和度视为无彩，不锚定
internal const val HUE_ANCHOR_MIN_SATURATION = 0.08f

// 被夹取像素彩度回补 1.08
private const val HUE_ANCHOR_SATURATION_GAIN = 1.08f

/**
 * 色相按最短方向夹到容差内，保留原有色差
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
 * 模糊前逐像素锚定色相，保留明暗结构
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
    // 无像素被夹时不回写
    if (!anchored) return
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
}
