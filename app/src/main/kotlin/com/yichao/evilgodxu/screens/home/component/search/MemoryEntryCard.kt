package com.yichao.evilgodxu.screens.home.component.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons

// 卡片高度：定高而非自适应，未解锁时的四行短诗与已解锁时的两行副标题切换时不会引起下方搜索框跳动
private val CARD_HEIGHT = 104.dp

/**
 * 回忆模式入口卡片。
 *
 * 未达启用门槛时以一段诗代替副标题说明「还没攒够」，点击不再另作提示 —— 说明已经写在卡片上，
 * 再弹一次是重复；数据攒够后卡片自行变为可进入。
 *
 * 心动模式不在此处给入口：它改的是「每首放哪一段」，是播放模式而非一屏内容，
 * 入口与单曲循环、随机并列在播放控制栏的模式切换里，单独在此再放一个开关只会造成两处状态源。
 *
 * @param unlocked 累计计数与收藏是否已达启用门槛
 * @param loading 本次名次是否正在计算
 * @param songCount 本次可播的曲目数，0 表示尚未排出或已排空
 */
@Composable
internal fun MemoryEntryCard(
    unlocked: Boolean,
    loading: Boolean,
    songCount: Int,
    onEnter: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(CARD_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .then(if (unlocked) Modifier.clickable { onEnter() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.AutoStories,
                contentDescription = null,
                tint = Color.White.copy(alpha = if (unlocked) 0.95f else 0.55f),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.music_panel_memory_mode_title),
                color = Color.White.copy(alpha = if (unlocked) 1f else 0.6f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (loading) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.9f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
            } else if (unlocked && songCount > 0) {
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(22.dp),
                ) {
                    Icon(
                        imageVector = AppIcons.Refresh,
                        contentDescription = stringResource(R.string.music_panel_memory_mode_refresh),
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
        if (unlocked) {
            Text(
                text = stringResource(
                    if (songCount > 0) {
                        R.string.music_panel_memory_mode_subtitle
                    } else {
                        R.string.music_panel_memory_mode_loading
                    }
                ),
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = stringResource(R.string.music_panel_memory_mode_poem),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
