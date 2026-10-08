package com.yichao.evilgodxu.ui.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.createBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.LocalMusicPanelStateHolder
import com.yichao.evilgodxu.data.music.metadata.CoverBackgroundColors
import com.yichao.evilgodxu.data.music.metadata.CoverColorCache
import com.yichao.evilgodxu.data.music.metadata.extractCoverBackgroundColors
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind
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

// 封面放大系数：放大到画布较长边的 1.3 倍后居中，错位叠画仍能盖满画布不露空边
private const val COVER_BACKGROUND_OVERSCAN = 1.3f

// 叠画饱和度：封面本身偏灰时也能得到有色彩的背景
private const val COVER_BACKGROUND_SATURATION = 2.5f

// 模糊半径：按 1/16 小图尺度取固定值，叠画后的封面柔化为连贯色域，边界不显形
private const val COVER_BACKGROUND_BLUR_RADIUS = 15

// 流动帧间隔：与显示帧率对齐约 30fps，低于此间隔的重绘并入下一帧
private const val COVER_BACKGROUND_FLOW_FRAME_INTERVAL_MS = 32L

// 压暗强度的下限：底色的明度再高，各层压暗也不低于这一强度。压暗层同时承担前景文字与状态栏的
// 对比，浅色底若按明度一路减下去，对比就不够了。关闭流动时的静态底色压暗层正按这一强度铺开，
// 故它不参与明度收敛；流动帧的压暗层与蒙层基准强度更高，收敛后由本值兜底
private const val DIM_MIN_ALPHA = 0.15f

// 压暗的明度自适应：底色相对亮度低于 [DIM_LUMINANCE_DARK] 时全额压暗，高于 [DIM_LUMINANCE_LIGHT] 时
// 降到 [DIM_LIGHT_SCALE]，区间内线性收敛。浅色封面衍生出的浅色底一旦按深色底的强度压暗，
// 自身会明显变暗发灰，与封面外缘拉开距离，封面渐隐带上便现出一条可辨的暗色差带
private const val DIM_LUMINANCE_DARK = 0.12f
private const val DIM_LUMINANCE_LIGHT = 0.60f

// 浅色底保留的压暗比例：明度最高的底色按此比例压缩帧上的压暗强度，压不到下限的由下限兜住
private const val DIM_LIGHT_SCALE = 0.4f

// 流动帧压暗层的顶端与底端强度：帧由封面叠画而来、整体偏亮，压暗上下边缘保证状态栏与前景文字可读
private const val COVER_SCRIM_TOP_ALPHA = 0.18f
private const val COVER_SCRIM_BOTTOM_ALPHA = 0.30f

// 流动帧两层蒙层的强度：第一层以封面主色（向黑收敛 [WASH_PRIMARY_DARKEN]）把画面中占比小的杂色
// 拉向主色，第二层中性黑只负责压暗、不改变色相。两层的暗化量同乘底色的明度系数，强度由 [dimAlpha] 兜底
private const val WASH_PRIMARY_DARKEN = 0.28f
private const val WASH_PRIMARY_ALPHA = 0.34f
private const val WASH_SECONDARY_ALPHA = 0.18f

// 压暗层中段的归零位置（占视口高度比例）：此处压暗为 0，上半段向顶端增强、下半段向底端增强
private const val COVER_SCRIM_ZERO_FRACTION = 0.5f

// 压暗层每半段的采样段数：色标之间为线性插值，按平滑曲线采样后不再出现折点
private const val COVER_SCRIM_SAMPLE_SEGMENTS = 12

