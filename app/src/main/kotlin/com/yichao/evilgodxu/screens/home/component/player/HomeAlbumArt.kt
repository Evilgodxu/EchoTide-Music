package com.yichao.evilgodxu.screens.home.component.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.theme.md_theme_dark_background
import com.yichao.evilgodxu.ui.component.COVER_FADE_RATIO
import com.yichao.evilgodxu.ui.component.coverFadeBrush
import com.yichao.evilgodxu.ui.component.coverLeftRightFadeBrush
import com.yichao.evilgodxu.ui.component.coverTopBottomFadeBrush
import com.yichao.evilgodxu.ui.component.rememberLargeCover
import com.yichao.evilgodxu.ui.icons.AppIcons

// 大封面换图（缩略图升清、切歌换封面）的淡入淡出时长
private const val COVER_CROSSFADE_MS = 300

// 首页大封面：竖屏沉浸封面与横屏融合封面铺满首屏，取图分三步 ——
// 1. 先以系统最大档略缩图占位出图（列表点选任意曲目时内嵌原图的读取与解码可能要 1–3 秒，封面不能空等）；
// 2. 异步解码内嵌原图（长边至 LargeCoverStore.MAX_EDGE_PX），相邻曲目另按 当前 → 下一 → 上一 预热；
// 3. 高清就位后淡入替换占位图。
// 第 3 步不能直接换画面：两级取图的清晰度差与新封面入场都经这一层过渡，直接替换会闪一下再跳一下。
// 缩放由 ImageDecoder 按精确目标尺寸重采样完成（线性过滤 + 多级 mipmap），
// 大比例缩小时边缘与细线不会出现毛刺与锯齿；结果以 WebP 落盘并驻留当前/下一/上一三张，
// 冷启动与往返切歌直接命中，取不到封面即显示占位符，不回退在线封面地址
@Composable
internal fun HomeAlbumArt(track: MusicTrack?, modifier: Modifier = Modifier) {
    val cover = rememberLargeCover(track)
    // 换图与缓动都交给 Crossfade：过渡期间两层同时驻留（位图都已在内存），不会露出背景
    Crossfade(
        targetState = cover,
        animationSpec = tween(durationMillis = COVER_CROSSFADE_MS),
        label = "homeCover",
        // 渐隐羽化蒙层加在过渡层之外：四条边只对合成后的结果羽化一次，过渡期间边缘不会显形
        modifier = modifier,
    ) { bitmap ->
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = track?.title,
                contentScale = ContentScale.Crop,
                // 高清渲染：mipmap 三线性过滤，缩放/旋转均无锯齿与模糊
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            )
        } else {
            Box(
                // 首页背景恒为深色，占位背景固定用深色主题背景色，避免浅色主题下首帧浅色闪烁
                modifier = Modifier
                    .fillMaxSize()
                    .background(md_theme_dark_background),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

// 首页沉浸式封面：全宽置顶（含状态栏后方），仅下边缘渐隐为透明融入封面下边缘同色的背景衔接层
@Composable
internal fun HomeImmersiveCover(
    track: MusicTrack?,
    modifier: Modifier = Modifier,
) {
    HomeAlbumArt(
        track = track,
        modifier = modifier.bottomFadeMask(),
    )
}

// 横屏沉浸封面：四边按竖屏封面下缘的同一处理渐隐，与同源封面衍生的背景无缝衔接，
// 不再呈现为一张有硬边的卡片
@Composable
internal fun HomeBlendedCover(
    track: MusicTrack?,
    modifier: Modifier = Modifier,
) {
    HomeAlbumArt(
        track = track,
        modifier = modifier.edgeFeatherMask(),
    )
}

// 四边羽化蒙层：先按水平方向在左右边缘渐隐，再按垂直方向在上下边缘渐隐；
// 两次 DstIn 的透明度相乘，得到四边同时渐隐、四角衰减更强的矩形羽化。
// 两条轴与竖屏封面下缘共用同一条采样曲线、同一带宽（见 ui/component 的 CoverFade），
// 故四条边的过渡轮廓与竖屏下缘完全一致，不随方向变化。
// 与背景衔接处不再有可辨认的硬边（DstIn 只取蒙层透明度，实色段用黑色即可）
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
