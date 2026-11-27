package com.yichao.evilgodxu.ui.component.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.ui.component.coverThumbnailSize
import com.yichao.evilgodxu.ui.component.player.AlbumArt
import kotlinx.coroutines.isActive

// 内圈封面占光碟直径 0.85
private const val DISC_COVER_INSET = 0.85f

@Composable
internal fun DiscArt(
    track: MusicTrack?,
    isPlaying: Boolean,
    discSize: Dp,
    modifier: Modifier = Modifier,
    coverArt: @Composable (MusicTrack?) -> Unit = { target ->
        // 按实际渲染尺寸换算封面档位，换图淡入
        AlbumArt(
            track = target,
            modifier = Modifier.fillMaxSize(),
            thumbnailSize = coverThumbnailSize(LocalDensity.current, discSize * DISC_COVER_INSET),
            animated = true,
        )
    },
) {
    // 角度跨曲累积，暂停后续转不回卷
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(isPlaying, track?.id) {
        if (isPlaying) {
            while (isActive) {
                rotation.animateTo(
                    targetValue = rotation.value + 360f,
                    animationSpec = tween(durationMillis = 12_000, easing = LinearEasing)
                )
            }
        }
    }
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation.value }
                .clip(CircleShape)
        ) {
            // 封面居中，外圈留透明材质
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(DISC_COVER_INSET)
                    .clip(CircleShape)
            ) {
                coverArt(track)
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val center = this.center
                // 浅色外环紧贴封面，宽为半径 9%
                val ringWidth = r * 0.09f
                drawCircle(
                    color = Color.White.copy(alpha = 0.22f),
                    radius = r * 0.85f + ringWidth / 2f,
                    center = center,
                    style = Stroke(width = ringWidth)
                )
            }
        }
    }
}
