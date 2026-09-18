package com.yichao.evilgodxu.screens.home.component.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.ui.icons.AppIcons

// 无限循环轮播的页码范围：起手落在中段，左右均可无限滑动而无需在边界处回弹
private const val LOOP_PAGE_COUNT = Int.MAX_VALUE / 2

/**
 * 每日推荐轮播：左图右文展示榜单候选按本地偏好挑出的歌曲，下方分页点指示当前项。
 *
 * 点击交由调用方处理：推荐项与搜索结果同属在线歌曲，播放前同样需要用户选择音质。
 */
@Composable
internal fun DailyRecommendCarousel(
    songs: List<NeteaseSongSearchResult>,
    loading: Boolean,
    onSongClick: (NeteaseSongSearchResult) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.music_panel_daily_recommend_title),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            IconButton(
                onClick = onRefresh,
                enabled = !loading,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Refresh,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = if (loading) 0.5f else 1f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        when {
            songs.isEmpty() -> CarouselPlaceholder(loading)
            else -> {
                // 起始页对齐到第一首：页码对歌曲数取模决定展示内容，偏移量用同一取模结果回正
                val initialPage = remember(songs.size) {
                    LOOP_PAGE_COUNT - LOOP_PAGE_COUNT % songs.size
                }
                val pagerState = rememberPagerState(initialPage = initialPage) { Int.MAX_VALUE }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    pageSpacing = 8.dp,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { page ->
                    RecommendCard(
                        song = songs[page % songs.size],
                        onClick = onSongClick,
                    )
                }
                Spacer(Modifier.height(6.dp))
                PageIndicator(
                    count = songs.size,
                    current = pagerState.currentPage % songs.size,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

// 轮播项：左侧封面，右侧两行文案（歌名 / 歌手）
@Composable
private fun RecommendCard(
    song: NeteaseSongSearchResult,
    onClick: (NeteaseSongSearchResult) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable { onClick(song) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val coverModel = (song.coverThumbUrl ?: song.coverUrl)?.takeIf { it.isNotBlank() }
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            if (coverModel != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverModel)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build(),
                    contentDescription = song.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Icon(
                    imageVector = AppIcons.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// 加载中与空态：占用与轮播项一致的高度，避免状态切换时搜索框位置跳动
@Composable
private fun CarouselPlaceholder(loading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(
                if (loading) R.string.music_panel_daily_recommend_loading
                else R.string.music_panel_daily_recommend_empty
            ),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
        )
    }
}

// 分页指示点：当前项拉长并与其余项区分
@Composable
private fun PageIndicator(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .width(if (active) 12.dp else 4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (active) 0.9f else 0.35f)),
            )
        }
    }
}