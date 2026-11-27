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
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.LocalPlaybackState
import com.yichao.evilgodxu.data.music.metadata.CoverBackgroundColors
import com.yichao.evilgodxu.data.music.metadata.CoverColorCache
import com.yichao.evilgodxu.data.music.metadata.CoverSkipRegistry
import com.yichao.evilgodxu.data.music.metadata.extractCoverBackgroundColors
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind
import com.yichao.evilgodxu.data.settings.backgroundFlowEnabledFlow
import com.yichao.evilgodxu.navigation.LocalScreenActive
import com.yichao.evilgodxu.theme.md_theme_dark_surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max

// 封面取样 64px，取色与代现算共用
internal const val COVER_BACKGROUND_SAMPLE_SIZE = 64

// 帧边长为视口 1/16，像素量约 1/256
private const val COVER_BACKGROUND_DOWNSAMPLE = 16

// 放大到长边 1.3 倍，叠画不露边
private const val COVER_BACKGROUND_OVERSCAN = 1.3f

// 叠画饱和度，灰封面也能出色
private const val COVER_BACKGROUND_SATURATION = 2.5f

// 小图模糊 11px：化开边界，保留明暗层次
private const val COVER_BACKGROUND_BLUR_RADIUS = 11

// 重绘间隔约 30fps，更密的并入下一帧
private const val COVER_BACKGROUND_FLOW_FRAME_INTERVAL_MS = 32L

// 帧间隔上限 1s，超出视为停滞不计入相位
private const val FLOW_FRAME_MAX_GAP_MS = 1_000L

// 取不到显示器刷新率时按 60Hz 兜底
private const val FALLBACK_VSYNC_NANOS = 16_666_667L

// 发布间隔取 vsync 周期的整数倍：120Hz 下 32ms 是 3.84 个周期，
// 不对齐则每次发布都落在 vsync 之间，高刷机上表现为相位推进忽快忽慢
private fun publishIntervalNanos(vsyncNanos: Long): Long {
    val frames = ceil(COVER_BACKGROUND_FLOW_FRAME_INTERVAL_MS * 1_000_000.0 / vsyncNanos)
        .toLong()
        .coerceAtLeast(1L)
    return frames * vsyncNanos
}

// 压暗下限：保证前景文字与状态栏对比
private const val DIM_MIN_ALPHA = 0.15f

// 明度过 0.12 全额压暗，过 0.60 降至 0.4 倍
private const val DIM_LUMINANCE_DARK = 0.12f
private const val DIM_LUMINANCE_LIGHT = 0.60f

// 最亮底色压暗降至 0.4 倍，不低于下限
private const val DIM_LIGHT_SCALE = 0.4f

// 压暗顶端 0.24、中段 0.06、底端 0.40
private const val COVER_SCRIM_TOP_ALPHA = 0.24f
private const val COVER_SCRIM_BASE_ALPHA = 0.06f
private const val COVER_SCRIM_BOTTOM_ALPHA = 0.40f

// 主色垫底 0.14、中性黑 0.12，色相交给锚定
private const val WASH_PRIMARY_DARKEN = 0.55f
private const val WASH_PRIMARY_ALPHA = 0.14f
private const val WASH_SECONDARY_ALPHA = 0.12f

// 模糊后彩度回补 1.12，避免发灰
private const val FRAME_SATURATION_RESTORE = 1.12f

// 中段基底在视口 50% 处，向两端增强
private const val COVER_SCRIM_ZERO_FRACTION = 0.5f

// 每半段采样 12 次，平滑无折点
private const val COVER_SCRIM_SAMPLE_SEGMENTS = 12

