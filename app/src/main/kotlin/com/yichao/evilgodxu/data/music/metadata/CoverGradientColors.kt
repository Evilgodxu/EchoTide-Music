package com.yichao.evilgodxu.data.music.metadata

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 下边缘取样带占封面高度的比例：取一条窄带而非单行像素可避开封面底部的描边与压缩噪点，
// 又足够贴近底边，使背景顶部与封面下边缘同色相接
private const val COVER_EDGE_BAND_HEIGHT_RATIO = 0.1f

// 封面取色：first 为封面下边缘色，作沉浸背景顶部与封面下边缘衔接的锚色；
// second 为封面下半区平均色，作背景底部深色端，保证底部前景文字可读。
// 显示端（SongImmersiveBackground）与切歌时的后台持久化共用本入口，使冷启动恢复色与实时取色同源。
internal suspend fun extractCoverGradient(source: Bitmap): Pair<Color, Color>? = withContext(Dispatchers.IO) {
    // 硬件位图不可直接 getPixel，复制为软件位图后再取色
    val bitmap = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
    } else source
    val edgeStartY = (bitmap.height * (1f - COVER_EDGE_BAND_HEIGHT_RATIO)).toInt()
    bitmap.avgColor(edgeStartY, bitmap.height).darkenIfNearWhite() to
        bitmap.avgColor(bitmap.height / 2, bitmap.height).darkenIfNearWhite()
}

// 与白色前景（按钮标题/歌词）亮度相近时轻微压暗，保证文字可读
private fun Color.darkenIfNearWhite(): Color {
    val luminance = 0.299f * red + 0.587f * green + 0.114f * blue
    return if (luminance > 0.8f) lerp(this, Color.Black, 0.2f) else this
}

private fun Bitmap.avgColor(startY: Int, endY: Int): Color {
    val from = startY.coerceIn(0, height)
    val to = endY.coerceIn(from, height)
    if (to <= from) return Color.Black
    var r = 0L
    var g = 0L
    var b = 0L
    var count = 0L
    for (y in from until to) {
        for (x in 0 until width) {
            val c = getPixel(x, y)
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            count++
        }
    }
    if (count == 0L) return Color.Black
    return Color(
        red = (r / count).toFloat() / 255f,
        green = (g / count).toFloat() / 255f,
        blue = (b / count).toFloat() / 255f,
    )
}
