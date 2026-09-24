package com.yichao.evilgodxu.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.data.music.metadata.extractCoverGradient
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.settings.backgroundFlowEnabledFlow
import com.yichao.evilgodxu.theme.md_theme_dark_surface
import com.yichao.evilgodxu.theme.md_theme_dark_surfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

// 封面取样尺寸：取色只需上下半区的平均色，背景渲染也在小画布上完成，64px 已足够且解码代价最低
private const val COVER_BACKGROUND_SAMPLE_SIZE = 64

// 背景帧降采样倍数：帧位图边长为视口的 1/16（像素量约 1/256），叠加、模糊与放大都以小图为准
private const val COVER_BACKGROUND_DOWNSAMPLE = 16

// 封面放大系数：放大到画布较长边的 1.3 倍后居中，错位叠画仍能盖满画布不露空边
private const val COVER_BACKGROUND_OVERSCAN = 1.3f

// 叠画饱和度：封面本身偏灰时也能得到有色彩的背景
private const val COVER_BACKGROUND_SATURATION = 2.5f

// 模糊半径：按 1/16 小图尺度取固定值，叠加后的封面柔化为连贯色块
private const val COVER_BACKGROUND_BLUR_RADIUS = 15

// 流动帧间隔：与显示帧率对齐约 30fps，低于此间隔的重绘并入下一帧
private const val COVER_BACKGROUND_FLOW_FRAME_INTERVAL_MS = 32L

// 背景流动的三层固定默认值：旋转周期与方向、错位量（占画布宽高的比例）。
// 三层周期互质且方向相反，叠画后的色块缓慢漂移而不出现明显循环
private val COVER_BACKGROUND_LAYERS = listOf(
    CoverBackgroundLayer(periodMs = 120_000L, clockwise = false, offsetX = 0f, offsetY = 0f, rotateAboutCenter = false),
    CoverBackgroundLayer(periodMs = 90_000L, clockwise = true, offsetX = -0.95f, offsetY = -0.7f, rotateAboutCenter = false),
    CoverBackgroundLayer(periodMs = 70_000L, clockwise = true, offsetX = -0.5f, offsetY = 0.7f, rotateAboutCenter = true),
)

private data class CoverBackgroundLayer(
    val periodMs: Long,
    val clockwise: Boolean,
    val offsetX: Float,
    val offsetY: Float,
    // 错位后再绕画布中心旋转一次，使该层的位移轨迹更接近漂浮
    val rotateAboutCenter: Boolean,
)

