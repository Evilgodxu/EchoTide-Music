package com.yichao.evilgodxu.data.music.metadata

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 封面取色：取上下半区的平均色作为沉浸背景兜底渐变的两端。
// 显示端（SongImmersiveBackground）与切歌时的后台持久化共用本入口，使冷启动恢复色与实时取色同源。
internal suspend fun extractCoverGradient(source: Bitmap): Pair<Color, Color>? = withContext(Dispatchers.IO) {
    // 硬件位图不可直接 getPixel，复制为软件位图后再取色
    val bitmap = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
    } else source
    bitmap.avgColor(topHalf = true).darkenIfNearWhite() to bitmap.avgColor(topHalf = false).darkenIfNearWhite()
}

// 与白色前景（按钮标题/歌词）亮度相近时轻微压暗，保证文字可读
private fun Color.darkenIfNearWhite(): Color {
    val luminance = 0.299f * red + 0.587f * green + 0.114f * blue
    return if (luminance > 0.8f) lerp(this, Color.Black, 0.2f) else this
}

private fun Bitmap.avgColor(topHalf: Boolean): Color {
    val startY = if (topHalf) 0 else height / 2
    val endY = if (topHalf) height / 2 else height
    var r = 0L
    var g = 0L
    var b = 0L
    var count = 0L
    for (y in startY until endY) {
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