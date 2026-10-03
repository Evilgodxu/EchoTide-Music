package com.yichao.evilgodxu.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withMatrix
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.LocalMusicPanelStateHolder
import com.yichao.evilgodxu.data.music.metadata.CoverBackgroundColors
import com.yichao.evilgodxu.data.music.metadata.CoverColorCache
import com.yichao.evilgodxu.data.music.metadata.extractCoverBackgroundColors
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.settings.backgroundFlowEnabledFlow
import com.yichao.evilgodxu.theme.md_theme_dark_surface
import com.yichao.evilgodxu.theme.md_theme_dark_surfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

// 封面取样尺寸：取色只需封面边缘环的平均色，64px 已足够且解码代价最低
private const val COVER_BACKGROUND_SAMPLE_SIZE = 64

// 背景帧降采样倍数：帧位图边长为视口的 1/16（像素量约 1/256），叠画、模糊与放大都以小图为准
private const val COVER_BACKGROUND_DOWNSAMPLE = 16

// 色块边长系数：色块取画布较长边的 1.3 倍，错位叠画与旋转后仍能盖满画布不露空边
private const val COVER_BACKGROUND_OVERSCAN = 1.3f

// 模糊半径：按 1/16 小图尺度取固定值，错位叠画的色块柔化为连贯色域，色块边界不显形
private const val COVER_BACKGROUND_BLUR_RADIUS = 15

// 流动帧间隔：与显示帧率对齐约 30fps，低于此间隔的重绘并入下一帧
private const val COVER_BACKGROUND_FLOW_FRAME_INTERVAL_MS = 32L

// 背景整体压暗强度：静态渐变与流动帧统一轻微压暗，给前景文字留出对比。
// 单值均匀压暗，不随位置变化
private const val COVER_BACKGROUND_DIM_ALPHA = 0.15f

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