// 压暗色标：两端按明度缩放，中段基底固定
private fun coverScrimStops(scale: Float): Array<Pair<Float, Color>> {
    val topAlpha = dimAlpha(COVER_SCRIM_TOP_ALPHA, scale)
    val bottomAlpha = dimAlpha(COVER_SCRIM_BOTTOM_ALPHA, scale)
    val baseAlpha = COVER_SCRIM_BASE_ALPHA
    return buildList {
        for (segment in 0 until COVER_SCRIM_SAMPLE_SEGMENTS) {
            val t = segment.toFloat() / COVER_SCRIM_SAMPLE_SEGMENTS
            add(
                COVER_SCRIM_ZERO_FRACTION * t to
                    Color.Black.copy(alpha = baseAlpha + (topAlpha - baseAlpha) * smoothFadeAlpha(t)),
            )
        }
        for (segment in 0..COVER_SCRIM_SAMPLE_SEGMENTS) {
            val t = segment.toFloat() / COVER_SCRIM_SAMPLE_SEGMENTS
            add(
                (COVER_SCRIM_ZERO_FRACTION + (1f - COVER_SCRIM_ZERO_FRACTION) * t) to
                    Color.Black.copy(
                        alpha = baseAlpha + (bottomAlpha - baseAlpha) * (1f - smoothFadeAlpha(t)),
                    ),
            )
        }
    }.toTypedArray()
}

/** 按压暗系数缩放强度，下限 DIM_MIN_ALPHA */
private fun dimAlpha(fullAlpha: Float, scale: Float): Float =
    (fullAlpha * scale).coerceAtLeast(DIM_MIN_ALPHA)

/** 底色越亮压暗系数越低，无底色时取 1 */
private fun dimScaleOf(color: Color?): Float {
    val luminance = color?.luminance() ?: return 1f
    val overLight = (luminance - DIM_LUMINANCE_DARK) / (DIM_LUMINANCE_LIGHT - DIM_LUMINANCE_DARK)
    return 1f - overLight.coerceIn(0f, 1f) * (1f - DIM_LIGHT_SCALE)
}

// 切歌横移沿用 TRACK_SLIDE_MS，与换图同时落位

// 取色落地淡出 480ms，无方向
private const val COVER_BACKGROUND_FADE_MS = 480

// 三层周期互质且反向，叠画缓慢漂移不显循环
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
    // 错位后再绕中心旋转，轨迹更像漂浮
    val rotateAboutCenter: Boolean,
)

// 整屏换色的过渡方式
private enum class BackgroundSwitch {
    // 同曲取色落地：原地淡出
    Fade,

    // 切歌横移：新旧色面同速平移，名称即新色入侧
    SlideFromLeft,
    SlideFromRight,
}

// 换色过渡：旧底色、方式与冻结帧
private data class BackgroundSwitchState(
    val base: BackgroundBase,
    val switch: BackgroundSwitch,
    // 切歌瞬间的帧，用于复现旧背景；空表示当时只有底色
    val frame: ImageBitmap?,
)

// 底色：取色未就绪时铺落盘单色，避免空白
private data class BackgroundBase(
    val colors: CoverBackgroundColors?,
    val solid: Color?,
) {
    // 压暗依据的色调：代表色或落盘单色
    val tone: Color?
        get() = colors?.representative ?: solid

    fun fill(): Brush = SolidColor(tone ?: md_theme_dark_surface)
}

