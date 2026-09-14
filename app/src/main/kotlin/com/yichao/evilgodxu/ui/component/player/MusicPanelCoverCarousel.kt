package com.yichao.evilgodxu.ui.component.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.ui.component.SongGradientBackground
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// 横屏沉浸式 3D 封面轮播：中心封面 + 左右各 3 首共 7 首同屏。
// 所有封面基于连续的"中心索引"推导位置/倾斜/缩放/透明度，滑动与点击共用同一弹簧，
// 切换时封面平滑移动并淡入淡出；点击居中封面播放，点击空白处收起。
private const val VISIBLE_PER_SIDE = 3

@Composable
internal fun CoverCarouselOverlay(
    playlist: List<MusicTrack>,
    currentIndex: Int,
    onTrackSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    if (playlist.isEmpty()) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val startIndex = currentIndex.coerceIn(0, playlist.size - 1)
    val lastIndex = playlist.size - 1
    var selectedIndex by remember { mutableIntStateOf(startIndex) }
    // 连续的"中心索引"：滑动时叠加临时偏移，吸附/点击时弹簧过渡到整数位
    val centerIndex = remember { Animatable(startIndex.toFloat()) }
    var dragShift by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        // 中心封面尺寸与横屏播放器封面一致：左栏宽度 × 封面占比，高度受限于时按高度收缩
        val landscapeCoverWidth = maxWidth.value * 0.5f * 0.68f * 0.7f
        val coverSize = minOf(landscapeCoverWidth, maxHeight.value * 0.55f).dp
        // 相邻封面中心距小于封面尺寸，让左右两侧封面在视觉上部分重叠，形成前后交错
        val spacingPx = with(density) { (coverSize * 0.72f).toPx() }
        // 吸附动效：低刚度弹簧，滑动释放与点击选取共用同一自然过渡
        val settleSpec = spring<Float>(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessLow,
        )

        // 受动画驱动的连续中心位：拖动增量实时叠加，动画期间由 centerIndex 平滑推进
        val rendered = centerIndex.value + dragShift

        // 背景：沿用首页封面色渐变处理，实时渲染为当前居中的歌曲；横屏系统栏隐藏，跳过顶部压暗
        SongGradientBackground(
            track = playlist[rendered.roundToInt().coerceIn(0, lastIndex)],
            darkenStatusBarArea = false,
        )

        // 交互层：左右滑动切换、点击封面选取，点击空白处收起
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(playlist.size) {
                    detectDragGestures(
                        onDragStart = { scope.launch { centerIndex.stop() } },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragShift -= dragAmount.x / spacingPx
                            // 限制在播放列表范围内，边缘不越界
                            val clamped = (centerIndex.value + dragShift).coerceIn(0f, lastIndex.toFloat())
                            dragShift = clamped - centerIndex.value
                        },
                        onDragEnd = {
                            scope.launch {
                                val now = (centerIndex.value + dragShift).coerceIn(0f, lastIndex.toFloat())
                                val target = now.roundToInt()
                                // 将临时偏移并入中心索引，再弹簧过渡到整数位，实现自然淡入淡出
                                centerIndex.snapTo(now)
                                dragShift = 0f
                                centerIndex.animateTo(target.toFloat(), animationSpec = settleSpec)
                                selectedIndex = target
                            }
                        }
                    )
                }
                .pointerInput(playlist.size) {
                    detectTapGestures { onDismiss() }
                },
            contentAlignment = Alignment.Center
        ) {
            // 渲染中心附近封面（含动画过渡进入的一首余量）
            val windowCenter = rendered.roundToInt().coerceIn(0, lastIndex)
            val windowStart = (windowCenter - VISIBLE_PER_SIDE - 1).coerceAtLeast(0)
            val windowEnd = (windowCenter + VISIBLE_PER_SIDE + 1).coerceAtMost(lastIndex)
            for (i in windowStart..windowEnd) {
                // 以歌曲 id 作为稳定 key，避免拖动/点击切换时窗口偏移导致封面重建而闪烁
                key(playlist[i].id) {
                    val dist = i - rendered
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((dist * spacingPx).roundToInt(), 0) }
                            .size(coverSize)
                            // 越靠近中心的封面层级越高，左右的封面被居中封面压住
                            .zIndex(-abs(dist))
                            .graphicsLayer {
                                // 前端封面几乎平放，远端封面旋转适度收敛，避免视角过陡
                                rotationY = (-dist * 10f).coerceIn(-50f, 50f)
                                scaleX = (1f - abs(dist) * 0.12f).coerceAtLeast(0.7f)
                                scaleY = scaleX
                                alpha = (1f - abs(dist) * 0.16f).coerceIn(0.5f, 1f)
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    if (i == selectedIndex) {
                                        onTrackSelected(i)
                                    } else {
                                        // 点击侧边封面：弹簧过渡到其中，封面平滑移动并淡入淡出
                                        scope.launch {
                                            centerIndex.stop()
                                            centerIndex.animateTo(i.toFloat(), animationSpec = settleSpec)
                                            dragShift = 0f
                                            selectedIndex = i
                                        }
                                    }
                                }
                            )
                    ) {
                        AlbumArt(playlist[i], Modifier.fillMaxSize())
                    }
                }
            }
        }

        // 居中封面的正下方居中显示歌曲标题与艺术家
        val track = playlist[rendered.roundToInt().coerceIn(0, lastIndex)]
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = track.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = track.artist,
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
    }
}