// 歌曲沉浸式背景：整屏统一渲染，不随顶置封面位置做局部处理，左右切页时背景始终连续。
// 底色取自封面边缘的三段相近色（见 extractCoverBackgroundColors）：关闭「背景流动」时只铺一条
// 由三段色构成的自上而下渐变，开启后在其上叠一层缓慢漂移的色块帧（见 renderFlowBackgroundFrame）；
// 关掉流动即撤下色块帧回到渐变，静止背景与流动背景始终是同一份色调。
// 三段取色未就绪时先回落 [restoredColor]（上次持久化的取色结果）整幅铺色，避免冷启动首帧闪默认色。
// 首页与 3D 封面轮播共用，随传入曲目实时变化；背景代表色经回调暴露供浮层容器复用。
@Composable
internal fun SongImmersiveBackground(
    track: MusicTrack?,
    modifier: Modifier = Modifier,
    restoredColor: Color? = null,
    onBackgroundColor: ((Color) -> Unit)? = null,
    onExtractedColor: ((Color) -> Unit)? = null,
) {
    val context = LocalContext.current
    val audioUri = track?.audioUri
    val coverRevision = LocalMusicPanelStateHolder.current.state.coverRevision
    // 预取已算好的取色：切歌首帧就能取到新曲目的色调，不必等下面那张 64px 略缩图解码
    val cachedColors = remember(audioUri, coverRevision) { audioUri?.let { CoverColorCache.get(it) } }
    // 与封面显示同一份系统略缩图：封面重写后系统图随媒体扫描重建，版本号变化即重新取色
    val thumbnail = rememberSystemThumbnail(track, COVER_BACKGROUND_SAMPLE_SIZE)
    var extracted by remember(audioUri, coverRevision) { mutableStateOf(cachedColors) }
    LaunchedEffect(audioUri, coverRevision, thumbnail) {
        // 取色优先用预取结果：它与高清封面同源，且不依赖略缩图是否已解码
        val colors = CoverColorCache.get(audioUri.orEmpty())
            ?: thumbnail?.asAndroidBitmap()?.let { extractCoverBackgroundColors(it) }
        extracted = colors
        if (colors != null) onExtractedColor?.invoke(colors.representative)
    }
    val colors = extracted
    val background = colors?.representative ?: restoredColor ?: md_theme_dark_surface
    LaunchedEffect(background) { onBackgroundColor?.invoke(background) }

    // 流动时间轴：仅在开关打开时推进，关闭后停在原地不再推进
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

    // 流动帧：只在开启流动且三段取色就绪时渲染，随流动时间推进重算；
    // 关闭流动即置空，背景不再有任何流动帧参与渲染，只剩下方静态渐变
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(colors, flowEnabled, viewportSize, flowTimeMs) {
        frame = if (!flowEnabled || colors == null || viewportSize.width <= 0 || viewportSize.height <= 0) {
            null
        } else {
            withContext(Dispatchers.Default) {
                renderFlowBackgroundFrame(
                    colors = colors,
                    viewportWidth = viewportSize.width,
                    viewportHeight = viewportSize.height,
                    timeMs = flowTimeMs,
                ).asImageBitmap()
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            // 静态背景：三段取色构成的自上而下渐变，流动帧叠在其上，关掉流动即露出这一层。
            // 仅持上次持久化的代表色时先整幅铺该色，取色未就绪时才用主题默认渐变
            .background(
                when {
                    colors != null -> Brush.verticalGradient(colors.stops)
                    restoredColor != null -> SolidColor(restoredColor)
                    else -> defaultBackgroundGradient()
                }
            ),
    ) {
        // 流动帧整幅铺满并盖住静态渐变；帧内已做整体模糊，色块边界不显形
        frame?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 轻微压暗层：静态渐变与流动帧一并压暗，给前景文字留出对比。
        // 强度沿全幅一致，不随位置变化，因而不产生亮度层次分界
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = COVER_BACKGROUND_DIM_ALPHA)),
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

/**
 * 渲染一帧流动背景：在 1/16 视口尺寸的小画布上错位叠画三份纯色块后整体模糊，由显示端放大铺满。
 * 每层取 [CoverBackgroundColors] 的一段色纯色填充（图层与色段同为三段，层序即自上而下的段序），
 * 三层色调已收敛为相近色，模糊后的相接处因此是连续过渡，不会两色硬接。
 *
 * 画色块而非叠画封面：封面各区域的色调可能相差很大，直接叠画会让三个色块各走一色；
 * 色块色只来自封面边缘取色，色调可控且与静态渐变同源。
 * 像素量约为整屏的 1/256，叠画与模糊的代价随之降到可忽略；[timeMs] 推进三层的旋转角度。
 */
internal fun renderFlowBackgroundFrame(
    colors: CoverBackgroundColors,
    viewportWidth: Int,
    viewportHeight: Int,
    timeMs: Long,
): Bitmap {
    val width = (viewportWidth / COVER_BACKGROUND_DOWNSAMPLE).coerceAtLeast(1)
    val height = (viewportHeight / COVER_BACKGROUND_DOWNSAMPLE).coerceAtLeast(1)
    val frame = createBitmap(width, height)
    val canvas = Canvas(frame)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val side = max(width, height) * COVER_BACKGROUND_OVERSCAN
    val matrix = Matrix()
    COVER_BACKGROUND_LAYERS.forEachIndexed { index, layer ->
        val rotation = (timeMs % layer.periodMs).toFloat() / layer.periodMs * 360f *
            if (layer.clockwise) 1f else -1f
        paint.color = colors.stops[index].toArgb()
        matrix.reset()
        matrix.postRotate(rotation, side / 2f, side / 2f)
        matrix.postTranslate(
            -(side - width) / 2f + width * layer.offsetX,
            -(side - height) / 2f + height * layer.offsetY,
        )
        if (layer.rotateAboutCenter) matrix.postRotate(rotation, width / 2f, height / 2f)
        canvas.withMatrix(matrix) {
            drawRect(0f, 0f, side, side, paint)
        }
    }
    return blurBackgroundFrame(frame, COVER_BACKGROUND_BLUR_RADIUS)
}

// 两趟盒式模糊：输入是 1/16 视口的小图，纯 CPU 逐像素处理即可，无需引入渲染管线
private fun blurBackgroundFrame(source: Bitmap, radius: Int): Bitmap {
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

    val result = createBitmap(width, height)
    result.setPixels(pixels, 0, width, 0, 0, width, height)
    // 待模糊的中间帧只在本函数内使用：流动期间每帧都会新建，及时回收避免遗留图片垃圾
    source.recycle()
    return result
}