// 歌曲沉浸背景：帧由封面叠画、模糊、色相锚定生成
// 流动开关只推相位，关闭时画面停在首帧
// 压暗随底色明度收敛，浅底更轻，由下限兜底
// 取色优先缓存与落盘，自行按曲目取用并回传
// 切歌横移、同曲取色淡出；回前台不补播过渡
@Composable
internal fun SongImmersiveBackground(
    track: MusicTrack?,
    modifier: Modifier = Modifier,
    // 换色方向；空表示浏览场景，交叠淡出
    switchKind: TrackSwitchKind? = null,
    onBackgroundColor: ((Color) -> Unit)? = null,
    onExtractedColors: ((CoverBackgroundColors) -> Unit)? = null,
) {
    val context = LocalContext.current
    val playbackState = LocalPlaybackState.current
    val audioUri = track?.audioUri
    val coverRevision = playbackState.coverRevision
    // 该曲上次落盘的代表色，取不到色时整幅铺它
    val restoredColor = playbackState.restoredBackgroundColorFor(track)
    // 预取取色，切歌首帧即可用
    val cachedColors = remember(audioUri, coverRevision) { audioUri?.let { CoverColorCache.get(it) } }
    // 与封面共用系统缩略图，重写后重新取色
    val thumbnailState = rememberThumbnailState(track, COVER_BACKGROUND_SAMPLE_SIZE)
    val thumbnail = thumbnailState.bitmap
    var extracted by remember(audioUri, coverRevision) { mutableStateOf(cachedColors) }
    // 取色未定论时沿用旧底色，避免闪黑
    var resolved by remember(audioUri, coverRevision) { mutableStateOf(cachedColors != null) }
    LaunchedEffect(audioUri, coverRevision, thumbnail, thumbnailState.pending) {
        // 优先缓存取色，其次用缩略图现算
        val cached = CoverColorCache.get(audioUri.orEmpty())
        val colors = cached ?: thumbnail?.asAndroidBitmap()?.let { extractCoverBackgroundColors(it) }
        // 封面取图未完成先不定论，沿用旧底色
        if (colors == null && thumbnailState.pending) return@LaunchedEffect
        extracted = colors
        resolved = true
        // 仅回传新算结果，缓存命中不重复落盘
        if (colors != null && cached == null) onExtractedColors?.invoke(colors)
    }
    val colors = extracted
    val background = colors?.representative ?: restoredColor ?: md_theme_dark_surface
    LaunchedEffect(background) { onBackgroundColor?.invoke(background) }

    // 帧提前渲染，静止与流动共用同一帧
    val flowEnabled by context.backgroundFlowEnabledFlow().collectAsStateWithLifecycle(initialValue = false)
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    // 现算帧所属曲目，用于认领
    var frameUri by remember { mutableStateOf<String?>(null) }

    // 换色过渡：旧底色连同冻结帧整屏铺在上层
    // 目标为空表示取色未定论，画面沿用当前底色
    val targetBase = remember(colors, restoredColor, resolved) {
        if (!resolved) {
            null
        } else {
            BackgroundBase(colors = colors, solid = if (colors == null) restoredColor else null)
        }
    }
    val visibleSession = rememberVisibleSession()
    // 铺底色时的可见会话，用于识别后台换色
    var lastBaseSession by remember { mutableIntStateOf(visibleSession) }
    // 实际铺开的底色，与退场层同帧落定
    var base by remember { mutableStateOf(targetBase ?: BackgroundBase(colors = null, solid = restoredColor)) }
    // 底色是否已换成当前曲目
    val baseApplied = base == targetBase
    // 代现算备好的同曲目帧，与现算同源同相位
    val preparedFrame = remember(audioUri, coverRevision) { CoverFlowFrameStore.peek(audioUri, coverRevision) }
    // 优先显示端现算帧，其次代现算帧，再退上次帧
    val shownFrame = when {
        // 底色未换到本曲目时沿用旧帧，底纹与底色同步入场
        !baseApplied -> frame
        frameUri == audioUri -> frame
        else -> preparedFrame ?: frame
    }
    // 各层共用的明度压暗系数
    val dimScale = remember(base) { dimScaleOf(base.tone) }
    // 当前底色是否沿用自上一曲，用于判定取色落地
    var baseInherited by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf<BackgroundSwitchState?>(null) }
    // 过渡进度：1 起点，0 落位
    val switchProgress = remember { Animatable(0f) }
    LaunchedEffect(targetBase, visibleSession) {
        val next = targetBase
        val previous = base
        val previousInherited = baseInherited
        // 重新可见时作废旧过渡，不补播
        val resumed = lastBaseSession != visibleSession
        if (resumed) switching = null
        lastBaseSession = visibleSession
        // 取色未定论：沿用当前底色
        if (next == null) {
            baseInherited = true
            return@LaunchedEffect
        }
        baseInherited = false
        if (previous == next) return@LaunchedEffect
        // 后台换色直接落位，不补横移
        if (resumed) {
            base = next
            return@LaunchedEffect
        }
        // 回落色或继承底色时淡出；有方向才横移
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
            base = next
            return@LaunchedEffect
        }
        // 先复位进度，再一次性立退场层并换底色
        switchProgress.snapTo(1f)
        // 冻结切歌瞬间的帧，复现旧画面
        switching = BackgroundSwitchState(base = previous, switch = sweep, frame = frame)
        // 与换底色同帧写入，不留破绽
        base = next
        switchProgress.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                if (sweep == BackgroundSwitch.Fade) COVER_BACKGROUND_FADE_MS else TRACK_SLIDE_MS
            ),
        )
        switching = null
    }

    // 流动时间轴：相位按帧累加，只在本曲可见且页面活跃时推进
    // 不取系统时间，避免回前台一次性跳变
    // 关开关时相位钉在零点，即首帧
    var flowTimeMs by remember(audioUri) { mutableLongStateOf(0L) }
    val screenActive = LocalScreenActive.current
    // 发布时刻对齐 vsync 整数倍，高刷设备上间隔仍稳定在约 30fps
    val view = LocalView.current
    val publishInterval = remember(view) {
        val vsyncNanos = view.display?.refreshRate
            ?.takeIf { it > 0f }
            ?.let { (1_000_000_000.0 / it).toLong() }
            ?: FALLBACK_VSYNC_NANOS
        publishIntervalNanos(vsyncNanos)
    }
    LaunchedEffect(flowEnabled, screenActive, visibleSession) {
        if (!flowEnabled) {
            flowTimeMs = 0L
            return@LaunchedEffect
        }
        // 被覆盖层压住期间不订阅 vsync，恢复后从上次相位继续
        if (!screenActive) return@LaunchedEffect
        var elapsedMs = flowTimeMs
        var previousNanos = 0L
        var nextPublishNanos = 0L
        while (true) {
            val nowNanos = withFrameNanos { it }
            // 帧间隔上限，超出视为停摆
            val deltaNanos = (nowNanos - previousNanos)
                .takeIf { previousNanos != 0L && it in 0..FLOW_FRAME_MAX_GAP_MS * 1_000_000L } ?: 0L
            previousNanos = nowNanos
            elapsedMs += deltaNanos / 1_000_000L
            if (nextPublishNanos == 0L || nowNanos >= nextPublishNanos) {
                flowTimeMs = elapsedMs
                // 按网格递增；落后超过一格即重新对齐，不补帧
                nextPublishNanos += publishInterval
                if (nextPublishNanos <= nowNanos) nextPublishNanos = nowNanos + publishInterval
            }
        }
    }

    // 相位每发布一次渲染一帧；渲染慢于发布时丢弃积压，只补最新相位
    // 不用 collectLatest：渲染不可中断，取消只会白跑一趟
    LaunchedEffect(audioUri, track, thumbnail, viewportSize, base.tone, baseApplied) {
        // 无曲目或确认无封面时撤帧，只留底色
        if (track == null || CoverSkipRegistry.isSkipped(track)) {
            frame = null
            frameUri = null
            return@LaunchedEffect
        }
        // 底色未落位先不算帧
        if (!baseApplied) return@LaunchedEffect
        // 封面取图中保留旧帧，避免换帧窗口露底色
        val cover = thumbnail?.asAndroidBitmap() ?: return@LaunchedEffect
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return@LaunchedEffect
        snapshotFlow { flowTimeMs }.conflate().collect { phase ->
            frame = withContext(Dispatchers.Default) {
                renderCoverFlowFrame(cover, viewportSize, base.tone, phase)
            }
            // 认领本曲目后逐帧推进
            frameUri = audioUri
        }
    }
    val transition = switching
    // 压暗色标按明度系数缓存
    val scrimStops = remember(dimScale) { coverScrimStops(dimScale) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                viewportSize = it
                // 代现算按同一尺寸渲染，构图一致
                CoverFlowFrameStore.setViewport(it)
            },
    ) {
        // 当前底色层：纯色打底，帧铺其上；横移时整层入场
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 进度越大离落位越远，方向与封面横移一致
                    translationX = when (transition?.switch) {
                        BackgroundSwitch.SlideFromLeft -> -size.width * switchProgress.value
                        BackgroundSwitch.SlideFromRight -> size.width * switchProgress.value
                        else -> 0f
                    }
                },
        ) {
            Box(modifier = Modifier.fillMaxSize().background(base.fill()))
            // 无帧时才铺这层均匀压暗
            if (shownFrame == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = DIM_MIN_ALPHA)),
                )
            }
            // 帧铺满，已含整体模糊
            shownFrame?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
                // 帧压暗层：两端压住状态栏，按明度收敛
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(colorStops = scrimStops)),
                )
            }
        }
        // 旧底色层：复现切歌瞬间画面，同速移出拼满整屏
        transition?.let { previous ->
            // 旧帧按旧底色明度压暗，复刻当时画面
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
                Box(modifier = Modifier.fillMaxSize().background(previous.base.fill()))
                if (previous.frame == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = DIM_MIN_ALPHA)),
                    )
                }
                // 冻结帧连同压暗层一并复刻
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

