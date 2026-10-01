package com.yichao.evilgodxu.screens.home.component.player

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
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
import com.yichao.evilgodxu.ui.component.rememberSystemThumbnail
import com.yichao.evilgodxu.ui.icons.AppIcons

// 首页大封面按面板尺寸取图：512 已是可用封面图的最大档，再大也只是插值放大
private const val HOME_COVER_THUMBNAIL_SIZE = 512

// 首页大封面：与其余封面显示处同源（见 rememberSystemThumbnail）；
// 封面图取不到即显示占位符，不回退在线封面地址
@Composable
internal fun HomeAlbumArt(track: MusicTrack?, modifier: Modifier = Modifier) {
    val thumbnail = rememberSystemThumbnail(track, HOME_COVER_THUMBNAIL_SIZE)
    if (thumbnail != null) {
        Image(
            bitmap = thumbnail,
            contentDescription = track?.title,
            contentScale = ContentScale.Crop,
            // 高清渲染：mipmap 三线性过滤，缩放/旋转均无锯齿与模糊
            filterQuality = FilterQuality.High,
            modifier = modifier.background(Color.Black),
        )
    } else {
        Box(
            // 首页背景恒为深色，占位背景固定用深色主题背景色，避免浅色主题下首帧浅色闪烁
            modifier = modifier.background(md_theme_dark_background),
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
