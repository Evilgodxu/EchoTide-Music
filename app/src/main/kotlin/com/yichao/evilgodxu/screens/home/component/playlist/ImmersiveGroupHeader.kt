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

// 头图高度占本页高度的比例与其上限：构图取播放页沉浸封面的「上重下轻」，尺寸随窗口计算，
// 各高宽比下都与下方列表分得住；上限挡住超长窗口（大屏、折叠展开）把列表挤成一条
internal const val IMMERSIVE_HEADER_HEIGHT_RATIO = 0.4f
internal val ImmersiveHeaderMaxHeight = 320.dp

/**
 * 分组详情页的沉浸头图：封面全宽置顶、下缘渐隐融入同源背景（与播放页同一处理），
 * 上端铺到状态栏后方；标题与副标题叠在头图下缘，页内不含任何按钮，整幅头图保持干净。
 *
 * 歌手页与专辑页共用这一份：两页的头图只在文案口径上不同，构图与尺寸随窗口走的规则必须一致，
 * 否则在两页之间下钻时头图会跳一个高度。
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
            // 换分组是整页跳转，不是沿播放队列的前后位移，换图没有可读的方向；
            // 同一分组内曲目变化只影响背景取色，头图本身按标识判定同图而不会重播过渡
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
 * 沉浸页的标签栏：选中项以高亮文字与下划线标示。
 *
 * 下划线取标签文字宽度，故标签须以最大固有宽度定宽——直接让下划线 fillMaxWidth
 * 会撑满整行（列宽本由最宽子项决定，而它就是那条下划线）。
 * 标签切换的跟手拖动与吸附交给各页自己的分页结算，本组件只负责显示与点击。
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
                    // 字重两种状态一致：选中态改字重会让标签宽度随之变化，下划线跟着一收一放
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
