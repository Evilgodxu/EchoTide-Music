package com.yichao.evilgodxu.ui.component

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.Modifier
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.theme.md_theme_dark_surface
import com.yichao.evilgodxu.theme.md_theme_dark_surfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 取色只需上下半区的平均色，64px 已足够且解码代价最低
private const val GRADIENT_SAMPLE_SIZE = 64

// 歌曲封面沉浸式背景：以封面图（与封面显示同源，见 rememberSystemThumbnail）的小尺寸取上下半区平均色组成向下渐变。
// 首页与 3D 封面轮播共用，随传入曲目实时变化；背景代表色经回调暴露供浮层容器复用。
@Composable
internal fun SongGradientBackground(
    track: MusicTrack?,
    modifier: Modifier = Modifier,
    darkenStatusBarArea: Boolean = true,
    onBackgroundColor: ((Color) -> Unit)? = null,
) {
    val defaultGradient = defaultSongGradient()
    var gradient by remember { mutableStateOf(defaultGradient) }
    // 与封面显示同一份系统略缩图：封面重写后系统图随媒体扫描重建，版本号变化即重新取色
    val thumbnail = rememberSystemThumbnail(track, GRADIENT_SAMPLE_SIZE)
    LaunchedEffect(thumbnail, darkenStatusBarArea) {
        val source = thumbnail?.asAndroidBitmap()
        val result = source?.let { songGradient(it, darkenStatusBarArea) }
        if (result != null) {
            gradient = result.first
            onBackgroundColor?.invoke(result.second)
        } else {
            gradient = defaultGradient
            onBackgroundColor?.invoke(md_theme_dark_surface)
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(gradient),
    )
}

// 首页默认背景固定深色，不随主题变化
private fun defaultSongGradient(): Brush =
    Brush.verticalGradient(
        listOf(
            md_theme_dark_surface,
            md_theme_dark_surfaceVariant,
        )
    )

// 取系统略缩图上下半区的平均色组成向下渐变并返回顶部主色；
// 竖屏时顶部压暗保证状态栏区域足够深，横屏系统栏隐藏时跳过该处理
private suspend fun songGradient(
    source: Bitmap,
    darkenStatusBarArea: Boolean,
): Pair<Brush, Color>? = withContext(Dispatchers.IO) {
    // 硬件位图不可直接 getPixel，复制为软件位图后再取色
    val bitmap = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
    } else source
    val topColor = bitmap.avgColor(topHalf = true).darkenIfNearWhite()
    Brush.verticalGradient(
        colorStops = arrayOf(
            0f to if (darkenStatusBarArea) topColor.darkenedForStatusBar() else topColor,
            0.12f to topColor,
            1f to bitmap.avgColor(topHalf = false).darkenIfNearWhite(),
        )
    ) to topColor
}

// 顶部压暗封面色：保留封面色调又足够深，保证状态栏白色图标始终可见
private fun Color.darkenedForStatusBar(): Color = lerp(this, md_theme_dark_surface, 0.3f)

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
