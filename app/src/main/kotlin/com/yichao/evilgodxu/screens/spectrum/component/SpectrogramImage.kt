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
import androidx.core.graphics.createBitmap
import com.yichao.evilgodxu.data.music.analysis.Spectrogram
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

// 像素上限 240 万，超限等比缩小
private const val MAX_IMAGE_PIXELS = 2_400_000

// 单边上限 4096，兜住无约束布局
private const val MAX_IMAGE_SIDE = 4096

// 并行度：核数减一，最多 8
private val RENDER_PARTS = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 8)

// 时频矩阵渲染成位图；时间向右、频率向上
@Composable
internal fun SpectrogramImage(
    spectrogram: Spectrogram,
    scale: FrequencyAxisScale,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // 按容器像素生成，不可测时收敛到上限
        val widthPx = with(density) { maxWidth.toPx() }.toInt().coerceIn(1, MAX_IMAGE_SIDE)
        val heightPx = with(density) { maxHeight.toPx() }.toInt().coerceIn(1, MAX_IMAGE_SIDE)
        var image by remember { mutableStateOf<ImageBitmap?>(null) }
        // 纯计算放默认调度器
        LaunchedEffect(spectrogram, scale, widthPx, heightPx) {
            image = withContext(Dispatchers.Default) {
                renderSpectrogramBitmap(spectrogram, scale, widthPx, heightPx).asImageBitmap()
            }
        }
        val rendered = image
        if (rendered == null) {
            // 未就绪先铺底色，避免闪白
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

// 重采样按轴刻度取行均值；屏幕与导出共用
internal suspend fun renderSpectrogramBitmap(
    spectrogram: Spectrogram,
    scale: FrequencyAxisScale,
    widthPx: Int,
    heightPx: Int,
): Bitmap {
    val pixelScale = sqrt(MAX_IMAGE_PIXELS.toFloat() / (widthPx.toFloat() * heightPx)).coerceAtMost(1f)
    val bitmapWidth = (widthPx * pixelScale).toInt().coerceAtLeast(1)
    val bitmapHeight = (heightPx * pixelScale).toInt().coerceAtLeast(1)
    val values = spectrogram.values
    val columns = spectrogram.columns
    val rows = spectrogram.rows
    val nyquistHz = spectrogram.sampleRate / 2f
    val pixels = IntArray(bitmapWidth * bitmapHeight)
    // 列区间只与 x 有关，先算好
    val columnFrom = IntArray(bitmapWidth)
    val columnTo = IntArray(bitmapWidth)
    for (x in 0 until bitmapWidth) {
        val start = x * columns / bitmapWidth
        columnFrom[x] = start
        columnTo[x] = ((x + 1) * columns / bitmapWidth).coerceAtLeast(start + 1)
    }
    // 行带并行，各带写不相交区间
    coroutineScope {
        val parts = RENDER_PARTS.coerceIn(1, bitmapHeight)
        (0 until parts).map { part ->
            async {
                val from = bitmapHeight * part / parts
                val to = bitmapHeight * (part + 1) / parts
                for (y in from until to) {
                    // 末行为最低频，按轴刻度换算行区间
                    val fromBottom = bitmapHeight - 1 - y
                    val lowHz = scale.hertzAt(fromBottom.toFloat() / bitmapHeight)
                    val highHz = scale.hertzAt((fromBottom + 1).toFloat() / bitmapHeight)
                    val rowStart = floor(lowHz / nyquistHz * rows).toInt().coerceIn(0, rows - 1)
                    val rowEnd = ceil(highHz / nyquistHz * rows).toInt().coerceIn(rowStart + 1, rows)
                    for (x in 0 until bitmapWidth) {
                        var sum = 0f
                        var count = 0
                        for (column in columnFrom[x] until columnTo[x]) {
                            val base = column * rows
                            for (row in rowStart until rowEnd) {
                                sum += values[base + row]
                                count++
                            }
                        }
                        pixels[y * bitmapWidth + x] =
                            spectrumColor(if (count > 0) sum / count else 0f)
                    }
                }
            }
        }.awaitAll()
    }
    val bitmap = createBitmap(bitmapWidth, bitmapHeight)
    bitmap.setPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
    return bitmap
}
