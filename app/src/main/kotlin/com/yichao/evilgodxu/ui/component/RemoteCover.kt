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
 * 远程封面的显示入口：调用方只给地址与显示边长，下载、解码、缓存与淡入都收口在这里。
 *
 * [targetSize] 必须是控件的实际显示边长——它按 [coverThumbnailSize] 分档后同时充当请求尺寸与缓存键，
 * 因此同一档位在不同对话框之间共享同一张图，档位不同则各取各的。
 *
 * 首帧先查内存缓存并同步落图：滚回已看过的候选、重开对话框时不会先闪一次占位块。
 */
@Composable
internal fun RemoteCover(
    url: String?,
    contentDescription: String?,
    targetSize: Dp,
    modifier: Modifier = Modifier,
    // 取图期间的垫底色：默认透明，由调用方的容器表现——图片未到位时不该凭空多出一块底色。
    // 底色与容器不一致时才会显出补丁，故只在确有需要时传
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
        // 首帧已同步命中的不再取一次，只有此前确为空的那一次才等异步结果
        if (bitmap == null) bitmap = RemoteCoverLoader.load(context, pending, targetPx)
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    CoverTransition(
        bitmap = image,
        contentDescription = contentDescription,
        placeholderColor = placeholderColor,
        modifier = modifier,
        // 占位块留给调用方的父容器表现（多数调用点本就铺了底色与兜底图标）
        placeholderIconSize = null,
        fadeInMillis = fadeInMillis,
    )
}