/** 现算流动帧：色相锚定、叠画、蒙层与模糊的唯一口径 */
internal fun renderCoverFlowFrame(cover: Bitmap, viewport: IntSize, tone: Color?, timeMs: Long): ImageBitmap {
    val dimScale = dimScaleOf(tone)
    // 两层蒙层按明度缩放，强度由下限兜底
    val washPrimary = lerp(tone ?: md_theme_dark_surface, Color.Black, WASH_PRIMARY_DARKEN * dimScale)
        .copy(alpha = dimAlpha(WASH_PRIMARY_ALPHA, dimScale))
    val washSecondary = Color.Black.copy(alpha = dimAlpha(WASH_SECONDARY_ALPHA, dimScale))
    return renderCoverBackgroundFrame(
        cover = cover,
        viewportWidth = viewport.width,
        viewportHeight = viewport.height,
        timeMs = timeMs,
        anchorHue = tone?.anchorHueOrNull(),
        washPrimaryArgb = washPrimary.toArgb(),
        washSecondaryArgb = washSecondary.toArgb(),
    ).asImageBitmap()
}

// 近灰主色不锚定，避免引入噪声色相
private fun Color.anchorHueOrNull(): Float? {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    return hsl[0].takeIf { hsl[1] >= HUE_ANCHOR_MIN_SATURATION }
}

/** 叠画三份封面，锚定色相后加蒙层、模糊并回补彩度 */
private fun renderCoverBackgroundFrame(
    cover: Bitmap,
    viewportWidth: Int,
    viewportHeight: Int,
    timeMs: Long,
    anchorHue: Float?,
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
    // 模糊前锚定色相，决定整幅色系
    anchorHue?.let { anchorFrameHues(frame, it) }
    canvas.drawColor(washPrimaryArgb)
    canvas.drawColor(washSecondaryArgb)
    return restoreFrameSaturation(blurBackgroundFrame(frame, COVER_BACKGROUND_BLUR_RADIUS))
}

// 彩度回补用色彩矩阵一次绘过，成本低一个量级
private fun restoreFrameSaturation(source: Bitmap): Bitmap {
    val restored = createBitmap(source.width, source.height)
    Canvas(restored).drawBitmap(
        source,
        0f,
        0f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(FRAME_SATURATION_RESTORE) })
        },
    )
    source.recycle()
    return restored
}

// 两趟盒式模糊，小图纯 CPU 即可
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
    // 待模糊的中间帧用完即回收
    source.recycle()
    return result
}