// 歌曲沉浸式背景：由封面缩略图渲染柔和的叠画背景（见 renderCoverBackgroundFrame），
// 封面未就绪时回落取色渐变，冷启动可先用 [restoredColors]（上次持久化的取色结果）渲染，避免首帧闪默认色。
// 默认只渲染一帧静态背景；设置页开启「背景流动」后按固定默认值缓慢推进时间轴。
// 首页与 3D 封面轮播共用，随传入曲目实时变化；背景代表色经回调暴露供浮层容器复用。
@Composable
internal fun SongImmersiveBackground(
    track: MusicTrack?,
    modifier: Modifier = Modifier,
    restoredColors: Pair<Color, Color>? = null,
    onBackgroundColor: ((Color) -> Unit)? = null,
    onExtractedColors: ((Color, Color) -> Unit)? = null,
) {
    val context = LocalContext.current
    // 与封面显示同一份系统略缩图：封面重写后系统图随媒体扫描重建，版本号变化即重新取色与重绘
    val thumbnail = rememberSystemThumbnail(track, COVER_BACKGROUND_SAMPLE_SIZE)
    var extracted by remember { mutableStateOf<Pair<Color, Color>?>(null) }
    LaunchedEffect(thumbnail) {
        val colors = thumbnail?.asAndroidBitmap()?.let { extractCoverGradient(it) }
        extracted = colors
        if (colors != null) onExtractedColors?.invoke(colors.first, colors.second)
    }
    val effective = extracted ?: restoredColors
    val background = effective?.first ?: md_theme_dark_surface
    LaunchedEffect(background) { onBackgroundColor?.invoke(background) }

    // 流动时间轴：仅在开关打开时推进，关闭时归零即回到静态首帧
    val flowEnabled by context.backgroundFlowEnabledFlow().collectAsStateWithLifecycle(initialValue = false)
    var flowTimeMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(flowEnabled, thumbnail) {
        if (!flowEnabled) {
            flowTimeMs = 0L
            return@LaunchedEffect
        }
        var lastPublishedNanos = 0L
        while (true) {
            val nowNanos = withFrameNanos { it }
            if (lastPublishedNanos == 0L ||
                nowNanos - lastPublishedNanos >= COVER_BACKGROUND_FLOW_FRAME_INTERVAL_MS * 1_000_000L
            ) {
                flowTimeMs = nowNanos / 1_000_000L
                lastPublishedNanos = nowNanos
            }
        }
    }

    // 背景帧：只在封面、视口尺寸或流动时间推进时重算
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val washPrimary = remember(background) { lerp(background, Color.Black, 0.28f).copy(alpha = 0.34f) }
    val washSecondary = remember { Color.Black.copy(alpha = 0.18f) }
    LaunchedEffect(thumbnail, viewportSize, flowTimeMs, washPrimary, washSecondary) {
        val cover = thumbnail?.asAndroidBitmap()
        frame = if (cover == null || viewportSize.width <= 0 || viewportSize.height <= 0) {
            null
        } else {
            withContext(Dispatchers.Default) {
                renderCoverBackgroundFrame(
                    cover = cover,
                    viewportWidth = viewportSize.width,
                    viewportHeight = viewportSize.height,
                    timeMs = flowTimeMs,
                    washPrimaryArgb = washPrimary.toArgb(),
                    washSecondaryArgb = washSecondary.toArgb(),
                ).asImageBitmap()
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            .background(effective?.let { fallbackGradient(it.first, it.second) } ?: defaultBackgroundGradient()),
    ) {
        frame?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 柔和压暗层：封面衍生背景可能整体偏亮，压暗上下边缘保证状态栏与前景文字可读
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.18f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.30f),
                        )
                    )
                ),
        )
    }
}

// 首页默认背景固定深色，不随主题变化
private fun defaultBackgroundGradient(): Brush =
    Brush.verticalGradient(
        listOf(
            md_theme_dark_surface,
            md_theme_dark_surfaceVariant,
        )
    )

// 封面未就绪时的兜底背景：取系统略缩图上下半区平均色（见 extractCoverGradient）作向下渐变
private fun fallbackGradient(topColor: Color, bottomColor: Color): Brush =
    Brush.verticalGradient(listOf(topColor, bottomColor))

/**
 * 渲染一帧封面衍生背景：在 1/16 视口尺寸的小画布上错位叠画三份高饱和封面，叠加色调蒙层后整体模糊，
 * 由显示端放大铺满。像素量约为整屏的 1/256，叠加与模糊的代价随之降到可忽略。
 * [timeMs] 为 0 时即静态首帧；流动开启后由调用方按帧推进，三份封面随各自周期缓慢旋转。
 */
