package com.yichao.evilgodxu.screens.spectrum.component

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.yichao.evilgodxu.data.music.analysis.Spectrogram
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 位图像素总数上限：位图与像素缓冲在渲染期间同时驻留，超限等比缩小后再由绘制放大
private const val MAX_IMAGE_PIXELS = 2_400_000

// 单边像素上限：容器尺寸不可测（无约束布局）时的兜底，避免按无限尺寸分配
private const val MAX_IMAGE_SIDE = 4096

// 色板档数：强度到颜色的映射逐像素发生，展开为查找表避免重复插值
private const val LUT_SIZE = 256

// 强度色板自低到高：近黑蓝 → 蓝 → 紫 → 红 → 橙 → 暖白
private val SPECTRUM_STOPS = intArrayOf(
    0xFF05051E.toInt(),
    0xFF2B2B7A.toInt(),
    0xFF8E2C8E.toInt(),
    0xFFD6452E.toInt(),
    0xFFF2A03C.toInt(),
    0xFFFFF8DC.toInt(),
)

private val COLOR_LUT = IntArray(LUT_SIZE) { index ->
    val position = index.toFloat() / (LUT_SIZE - 1) * (SPECTRUM_STOPS.size - 1)
    val segment = position.toInt().coerceAtMost(SPECTRUM_STOPS.size - 2)
    blendColor(SPECTRUM_STOPS[segment], SPECTRUM_STOPS[segment + 1], position - segment)
}

// 频谱图：把时频矩阵渲染成位图后铺满容器。
// 时间轴朝向随朝向切换——横屏沿水平方向向右延伸，竖屏沿竖直方向向下延伸，
// 使图始终沿屏幕长边展开，两种朝向下都能占满可视区
@Composable
internal fun SpectrogramImage(
    spectrogram: Spectrogram,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // 位图按容器像素尺寸生成，绘制时一对一铺满；尺寸不可测时收敛到上限而非无限
        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceIn(1, MAX_IMAGE_SIDE)
        val heightPx = with(density) { maxHeight.toPx() }.toInt().coerceIn(1, MAX_IMAGE_SIDE)
        var image by remember { mutableStateOf<ImageBitmap?>(null) }
        // 重采样与像素填充是纯计算，放默认调度器执行，避免长音频分析后再次卡住主线程
        LaunchedEffect(spectrogram, vertical, widthPx, heightPx) {
            image = withContext(Dispatchers.Default) {
                renderSpectrogram(spectrogram, vertical, widthPx, heightPx)
            }
        }
        val rendered = image
        if (rendered == null) {
            // 位图尚未就绪：先铺底色，避免首帧闪出白块
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawImage(
                    image = rendered,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.Low,
                )
            }
        }
    }
}

// 把时频矩阵重采样为目标像素尺寸的位图。
// 时间轴与频率轴各自铺满一个方向：横屏时时间沿水平、频率沿竖直；竖屏时两轴交换。
// 目标像素稀于源数据时按数据块取均值，避免长音频压缩到窄位图时出现摩尔纹；
// 频率轴自容器底边起算，保证低频贴底、高频在顶
private fun renderSpectrogram(
    spectrogram: Spectrogram,
    vertical: Boolean,
    widthPx: Int,
    heightPx: Int,
): ImageBitmap {
    val scale = sqrt(MAX_IMAGE_PIXELS.toFloat() / (widthPx.toFloat() * heightPx)).coerceAtMost(1f)
    val bitmapWidth = (widthPx * scale).toInt().coerceAtLeast(1)
    val bitmapHeight = (heightPx * scale).toInt().coerceAtLeast(1)
    val values = spectrogram.values
    val columns = spectrogram.columns
    val rows = spectrogram.rows
    // 时间轴与频率轴各自占用的位图像素数：竖屏时时间轴沿纵向、频率轴沿横向
    val timePixels = if (vertical) bitmapHeight else bitmapWidth
    val frequencyPixels = if (vertical) bitmapWidth else bitmapHeight
    val pixels = IntArray(bitmapWidth * bitmapHeight)
    for (y in 0 until bitmapHeight) {
        for (x in 0 until bitmapWidth) {
            val timePixel = if (vertical) y else x
            // 频率像素自低频端起算：横屏时位图末行对应最低频
            val frequencyPixel = if (vertical) x else bitmapHeight - 1 - y
            val timeStart = timePixel * columns / timePixels
            val timeEnd = ((timePixel + 1) * columns / timePixels).coerceAtLeast(timeStart + 1)
            val rowStart = frequencyPixel * rows / frequencyPixels
            val rowEnd = ((frequencyPixel + 1) * rows / frequencyPixels).coerceAtLeast(rowStart + 1)
            var sum = 0f
            var count = 0
            for (column in timeStart until timeEnd) {
                val base = column * rows
                for (row in rowStart until rowEnd) {
                    sum += values[base + row]
                    count++
                }
            }
            pixels[y * bitmapWidth + x] = COLOR_LUT[colorIndex(if (count > 0) sum / count else 0f)]
        }
    }
    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
    return bitmap.asImageBitmap()
}

// 强度映射到色板档位：色板首档即最低强度，末档即满强度
private fun colorIndex(intensity: Float): Int =
    (intensity * (LUT_SIZE - 1)).toInt().coerceIn(0, LUT_SIZE - 1)

// 按比例混合两个不透明色，结果保持不透明
private fun blendColor(from: Int, to: Int, fraction: Float): Int {
    val red = channelOf(from, 16) + ((channelOf(to, 16) - channelOf(from, 16)) * fraction).toInt()
    val green = channelOf(from, 8) + ((channelOf(to, 8) - channelOf(from, 8)) * fraction).toInt()
    val blue = channelOf(from, 0) + ((channelOf(to, 0) - channelOf(from, 0)) * fraction).toInt()
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun channelOf(color: Int, shift: Int): Int = color shr shift and 0xFF
