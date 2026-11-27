package com.yichao.evilgodxu.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.yichao.evilgodxu.data.music.remote.RemoteCoverLoader

/**
 * 远程封面入口：下载、解码、缓存与淡入都收口在此
 */
@Composable
internal fun RemoteCover(
    url: String?,
    contentDescription: String?,
    targetSize: Dp,
    modifier: Modifier = Modifier,
    // 取图期间垫底色，默认透明
    placeholderColor: Color = Color.Transparent,
    fadeInMillis: Int = COVER_FADE_IN_MS,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val targetPx = remember(targetSize, density) { coverThumbnailSize(density, targetSize) }
    var bitmap by remember(url, targetPx) {
        mutableStateOf(url?.let { RemoteCoverLoader.peek(it, targetPx) })
    }
    LaunchedEffect(url, targetPx) {
        val pending = url ?: return@LaunchedEffect
        // 首帧已命中的不再重复取图
        if (bitmap == null) bitmap = RemoteCoverLoader.load(context, pending, targetPx)
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    CoverTransition(
        bitmap = image,
        contentDescription = contentDescription,
        placeholderColor = placeholderColor,
        modifier = modifier,
        // 占位交由父容器表现
        placeholderIconSize = null,
        fadeInMillis = fadeInMillis,
    )
}
