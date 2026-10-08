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

// 封面换图（略缩图升清、换曲换封面、占位块出场）的淡入时长。
// 取 500ms 而非常规元素过渡的 300ms：这里叠的是两次取图之间的清晰度差与整幅换图，
// 再快就读成「跳」而不是「化」。首页大封面与音乐面板/迷你播放器光碟共用同一节拍
internal const val COVER_FADE_IN_MS = 500

/**
 * 封面显示与换图过渡：底层常驻 + 上层淡入。
 *
 * 不能用交叠淡出——它要求两层同时半透明，合成结果在过渡中段只剩约七成不透明度，
 * 底色会透上来形成一次亮度塌陷，表现为封面整体暗一下再亮回来。
 * 这里让底层那张始终保持完全不透明铺满容器，新到的一张叠在其上、alpha 由 0 动画到 1，
 * 过渡全程至少有一层以实色盖住底色；上层完全显示后才撤下底层，回到单层驻留。
 *
 * [bitmap] 为空即无封面可显示，两层的空缺都由 [placeholderColor] 的占位块补上——
 * 占位块同样以实色垫底与淡入，故「换曲 → 该曲确认无封面」的落位过程也不塌陷亮度。
 *
 * [animated] 为假时不做过渡、直接替换，并跳过两层的全部开销：
 * 用于封面不随播放变更的场景（3D 轮播的格位、封面替换对话框的对比图）。
 */
@Composable
internal fun CoverTransition(
    bitmap: ImageBitmap?,
    contentDescription: String?,
    placeholderColor: Color,
    modifier: Modifier = Modifier,
    placeholderIconSize: Dp = 24.dp,
    animated: Boolean = true,
) {
    // 底层内容：当前已完全显示的一张
    var base by remember { mutableStateOf(bitmap) }
    // 上层内容：正在淡入的一张。它可以是空（占位块淡入），故另设 fading 标记在场与否，
    // 不能以「位图是否为空」判定，否则占位块永远进不了上层
    var incoming by remember { mutableStateOf<ImageBitmap?>(null) }
    var fading by remember { mutableStateOf(false) }
    val fade = remember { Animatable(1f) }

    LaunchedEffect(bitmap, animated) {
        // 不做过渡、或结果与正在显示的是同一张图（首帧、取图命中同一实例）时直接回到单层，
        // 不摆一次空转的动画
        if (!animated || bitmap === base) {
            base = bitmap
            incoming = null
            fading = false
            fade.snapTo(1f)
            return@LaunchedEffect
        }
        // 先挂到 0f 再起动画：起手若沿用上一轮推进过的值，会先以半透明亮一帧再回落
        incoming = bitmap
        fading = true
        fade.snapTo(0f)
        fade.animateTo(1f, tween(durationMillis = COVER_FADE_IN_MS))
        // 上层完全显示才落成底层；三处赋值之间没有挂起点，同一帧内一并生效，替换处不会露出空档
        base = bitmap
        incoming = null
        fading = false
        fade.snapTo(1f)
    }

    Box(modifier = modifier) {
        CoverLayer(
            bitmap = base,
            contentDescription = contentDescription,
            placeholderColor = placeholderColor,
            placeholderIconSize = placeholderIconSize,
        )
        if (fading) {
            CoverLayer(
                bitmap = incoming,
                contentDescription = contentDescription,
                placeholderColor = placeholderColor,
                placeholderIconSize = placeholderIconSize,
                // alpha 只在绘制阶段读取，逐帧推进不触发重组
                modifier = Modifier.graphicsLayer { alpha = fade.value },
            )
        }
    }
}

// 单层封面内容：有位图即整幅铺满，无位图即占位块。
// 底层与上层共用这一份实现，两层的构图、缩放与过滤口径因此完全一致，过渡期间不会错位或清晰度突变
@Composable
private fun CoverLayer(
    bitmap: ImageBitmap?,
    contentDescription: String?,
    placeholderColor: Color,
    placeholderIconSize: Dp,
    modifier: Modifier = Modifier,
) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            // 高清渲染：mipmap 三线性过滤，缩放/旋转均无锯齿与模糊
            filterQuality = FilterQuality.High,
            modifier = modifier
                .fillMaxSize()
                // 图片未铺满的极短间隙不露容器底色
                .background(Color.Black),
        )
    } else {
        Box(
            // 占位块颜色由调用方给定：首页背景恒为深色，取固定的深色主题背景色而非随主题变化的 surface
            modifier = modifier
                .fillMaxSize()
                .background(placeholderColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(placeholderIconSize),
            )
        }
    }
}
