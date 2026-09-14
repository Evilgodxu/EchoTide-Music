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

// 封面底部渐隐区占封面高度的比例
private const val BOTTOM_FADE_FRACTION = 0.2f

// 首页沉浸式封面：全宽置顶，上下边缘渐隐为透明融入真实渲染背景
@Composable
internal fun HomeImmersiveCover(
    track: MusicTrack?,
    topFraction: Float,
    modifier: Modifier = Modifier,
) {
    HomeAlbumArt(
        track = track,
        modifier = modifier.verticalFadeMask(
            topFraction.coerceIn(0f, 1f - BOTTOM_FADE_FRACTION),
            BOTTOM_FADE_FRACTION,
        ),
    )
}

// 上下边缘渐隐蒙层：与跑马灯同款 DstIn 处理，封面顶部与底部渐变消失透出背景
private fun Modifier.verticalFadeMask(topFraction: Float, bottomFraction: Float): Modifier = drawWithCache {
    val brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.Transparent,
            topFraction to Color.Black,
            1f - bottomFraction to Color.Black,
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