// 流动帧压暗层色标：顶端 [COVER_SCRIM_TOP_ALPHA] 平滑收敛到 [COVER_SCRIM_ZERO_FRACTION] 处为 0，
// 再平滑增强到底端 [COVER_SCRIM_BOTTOM_ALPHA]。两半都取自 [smoothFadeAlpha]，
// 两端与归零点的斜率均为 0：压暗层自身不会在背景中段留下层次分界。
// [scale] 为底色的明度系数（见 dimScaleOf），两端强度按它缩放并由 [dimAlpha] 兜住下限；
// 中段的归零点不受下限约束——上半段的收尾与下半段的起始都要落在那里才接得上。
// 色标形状只与常量有关，同一系数下构造一次即可复用
private fun coverScrimStops(scale: Float): Array<Pair<Float, Color>> {
    val topAlpha = dimAlpha(COVER_SCRIM_TOP_ALPHA, scale)
    val bottomAlpha = dimAlpha(COVER_SCRIM_BOTTOM_ALPHA, scale)
    return buildList {
        for (segment in 0 until COVER_SCRIM_SAMPLE_SEGMENTS) {
            val t = segment.toFloat() / COVER_SCRIM_SAMPLE_SEGMENTS
            add(
                COVER_SCRIM_ZERO_FRACTION * t to
                    Color.Black.copy(alpha = topAlpha * smoothFadeAlpha(t)),
            )
        }
        for (segment in 0..COVER_SCRIM_SAMPLE_SEGMENTS) {
            val t = segment.toFloat() / COVER_SCRIM_SAMPLE_SEGMENTS
            add(
                (COVER_SCRIM_ZERO_FRACTION + (1f - COVER_SCRIM_ZERO_FRACTION) * t) to
                    Color.Black.copy(alpha = bottomAlpha * (1f - smoothFadeAlpha(t))),
            )
        }
    }.toTypedArray()
}

/**
 * 明度系数对应的压暗强度：按系数缩放，并以 [DIM_MIN_ALPHA] 兜底。
 * 兜底与缩放同样重要——浅色底压得更轻是为了贴合封面外缘的观感，
 * 轻到前景文字与状态栏看不出对比便失去了压暗层的意义。
 */
private fun dimAlpha(fullAlpha: Float, scale: Float): Float =
    (fullAlpha * scale).coerceAtLeast(DIM_MIN_ALPHA)

/**
 * 底色明度对应的压暗系数：相对亮度越高系数越低，浅色底因此压得更轻。
 * 相对亮度按人眼感知加权（[luminance]），与按色相/饱和度取色的取色模块口径不同：
 * 这里要判的是「这个底色看上去有多亮」，而非它是不是有彩。
 * 系数只决定帧上各层压暗的相对轻，绝对强度由 [dimAlpha] 兜住下限。
 * 无底色可依据时取 1，即维持原有的全额压暗。
 */
private fun dimScaleOf(color: Color?): Float {
    val luminance = color?.luminance() ?: return 1f
    val overLight = (luminance - DIM_LUMINANCE_DARK) / (DIM_LUMINANCE_LIGHT - DIM_LUMINANCE_DARK)
    return 1f - overLight.coerceIn(0f, 1f) * (1f - DIM_LIGHT_SCALE)
}

// 切歌横移沿用封面那一条时长（见 TRACK_SLIDE_MS）：整屏换色必须与换图同时推进、同时落位，
// 各写一份时长迟早会漂移出前后脚

// 同曲取色落地的过渡时长：底色由占位色换成真实取色，无方向可言，只做交叠淡出
private const val COVER_BACKGROUND_FADE_MS = 480

// 背景流动的三层固定默认值：旋转周期与方向、错位量（占画布宽高的比例）。
// 三层周期互质且方向相反，叠画后的封面缓慢漂移而不出现明显循环
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

// 整屏换色的过渡方式
private enum class BackgroundSwitch {
    // 上一份底色原地淡出：用于同曲取色落地——新色只是替换占位色，没有方向可言
    Fade,

    // 切歌横移：上一份底色与当前底色各持一份整幅色面，同幅同速平移，全程拼满整屏。
    // 底色是竖向渐变，自身横移看不出变化，横移的是整层色面，读起来就是新色自一侧漫过来。
    // 名称即新色移入的一侧，与封面、歌曲信息的横移取同一侧
    SlideFromLeft,
    SlideFromRight,
}

// 正在进行的整屏换色：移出的一份底色、过渡方式，以及切歌瞬间冻结的流动帧
private data class BackgroundSwitchState(
    val base: BackgroundBase,
    val switch: BackgroundSwitch,
    // 切歌那一刻画面上的流动帧：退场层用它复现旧曲目的完整背景。
    // 为空表示切歌时本就只有静态底色（未开流动，或帧尚未就绪）
    val frame: ImageBitmap?,
)

// 整屏底色的一种渲染口径：三段取色就绪时为自上而下三段渐变，未就绪时退化为单色铺底
private data class BackgroundBase(
    val colors: CoverBackgroundColors?,
    val solid: Color?,
) {
    // 压暗自适应所依据的色调：有取色结果时以代表色为准，否则退回落盘的单一底色
    val tone: Color?
        get() = colors?.representative ?: solid

    fun brush(): Brush = colors?.let { Brush.verticalGradient(it.stops) }
        ?: solid?.let { SolidColor(it) }
        ?: defaultBackgroundGradient()
}

