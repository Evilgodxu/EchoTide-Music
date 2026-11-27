package com.yichao.evilgodxu.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.ui.icons.AppIcons

// 封面淡入 500ms；列表行另取更短档
internal const val COVER_FADE_IN_MS = 500

/**
 * 封面换图过渡：底层常驻不透明，新图在上层淡入。
 * 不能用交叠淡出，否则中段亮度塌陷。
 * bitmap 为空时用占位色、低清底图或图标兜底。
 */
@Composable
internal fun CoverTransition(
    bitmap: ImageBitmap?,
    contentDescription: String?,
    placeholderColor: Color,
    modifier: Modifier = Modifier,
    // 低清底图，正图未就位时垫底
    baseBitmap: ImageBitmap? = null,
    // 兜底图标尺寸，空即不叠
    placeholderIconSize: Dp? = 24.dp,
    animated: Boolean = true,
    fadeInMillis: Int = COVER_FADE_IN_MS,
) {
    // 底层：已完全显示的一张
    var base by remember { mutableStateOf(bitmap) }
    // 上层正在淡入的一张；fading 标记是否在场
    var incoming by remember { mutableStateOf<ImageBitmap?>(null) }
    var fading by remember { mutableStateOf(false) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(bitmap, animated, fadeInMillis) {
        // 同一张图或不做过渡时直接回单层
        if (!animated || bitmap === base) {
            base = bitmap
            incoming = null
            fading = false
            fade.snapTo(1f)
            return@LaunchedEffect
        }
        // 先归 0 再动画，避免半透明亮一帧
        incoming = bitmap
        fading = true
        fade.snapTo(0f)
        fade.animateTo(1f, tween(durationMillis = fadeInMillis))
        // 上层落成底层后再清空，同帧生效不露空档
        base = bitmap
        incoming = null
        fading = false
        fade.snapTo(1f)
    }

    Box(modifier = modifier) {
        CoverLayer(
            bitmap = base,
            baseBitmap = baseBitmap,
            contentDescription = contentDescription,
            placeholderColor = placeholderColor,
            placeholderIconSize = placeholderIconSize,
        )
        if (fading) {
            CoverLayer(
                bitmap = incoming,
                baseBitmap = baseBitmap,
                contentDescription = contentDescription,
                placeholderColor = placeholderColor,
                placeholderIconSize = placeholderIconSize,
                // alpha 仅在绘制阶段读取
                modifier = Modifier.graphicsLayer { alpha = fade.value },
            )
        }
    }
}

// 单层封面：有位图即铺满，否则占位
@Composable
private fun CoverLayer(
    bitmap: ImageBitmap?,
    baseBitmap: ImageBitmap?,
    contentDescription: String?,
    placeholderColor: Color,
    placeholderIconSize: Dp?,
    modifier: Modifier = Modifier,
) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            // mipmap 三线性过滤，缩放无锯齿
            filterQuality = FilterQuality.High,
            modifier = modifier
                .fillMaxSize()
                // 间隙不露容器底色
                .background(Color.Black),
        )
    } else {
        Box(
            // 占位色由调用方给定
            modifier = modifier
                .fillMaxSize()
                .background(placeholderColor),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // 与正图同构图，升清不位移
                baseBitmap != null -> Image(
                    bitmap = baseBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )

                placeholderIconSize != null -> Icon(
                    imageVector = AppIcons.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(placeholderIconSize),
                )
            }
        }
    }
}
