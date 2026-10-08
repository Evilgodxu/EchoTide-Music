package com.yichao.evilgodxu.screens.home.component.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind
import com.yichao.evilgodxu.theme.md_theme_dark_background
import com.yichao.evilgodxu.ui.component.COVER_FADE_RATIO
import com.yichao.evilgodxu.ui.component.CoverTransition
import com.yichao.evilgodxu.ui.component.coverFadeBrush
import com.yichao.evilgodxu.ui.component.coverLeftRightFadeBrush
import com.yichao.evilgodxu.ui.component.coverTopBottomFadeBrush
import com.yichao.evilgodxu.ui.component.noElementTransition
import com.yichao.evilgodxu.ui.component.rememberLargeCoverState
import com.yichao.evilgodxu.ui.component.rememberVisibleSession
import com.yichao.evilgodxu.ui.component.trackSlideTransform

// 首页大封面：竖屏沉浸封面与横屏融合封面铺满首屏，取图分三步 ——
// 1. 先以系统最大档略缩图占位出图（列表点选任意曲目时内嵌原图的读取与解码可能要 1–3 秒，封面不能空等）；
// 2. 异步解码内嵌原图（长边至 LargeCoverStore.MAX_EDGE_PX），相邻曲目另按 当前 → 下一 → 上一 预热；
// 3. 高清就位后按「底层常驻 + 上层淡入」替换占位图（见 CoverTransition）。
// 第 3 步不能直接换画面：两级取图的清晰度差与新封面入场都经这一层过渡，直接替换会闪一下再跳一下。
// 缩放由 ImageDecoder 按精确目标尺寸重采样完成（线性过滤 + 多级 mipmap），
// 大比例缩小时边缘与细线不会出现毛刺与锯齿；结果以 WebP 落盘并驻留当前/下一/上一三张，
// 冷启动与往返切歌直接命中，取不到封面即显示占位符，不回退在线封面地址。
// 三级之间不留黑窗：新曲目的封面仍在取图时先沿用上一张已就位的封面，只有确认这首确实没有封面
// 才回到占位符——占位符是与页面底色同为近黑的色块，换曲时直接露出来就是一次黑闪
//
// 换曲目则走整幅横移（见 trackSlideTransform）：方向取自本次变更的类型，与歌曲信息同一套判定。
// 上下两首都已预热在内存，入场的一层从进场那一刻就是成图，不必用过渡遮盖取图空窗；
// 横移全程两层都不透明、首尾相接，容器不会被透出底色。
// 应用不可见期间发生的切歌不补播横移：那几次切歌谁也没看见，回前台时才被应用的一次若按横移补播，
// 返回前台就成了「先看到上一首，再看着它切走」（见 rememberVisibleSession）
@Composable
internal fun HomeAlbumArt(
    track: MusicTrack?,
    kind: TrackSwitchKind,
    modifier: Modifier = Modifier,
) {
    // 上一张已就位的封面：新曲目的封面还在取图时先沿用它，而不退回占位符。
    // 占位符是与页面底色同为近黑的色块，换曲取图期间直接露出来就是一次黑闪
    var lastShown by remember { mutableStateOf<ImageBitmap?>(null) }
    // 回前台不补播切歌过渡：后台期间应用不可见，那几次切歌谁也没看见；重新可见时才被应用的
    // 一次变更若按横移补播，返回前台就成了「先看到上一首，再看着它切走」。
    // 会话重建使过渡以当前曲目为初值，直接落位；当前曲目的封面已由切歌预取备好，首帧即是成图
    val visibleSession = rememberVisibleSession()
    key(visibleSession) {
        AnimatedContent(
            targetState = track,
            // 以曲目标识为过渡键：曲目实例会随歌词补全等元数据更新被替换，用实例作键会误触发过渡
            contentKey = { it?.id },
            transitionSpec = {
                when (kind) {
                    TrackSwitchKind.Previous -> trackSlideTransform(enterFromLeft = true)
                    TrackSwitchKind.Next -> trackSlideTransform(enterFromLeft = false)
                    // 选曲播放没有可读的方向，移入的一侧无从取；
                    // 内容同源的变更（在线曲迁到本地）直接替换，横移会让同源封面错位成接缝
                    TrackSwitchKind.Select, TrackSwitchKind.SameContent -> noElementTransition
                }
            },
            // 渐隐羽化蒙层加在过渡层之外：四条边只对合成后的结果羽化一次。
            // 加在每一层上则位移期间各层的羽化边会移进视口，与本层封面错位成一道可辨的接缝
            modifier = modifier,
            label = "homeCover",
        ) { layer ->
            val state = rememberLargeCoverState(layer)
            // 取图中且暂无图可显示时沿用上一张；已确认这首没有封面则退回占位符（此时 lastShown 不参与）
            val shown = state.cover ?: lastShown.takeIf { state.pending }
            // 只在真正取到图时更新：占位与「暂无」都不该顶掉已就位的一张
            LaunchedEffect(state.cover) { state.cover?.let { lastShown = it } }
            CoverTransition(
                bitmap = shown,
                contentDescription = layer?.title,
                // 首页背景恒为深色，占位块固定用深色主题背景色，避免浅色主题下首帧浅色闪烁
                placeholderColor = md_theme_dark_background,
            )
        }
    }
}