// 歌曲沉浸式背景：整屏统一渲染，不随顶置封面位置做局部处理，左右切页时背景始终连续。
// 底色取自封面边缘的三段相近色（见 extractCoverBackgroundColors）：关闭「背景流动」时只铺一条
// 由三段色构成的自上而下渐变，开启后在其上叠一层缓慢漂移的封面叠画帧（见 renderCoverBackgroundFrame），
// 帧内以封面主色与中性黑压暗、其上再按 [coverScrimStops] 渐变压暗；关掉流动即撤下帧回到渐变。
// 帧上的压暗层与蒙层随底色的明度收敛（见 [dimScaleOf]）：浅色封面叠出的浅色背景只轻度压暗，
// 保持与封面外缘同色调，封面渐隐带上不出现暗色差带；收敛后各层强度由 [DIM_MIN_ALPHA] 兜底，
// 静态底色压暗层恒定按该强度铺开——浅色底同样要留出前景文字与状态栏的对比。
// 三段取色未就绪时先回落 [restoredColor]（上次持久化的取色结果）整幅铺色，避免冷启动首帧闪默认色。
// 底色有变时上一份底色与当前底色交叠一处：切歌按方向整屏横移，同曲取色落地则原地淡出，
// 两种情形都不会瞬间跳变。
// 回前台不补播过渡：应用不可见期间发生的换色谁也没看见，重新可见时才被应用的那一次若补播横移，
// 返回前台就成了「先看到上一首的底色，再看着它整屏移走」，故按可见会话识别后直接落位；
// 取色尚未定论、暂时沿用上一份底色的兜底不受此影响（回落时机不属于一次换色）。
// 首页与 3D 封面轮播共用，随传入曲目实时变化；背景代表色经回调暴露供浮层容器复用。
@Composable
internal fun SongImmersiveBackground(
    track: MusicTrack?,
    modifier: Modifier = Modifier,
    // 触发本次换色的曲目变更类型：带方向的类型按该方向整屏横移，其余交叠淡出或直接替换。
    // 为空表示调用方无从提供类型（浏览场景，如 3D 封面轮播），同样只做交叠淡出
    switchKind: TrackSwitchKind? = null,
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
    // 取色是否已有定论：未定论时先沿用当前底色，不回落默认渐变——
    // 那是与页面底色同为近黑的一条，换曲时整屏瞬间发黑
    var resolved by remember(audioUri, coverRevision) { mutableStateOf(cachedColors != null) }
    LaunchedEffect(audioUri, coverRevision, thumbnail) {
        // 取色优先用预取结果：它与高清封面同源，且不依赖略缩图是否已解码
        val colors = CoverColorCache.get(audioUri.orEmpty())
            ?: thumbnail?.asAndroidBitmap()?.let { extractCoverBackgroundColors(it) }
        extracted = colors
        resolved = true
        if (colors != null) onExtractedColor?.invoke(colors.representative)
    }
    val colors = extracted
    val background = colors?.representative ?: restoredColor ?: md_theme_dark_surface
    LaunchedEffect(background) { onBackgroundColor?.invoke(background) }

    // 流动帧：只在开启流动且封面略缩图就绪时渲染，随流动时间推进重算；
    // 关闭流动即置空，背景不再有任何流动帧参与渲染，只剩下方静态渐变。
    // 提前于换色逻辑声明：换色过渡要把切歌瞬间的这一帧冻结进退场层
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }

    // 整屏换色：上一份底色连同切歌瞬间冻结的流动帧整屏铺在上层，按下述方式与当前底色交叠。
    // 流动帧必须一并冻结带走：帧由封面现算，切歌那刻会立刻换成新曲目的帧，而底色交叠要持续一整段时长；
    // 只管底色、任由帧瞬间换掉，整屏就会在切歌伊始跳一次色。
    // 旧底色与旧帧自带相同的压暗层，过渡期间的亮度与静止画面一致
    //
    // 目标底色为 null 表示取色还没定论：此时不发新底色，画面沿用当前这份，
    // 等取色落地再换——回落默认渐变会让整屏先黑一下再变彩，比晚一步换色难看得多
    val targetBase = remember(colors, restoredColor, resolved) {
        if (!resolved) {
            null
        } else {
            BackgroundBase(colors = colors, solid = if (colors == null) restoredColor else null)
        }
    }
    val visibleSession = rememberVisibleSession()
    var lastBase by remember { mutableStateOf<BackgroundBase?>(null) }
    // 铺下上一份底色时的可见会话：与当前会话不同，说明这份底色与本次换色之间隔着一段页面不可见的时间，
    // 即本次换色发生在后台。这类换色回前台时才被应用，不是「刚刚发生」的事件，见下方过渡判定
    var lastBaseSession by remember { mutableIntStateOf(visibleSession) }
    // 实际铺开的底色：取色未定论时仍是当前这份；连一份都还没有（冷启动首帧）才回落历史取色与默认渐变
    val base = targetBase ?: lastBase ?: BackgroundBase(colors = null, solid = restoredColor)
    // 流动帧各层压暗共用的明度系数：底色越浅压得越轻（见 dimScaleOf），强度由 dimAlpha 兜住下限
    val dimScale = remember(base) { dimScaleOf(base.tone) }
    // 当前铺开的底色是否沿用自上一曲：取色未定论期间为真，落地后归假。
    // 它与「上一份有没有取色」合起来判定本次是不是取色落地——落在两处之一都不该按键类型横移
    var baseInherited by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf<BackgroundSwitchState?>(null) }
    // 过渡进度：1 为过渡起点（当前底色尚未入场），0 为过渡结束（当前底色完全落位）
    val switchProgress = remember { Animatable(0f) }
    LaunchedEffect(targetBase, base, visibleSession) {
        val previous = lastBase
        val previousInherited = baseInherited
        // 页面刚重新可见（会话切换）：不可见期间谁也没看见画面，回前台不该补播任何过渡。
        // 上一轮未走完的过渡连同进度一并作废——动画在不可见期间停摆，接着走等于把半途的横移补出来；
        // 同时据此识别下方「本次换色发生在后台」的情形
        val resumed = lastBaseSession != visibleSession
        if (resumed) switching = null
        lastBase = base
        lastBaseSession = visibleSession
        baseInherited = targetBase == null && previous != null
        if (previous == null || previous == base) return@LaunchedEffect
        // 本次换色发生在页面不可见期间：底色照常换成新曲目的取色，只是补播横移会读成刚切歌，直接落位
        if (resumed) return@LaunchedEffect
        // 上一份是回落色（无取色）或沿用自上一曲，说明本次是取色落地：先后关系不可读，只做淡出；
        // 两份都持真实取色时才谈方向，方向由变更类型给出——选曲播放与内容同源的变更都没有方向，
        // 不做过渡，底色原地换掉
        val kind = switchKind
        val sweep = when {
            previousInherited || previous.colors == null -> BackgroundSwitch.Fade
            kind == null -> BackgroundSwitch.Fade
            kind == TrackSwitchKind.Previous -> BackgroundSwitch.SlideFromLeft
            kind == TrackSwitchKind.Next -> BackgroundSwitch.SlideFromRight
            else -> null
        }
        if (sweep == null) {
            switching = null
            return@LaunchedEffect
        }
        // 此刻 frame 仍是旧曲目的帧：新曲目的帧要等略缩图解码后才现算，本效果先于那次现算读到它
        switching = BackgroundSwitchState(base = previous, switch = sweep, frame = frame)
        switchProgress.snapTo(1f)
        switchProgress.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                if (sweep == BackgroundSwitch.Fade) COVER_BACKGROUND_FADE_MS else TRACK_SLIDE_MS
            ),
        )
        switching = null
    }

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

    // 流动帧蒙层（见 WASH_* 常量）：两层的暗化量都按 [dimScale] 缩放，浅色封面叠出的浅色帧因此不会被压暗成另一种色调；
    // 强度由 [dimAlpha] 兜住下限，浅色帧上也要留出前景文字与状态栏的对比
    val washPrimary = remember(background, dimScale) {
        lerp(background, Color.Black, WASH_PRIMARY_DARKEN * dimScale)
            .copy(alpha = dimAlpha(WASH_PRIMARY_ALPHA, dimScale))
    }
    val washSecondary = remember(dimScale) { Color.Black.copy(alpha = dimAlpha(WASH_SECONDARY_ALPHA, dimScale)) }
    LaunchedEffect(thumbnail, flowEnabled, viewportSize, flowTimeMs, washPrimary, washSecondary) {
        val cover = thumbnail?.asAndroidBitmap()
        frame = if (!flowEnabled || cover == null || viewportSize.width <= 0 || viewportSize.height <= 0) {
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
    val transition = switching
    // 压暗层色标随底色明度变化，按系数缓存复用
    val scrimStops = remember(dimScale) { coverScrimStops(dimScale) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it },
    ) {
        // 当前底色层：三段取色构成的自上而下渐变，流动帧叠在其上，关掉流动即露出渐变；
        // 仅持上次持久化的代表色时先整幅铺该色，取色未就绪时才用主题默认渐变。
        // 切歌横移时整层自进入侧入场，落位后偏移归零——底色自身的竖向渐变横移看不出变化，
        // 读到的位移来自本层与原底色层色面的相对运动
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 过渡进度 1 为起点，偏移按进度本身取：进度越大离落位越远，
                    // 入场侧即这里的符号方向，须与封面、歌曲信息的横移方向一致
                    translationX = when (transition?.switch) {
                        BackgroundSwitch.SlideFromLeft -> -size.width * switchProgress.value
                        BackgroundSwitch.SlideFromRight -> size.width * switchProgress.value
                        else -> 0f
                    }
                },
        ) {
            Box(modifier = Modifier.fillMaxSize().background(base.brush()))
            // 静态底色压暗层：关闭流动时背景只剩这条渐变，均匀压暗给前景文字留出对比。
            // 强度沿全幅一致，不随位置变化，因而不产生亮度层次分界；按压暗下限铺开，浅色底也是这个强度
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = DIM_MIN_ALPHA)),
            )
            // 流动帧整幅铺满并盖住静态底色；帧内已做整体模糊，封面叠画的边界不显形
            frame?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                // 流动帧压暗层：帧整体偏亮，压暗上下边缘保证状态栏与前景文字可读。
                // 归零点与两端斜率均为 0，不会在背景中段留下亮度分界；
                // 两端强度按底色明度收敛，并由 [DIM_MIN_ALPHA] 兜底
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(colorStops = scrimStops)),
                )
            }
        }
        // 上一份底色层：置于最上层，复现旧曲目切歌那一刻的完整背景——底色、压暗层，
        // 以及当时冻结的流动帧（帧在时盖住底色，观感与切歌前静止画面一致）。
        // 横移时向离开侧等速移出，与当前底色层同速：两层的偏移量之和恒为一屏宽，
        // 全程首尾相接拼满整屏，谁也不会先离开而露出空档
        transition?.let { previous ->
            // 退场层里的流动帧按旧底色自己的明度系数压暗：复刻的是切歌那一刻的画面，不随新底的深浅变化。
            // 静态压暗层是定值，新旧两层自然一致
            val previousDimScale = remember(previous) { dimScaleOf(previous.base.tone) }
            val previousScrimStops = remember(previousDimScale) { coverScrimStops(previousDimScale) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = if (previous.switch == BackgroundSwitch.Fade) switchProgress.value else 1f
                        translationX = when (previous.switch) {
                            BackgroundSwitch.SlideFromLeft -> size.width * (1f - switchProgress.value)
                            BackgroundSwitch.SlideFromRight -> -size.width * (1f - switchProgress.value)
                            BackgroundSwitch.Fade -> 0f
                        }
                    },
            ) {
                Box(modifier = Modifier.fillMaxSize().background(previous.base.brush()))
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = DIM_MIN_ALPHA)),
                )
                // 冻结的流动帧连同其压暗层一并在层内复刻，退场侧与切歌前的静止画面完全同源，
                // 不会在切歌伊始由流动帧骤然塌回静态渐变
                previous.frame?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(colorStops = previousScrimStops)),
                    )
                }
            }
        }
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
 * 渲染一帧流动背景：在 1/16 视口尺寸的小画布上错位叠画三份高饱和封面，叠加主色与中性黑两层蒙层后
 * 整体模糊，由显示端放大铺满。像素量约为整屏的 1/256，叠画与模糊的代价随之降到可忽略。
 * [timeMs] 推进三层的旋转角度。
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
    val frame = createBitmap(width, height)
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
