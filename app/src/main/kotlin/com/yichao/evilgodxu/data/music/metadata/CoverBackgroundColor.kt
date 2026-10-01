package com.yichao.evilgodxu.data.music.metadata

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 色桶量化位数：每通道保留高 N 位作为桶索引（共 2^(3N) 个桶）。
// 位数越少，相近色越能并入同一桶，主色区域不易被压缩噪点与渐变切散；3 位在合并度与分辨率间取平衡
private const val COLOR_QUANTIZE_BITS = 3

// 脏色的明度区间：接近纯白（云、高光）与纯黑（阴影、描边）不承载色相倾向，不作主色调
private const val DIRTY_LUMA_MAX = 0.9f
private const val DIRTY_LUMA_MIN = 0.08f

// 脏色的彩度下限：三通道极差过小即灰调（混色、蒙尘），取它会让背景发脏
private const val DIRTY_CHROMA_MIN = 0.12f

// 参与取色的最低不透明度：透明区域（抠图留白、圆形封面外）不代表封面主色调
private const val OPAQUE_ALPHA_MIN = 128

// 封面取色：对全图做一次量化直方图，取覆盖面积占比最大且不脏的颜色作背景主色。
// 占比小的碎色（如画面角落的物体色）与灰调混色不会胜出，背景整体因此贴合封面主色调而不被局部脏色带偏。
// 取色结果即背景主色，不再做任何压暗：显示端只在其上叠加均匀的轻微压暗（见 SongImmersiveBackground）。
// 显示端（SongImmersiveBackground）与切歌时的后台持久化共用本入口，使冷启动恢复色与实时取色同源。
internal suspend fun extractCoverBackgroundColor(source: Bitmap): Color? = withContext(Dispatchers.IO) {
    // 硬件位图不可直接 getPixels，复制为软件位图后再取色
    val bitmap = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
    } else source
    bitmap.dominantColor()
}

// 占比最大且不脏的颜色：逐桶累计像素数后，在非脏桶中取像素最多者；
// 全图皆脏（如纯黑白封面）时退回像素最多的桶，保证仍有可用的代表色
private fun Bitmap.dominantColor(): Color? {
    if (width <= 0 || height <= 0) return null
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)

    val shift = 8 - COLOR_QUANTIZE_BITS
    val channelBuckets = 1 shl COLOR_QUANTIZE_BITS
    val bucketCount = channelBuckets * channelBuckets * channelBuckets
    val counts = IntArray(bucketCount)
    val sumR = LongArray(bucketCount)
    val sumG = LongArray(bucketCount)
    val sumB = LongArray(bucketCount)

    for (pixel in pixels) {
        if (((pixel ushr 24) and 0xFF) < OPAQUE_ALPHA_MIN) continue
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        val index = ((r shr shift) * channelBuckets + (g shr shift)) * channelBuckets + (b shr shift)
        counts[index]++
        sumR[index] += r
        sumG[index] += g
        sumB[index] += b
    }

    var mostIndex = -1
    var mostCount = 0
    var cleanIndex = -1
    var cleanCount = 0
    for (index in 0 until bucketCount) {
        val count = counts[index]
        if (count == 0) continue
        if (count > mostCount) {
            mostCount = count
            mostIndex = index
        }
        if (count <= cleanCount) continue
        if (isDirtyColor((sumR[index] / count).toInt(), (sumG[index] / count).toInt(), (sumB[index] / count).toInt())) {
            continue
        }
        cleanCount = count
        cleanIndex = index
    }

    val chosen = if (cleanIndex >= 0) cleanIndex else mostIndex
    if (chosen < 0) return null
    val count = counts[chosen]
    return Color(
        red = (sumR[chosen] / count).toFloat() / 255f,
        green = (sumG[chosen] / count).toFloat() / 255f,
        blue = (sumB[chosen] / count).toFloat() / 255f,
    )
}

// 脏色：明度落在纯白/纯黑两端，或彩度过低呈灰调
private fun isDirtyColor(r: Int, g: Int, b: Int): Boolean {
    val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    if (luma > DIRTY_LUMA_MAX || luma < DIRTY_LUMA_MIN) return true
    return (maxOf(r, g, b) - minOf(r, g, b)) / 255f < DIRTY_CHROMA_MIN
}
