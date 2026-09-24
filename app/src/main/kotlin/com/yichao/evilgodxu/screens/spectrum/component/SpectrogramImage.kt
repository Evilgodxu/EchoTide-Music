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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 位图像素总数上限：位图与像素缓冲在渲染期间同时驻留，超限等比缩小后再由绘制放大
private const val MAX_IMAGE_PIXELS = 2_400_000

// 单边像素上限：容器尺寸不可测（无约束布局）时的兜底，避免按无限尺寸分配
private const val MAX_IMAGE_SIDE = 4096

// 频谱图：把时频矩阵渲染成位图后铺满容器。
// 轴朝向固定——时间沿水平方向自左向右、频率沿竖直方向自下而上；
// 竖屏下容器本身窄高，图随之被纵向拉伸而非旋转，读图习惯在两种朝向下保持一致。
// 纵向按传入的轴刻度取行，与左侧频率刻度共用同一套坐标（见 FrequencyAxisScale）
@Composable
internal fun SpectrogramImage(
    spectrogram: Spectrogram,
    scale: FrequencyAxisScale,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // 位图按容器像素尺寸生成，绘制时一对一铺满；尺寸不可测时收敛到上限而非无限
        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceIn(1, MAX_IMAGE_SIDE)
        val heightPx = with(density) { maxHeight.toPx() }.toInt().coerceIn(1, MAX_IMAGE_SIDE)
        var image by remember { mutableStateOf<ImageBitmap?>(null) }
        // 重采样与像素填充是纯计算，放默认调度器执行，避免长音频分析后再次卡住主线程
        LaunchedEffect(spectrogram, scale, widthPx, heightPx) {
            image = withContext(Dispatchers.Default) {
                renderSpectrogram(spectrogram, scale, widthPx, heightPx)
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

// 把时频矩阵重采样为目标像素尺寸的位图：横向为时间、纵向为频率，低频贴底、高频在顶。
// 纵向按轴刻度取行：源矩阵按频率等距分布，对数轴上高层行覆盖更宽的频带，故按该行覆盖的矩阵区间取均值，
// 使图内每一行都与左侧同一高度的刻度读数对应。
// 目标像素稀于源数据时同样按数据块取均值，避免长音频压缩到窄位图时出现摩尔纹
private fun renderSpectrogram(
    spectrogram: Spectrogram,
    scale: FrequencyAxisScale,
    widthPx: Int,
    heightPx: Int,
): ImageBitmap {
    val pixelScale = sqrt(MAX_IMAGE_PIXELS.toFloat() / (widthPx.toFloat() * heightPx)).coerceAtMost(1f)
    val bitmapWidth = (widthPx * pixelScale).toInt().coerceAtLeast(1)
    val bitmapHeight = (heightPx * pixelScale).toInt().coerceAtLeast(1)
    val values = spectrogram.values
    val columns = spectrogram.columns
    val rows = spectrogram.rows
    val nyquistHz = spectrogram.sampleRate / 2f
    val pixels = IntArray(bitmapWidth * bitmapHeight)
    for (y in 0 until bitmapHeight) {
        // 频率自下而上：位图末行对应最低频，该行覆盖的频率区间由轴刻度反算，
        // 再按源矩阵的频率间隔换算成行号区间（含首尾整行，避免行间出现空档）
        val fromBottom = bitmapHeight - 1 - y
        val lowHz = scale.hertzAt(fromBottom.toFloat() / bitmapHeight)
        val highHz = scale.hertzAt((fromBottom + 1).toFloat() / bitmapHeight)
        val rowStart = floor(lowHz / nyquistHz * rows).toInt().coerceIn(0, rows - 1)
        val rowEnd = ceil(highHz / nyquistHz * rows).toInt().coerceIn(rowStart + 1, rows)
        for (x in 0 until bitmapWidth) {
            val columnStart = x * columns / bitmapWidth
            val columnEnd = ((x + 1) * columns / bitmapWidth).coerceAtLeast(columnStart + 1)
            var sum = 0f
            var count = 0
            for (column in columnStart until columnEnd) {
                val base = column * rows
                for (row in rowStart until rowEnd) {
                    sum += values[base + row]
                    count++
                }
            }
            pixels[y * bitmapWidth + x] = spectrumColor(if (count > 0) sum / count else 0f)
        }
    }
    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
    return bitmap.asImageBitmap()
}
