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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.theme.md_theme_dark_background
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

// 封面底部渐隐带占封面高度的比例：下缘由此比例起渐隐为透明，融入封面下边缘同色的背景衔接层。
// 该值须与 SongImmersiveBackground 的 COVER_EDGE_BLEND_FADE_RATIO 保持一致：
// 两侧等长才能在封面底边处同色相接，改一处而漏另一处会重新出现接缝
private const val BOTTOM_FADE_FRACTION = 0.3f

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

// 下边缘渐隐蒙层：与跑马灯同款 DstIn 处理，封面下缘渐隐为透明，透出与封面下边缘同色的背景衔接层。
// 透明度按平滑曲线采样多段：单段线性渐隐会在折点处留下一条可见的色阶带
private fun Modifier.bottomFadeMask(): Modifier = drawWithCache {
    val fadeStart = 1f - BOTTOM_FADE_FRACTION
    val brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.Black,
            fadeStart to Color.Black,
            fadeStart + BOTTOM_FADE_FRACTION * 0.2f to Color.Black.copy(alpha = 0.95f),
            fadeStart + BOTTOM_FADE_FRACTION * 0.4f to Color.Black.copy(alpha = 0.79f),
            fadeStart + BOTTOM_FADE_FRACTION * 0.6f to Color.Black.copy(alpha = 0.55f),
            fadeStart + BOTTOM_FADE_FRACTION * 0.8f to Color.Black.copy(alpha = 0.21f),
            1f to Color.Transparent,
        ),
    )
    onDrawWithContent {
        drawIntoCanvas { canvas -> canvas.saveLayer(Rect(Offset.Zero, size), Paint()) }
        drawContent()
        drawRect(brush = brush, size = size, blendMode = BlendMode.DstIn)
        drawIntoCanvas { canvas -> canvas.restore() }
    }
}
