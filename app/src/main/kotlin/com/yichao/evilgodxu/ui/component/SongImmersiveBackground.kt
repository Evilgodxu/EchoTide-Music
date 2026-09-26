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

// 封面取样尺寸：取色只需封面下边缘带与下半区的平均色，背景渲染也在小画布上完成，64px 已足够且解码代价最低
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

// 下边缘衔接层淡出带长度占封面高度的比例：取与封面自身下缘渐隐带（HomeAlbumArt 的 BOTTOM_FADE_FRACTION）同值，
// 使封面淡出与背景淡入在封面底边两侧等长对称，接缝处颜色连续
private const val COVER_EDGE_BLEND_FADE_RATIO = 0.3f

// 衔接层淡出过程的采样透明度（由 1 递减至 0）：与封面下缘渐隐蒙层同一条平滑曲线，
// 单段线性渐隐会在折点处留下可见的色阶带
private val COVER_EDGE_BLEND_ALPHAS = listOf(0.95f, 0.79f, 0.55f, 0.21f)

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
// 渐变与衍生背景顶部都以封面下边缘色为锚（见 extractCoverGradient），
// 传入 [coverBottomFraction] 后还会在封面底边处铺一层同色衔接层，使封面下边缘与背景同色相接。
// 默认只渲染一帧静态背景；设置页开启「背景流动」后按固定默认值缓慢推进时间轴。
// 首页与 3D 封面轮播共用，随传入曲目实时变化；背景代表色经回调暴露供浮层容器复用。
@Composable
internal fun SongImmersiveBackground(
    track: MusicTrack?,
    modifier: Modifier = Modifier,
    restoredColors: Pair<Color, Color>? = null,
    // 顶部沉浸封面下边缘在视口中的位置（占视口高度比例）：给定后背景在封面底边处对齐同色衔接层；
    // 0 表示当前没有顶部沉浸封面（横屏轮播等），背景不做衔接处理
    coverBottomFraction: Float = 0f,
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
    // 封面下边缘衔接层：把封面底边往下的一段固定为封面下边缘色，再按与封面下缘渐隐带等长的距离淡出到衍生背景。
    // 衍生背景的色块不会直接贴在封面下边缘，接缝两侧颜色一致，首帧（衍生背景尚未出图）同样成立
    val edgeBlendBrush = effective?.first?.let { coverEdgeBlendBrush(it, coverBottomFraction) }

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
        // 衔接层压在衍生背景之上、压暗层之下：压暗层沿纵向连续，不会在接缝处留下色阶
        edgeBlendBrush?.let { brush ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(brush),
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

// 封面未就绪（首帧）时的兜底背景：由封面下边缘色起、封面下半区平均色收（见 extractCoverGradient），
// 与衔接层同锚色，故首帧底色与封面下边缘连续，不会出现与封面下边缘不协调的其它色块
private fun fallbackGradient(edgeColor: Color, deepColor: Color): Brush =
    Brush.verticalGradient(listOf(edgeColor, deepColor))

/**
 * 封面下边缘衔接层：[coverBottomFraction] 为封面下边缘在视口中的纵向位置，此后一段固定为封面下边缘色 [edge]，
 * 再以 [COVER_EDGE_BLEND_FADE_RATIO] 确定的长度淡出，使封面下边缘与背景在接缝处同色相接。
 * 封面几乎铺满视口时没有可衔接的背景区，返回 null 表示无需衔接层。
 */
private fun coverEdgeBlendBrush(edge: Color, coverBottomFraction: Float): Brush? {
    val start = coverBottomFraction.coerceIn(0f, 1f)
    if (start <= 0f || start >= 1f) return null
    val end = (start * (1f + COVER_EDGE_BLEND_FADE_RATIO)).coerceAtMost(1f)
    val span = end - start
    if (span <= 0f) return null
    val step = span / (COVER_EDGE_BLEND_ALPHAS.size + 1)
    val stops = ArrayList<Pair<Float, Color>>(COVER_EDGE_BLEND_ALPHAS.size + 3)
    stops += 0f to edge
    stops += start to edge
    COVER_EDGE_BLEND_ALPHAS.forEachIndexed { index, alpha ->
        stops += (start + step * (index + 1)) to edge.copy(alpha = alpha)
    }
    // 末档用同色全透明而非 Color.Transparent：避免 RGB 在淡出末段向黑色插值而渗出灰调
    stops += end to edge.copy(alpha = 0f)
    return Brush.verticalGradient(colorStops = stops.toTypedArray())
}

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