// 首页沉浸式封面：全宽置顶（含状态栏后方），仅下边缘渐隐为透明融入封面下边缘同色的背景衔接层
@Composable
internal fun HomeImmersiveCover(
    track: MusicTrack?,
    kind: TrackSwitchKind,
    modifier: Modifier = Modifier,
) {
    HomeAlbumArt(
        track = track,
        kind = kind,
        modifier = modifier.bottomFadeMask(),
    )
}

// 横屏沉浸封面：四边按竖屏封面下缘的同一处理渐隐，与同源封面衍生的背景无缝衔接，
// 不再呈现为一张有硬边的卡片
@Composable
internal fun HomeBlendedCover(
    track: MusicTrack?,
    kind: TrackSwitchKind,
    modifier: Modifier = Modifier,
) {
    HomeAlbumArt(
        track = track,
        kind = kind,
        modifier = modifier.edgeFeatherMask(),
    )
}

// 四边羽化蒙层：先按水平方向在左右边缘渐隐，再按垂直方向在上下边缘渐隐；
// 两次 DstIn 的透明度相乘，得到四边同时渐隐、四角衰减更强的矩形羽化。
// 两条轴与竖屏封面下缘共用同一条采样曲线、同一带宽（见 ui/component 的 CoverFade），
// 故四条边的过渡轮廓与竖屏下缘完全一致，不随方向变化。
// 与背景衔接处不再有可辨认的硬边（DstIn 只取蒙层透明度，实色段用黑色即可）。
// 蒙层由 saveLayer 限定在本节点边界内，横移期间越出容器的部分被一并裁掉
private fun Modifier.edgeFeatherMask(): Modifier = drawWithCache {
    val horizontal = coverLeftRightFadeBrush(Color.Black)
    val vertical = coverTopBottomFadeBrush(Color.Black)
    onDrawWithContent {
        drawIntoCanvas { canvas -> canvas.saveLayer(Rect(Offset.Zero, size), Paint()) }
        drawContent()
        drawRect(brush = horizontal, size = size, blendMode = BlendMode.DstIn)
        drawRect(brush = vertical, size = size, blendMode = BlendMode.DstIn)
        drawIntoCanvas { canvas -> canvas.restore() }
    }
}

// 下边缘渐隐蒙层：与跑马灯同款 DstIn 处理，封面下缘按与背景衔接层共享的平滑曲线渐隐为透明
// （见 ui/component 的 coverFadeBrush），渐隐带长度同取 COVER_FADE_RATIO。
// 两侧同曲线、等长度，封面底边上下才是对称的同一段过渡，接缝处颜色与亮度连续
private fun Modifier.bottomFadeMask(): Modifier = drawWithCache {
    val brush = coverFadeBrush(
        // DstIn 只取蒙层的透明度，RGB 不参与合成，实色段用黑色即可
        color = Color.Black,
        start = 1f - COVER_FADE_RATIO,
        end = 1f,
    )
    onDrawWithContent {
        drawIntoCanvas { canvas -> canvas.saveLayer(Rect(Offset.Zero, size), Paint()) }
        drawContent()
        drawRect(brush = brush, size = size, blendMode = BlendMode.DstIn)
        drawIntoCanvas { canvas -> canvas.restore() }
    }
}
