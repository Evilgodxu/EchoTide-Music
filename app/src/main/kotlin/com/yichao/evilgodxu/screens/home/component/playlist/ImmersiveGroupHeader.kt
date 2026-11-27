package com.yichao.evilgodxu.screens.home.component.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.TrackSwitchKind
import com.yichao.evilgodxu.screens.home.component.player.HomeImmersiveCover

// 头高 40%，上限 320dp，避免大屏挤压列表
internal const val IMMERSIVE_HEADER_HEIGHT_RATIO = 0.4f
internal val ImmersiveHeaderMaxHeight = 320.dp

/**
 * 分组详情页沉浸头图：封面全宽置顶、下缘渐隐，标题叠在下缘。
 * 歌手页与专辑页共用，保证两页头图高度一致。
 */
@Composable
internal fun ImmersiveGroupHeader(
    track: MusicTrack?,
    title: String,
    subtitle: String,
    height: Dp,
) {
    Box(modifier = Modifier.fillMaxWidth().height(height)) {
        HomeImmersiveCover(
            track = track,
            // 换分组是整页跳转，无方向；同图不重播过渡
            kind = TrackSwitchKind.Select,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 沉浸页标签栏：选中项高亮加下划线。
 * 标签须按最大固有宽度定宽，下划线才不会撑满整行。
 */
@Composable
internal fun ImmersiveTabBar(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (index: Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelect(index) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    // 字重不变，避免标签宽度与下划线抖动
                    text = label,
                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            color = if (isSelected) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(1.dp),
                        ),
                )
            }
        }
    }
}