internal fun renderCoverBackgroundFrame(
    cover: Bitmap,
    viewportWidth: Int,
    viewportHeight: Int,
    timeMs: Long,
    washPrimaryArgb: Int,
    washSecondaryArgb: Int,
): Bitmap {
    val width = (viewportWidth / COVER_BACKGROUND_DOWNSAMPLE).coerceAtLeast(1)
    val height = (viewportHeight / COVER_BACKGROUND_DOWNSAMPLE).coerceAtLeast(1)
    val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(frame)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(COVER_BACKGROUND_SATURATION) })
    }
    val side = max(width, height) * COVER_BACKGROUND_OVERSCAN
    val scale = side / max(cover.width, cover.height)
    val matrix = Matrix()
    for (layer in COVER_BACKGROUND_LAYERS) {
        val rotation = (timeMs % layer.periodMs).toFloat() / layer.periodMs * 360f *
            if (layer.clockwise) 1f else -1f
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postRotate(rotation, side / 2f, side / 2f)
        matrix.postTranslate(
            -(side - width) / 2f + width * layer.offsetX,
            -(side - height) / 2f + height * layer.offsetY,
        )
        if (layer.rotateAboutCenter) matrix.postRotate(rotation, width / 2f, height / 2f)
        canvas.drawBitmap(cover, matrix, paint)
    }
    canvas.drawColor(washPrimaryArgb)
    canvas.drawColor(washSecondaryArgb)
    return blurCoverBackgroundFrame(frame, COVER_BACKGROUND_BLUR_RADIUS)
}

// 两趟盒式模糊：输入是 1/16 视口的小图，纯 CPU 逐像素处理即可，无需引入渲染管线
private fun blurCoverBackgroundFrame(source: Bitmap, radius: Int): Bitmap {
    val width = source.width
    val height = source.height
    if (width <= 1 || height <= 1) return source
    val r = radius.coerceIn(1, minOf(width, height) / 2)
    val window = r * 2 + 1
    val pixels = IntArray(width * height)
    source.getPixels(pixels, 0, width, 0, 0, width, height)
    val rowSums = IntArray(width * height)

    // 横向
    for (y in 0 until height) {
        val row = y * width
        var a = 0
        var red = 0
        var green = 0
        var blue = 0
        for (k in -r..r) {
            val p = pixels[row + k.coerceIn(0, width - 1)]
            a += (p ushr 24) and 0xff
            red += (p ushr 16) and 0xff
            green += (p ushr 8) and 0xff
            blue += p and 0xff
        }
        for (x in 0 until width) {
            rowSums[row + x] = ((a / window) shl 24) or ((red / window) shl 16) or
                ((green / window) shl 8) or (blue / window)
            val outgoing = pixels[row + (x - r).coerceIn(0, width - 1)]
            val incoming = pixels[row + (x + r + 1).coerceIn(0, width - 1)]
            a += ((incoming ushr 24) and 0xff) - ((outgoing ushr 24) and 0xff)
            red += ((incoming ushr 16) and 0xff) - ((outgoing ushr 16) and 0xff)
            green += ((incoming ushr 8) and 0xff) - ((outgoing ushr 8) and 0xff)
            blue += (incoming and 0xff) - (outgoing and 0xff)
        }
    }

    // 纵向
    for (x in 0 until width) {
        var a = 0
        var red = 0
        var green = 0
        var blue = 0
        for (k in -r..r) {
            val p = rowSums[k.coerceIn(0, height - 1) * width + x]
            a += (p ushr 24) and 0xff
            red += (p ushr 16) and 0xff
            green += (p ushr 8) and 0xff
            blue += p and 0xff
        }
        for (y in 0 until height) {
            pixels[y * width + x] = ((a / window) shl 24) or ((red / window) shl 16) or
                ((green / window) shl 8) or (blue / window)
            val outgoing = rowSums[(y - r).coerceIn(0, height - 1) * width + x]
            val incoming = rowSums[(y + r + 1).coerceIn(0, height - 1) * width + x]
            a += ((incoming ushr 24) and 0xff) - ((outgoing ushr 24) and 0xff)
            red += ((incoming ushr 16) and 0xff) - ((outgoing ushr 16) and 0xff)
            green += ((incoming ushr 8) and 0xff) - ((outgoing ushr 8) and 0xff)
            blue += (incoming and 0xff) - (outgoing and 0xff)
        }
    }

    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    result.setPixels(pixels, 0, width, 0, 0, width, height)
    // 待模糊的中间帧只在本函数内使用：流动期间每帧都会新建，及时回收避免遗留图片垃圾
    source.recycle()
    return result
}