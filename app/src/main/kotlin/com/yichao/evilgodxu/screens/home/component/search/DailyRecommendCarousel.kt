package com.yichao.evilgodxu.screens.home.component.search

import androidx.compose.animation.core.tween
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.ui.component.MarqueeText
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.delay

// 无限循环轮播的页码范围：起手落在中段，左右均可无限滑动而无需在边界处回弹
private const val LOOP_PAGE_COUNT = Int.MAX_VALUE / 2

// 自动轮播的停留时长：短于此值会在读完歌名与歌手前就把当前项划走
private const val AUTO_SCROLL_INTERVAL_MS = 4000L

// 自动翻页的滑动时长：固定时长比弹簧更好预期，也不会与手动跟手滑动的动势混同
private const val AUTO_SCROLL_DURATION_MS = 450

/**
 * 每日推荐轮播：左图右文展示榜单候选按本地偏好挑出的歌曲，下方分页点指示当前项。
 *
 * 项数足够时自动循环滚动。用户拖动期间不与之抢手势；[visible] 为假时不推进 ——
 * 页面容器把所有页常驻合成树，不可见时仍会照常合成。
 *
 * 点击交由调用方处理：推荐项与搜索结果同属在线歌曲，播放前同样需要用户选择音质。
 * 项右侧的心碎按钮把该曲目交由调用方拉黑，被拉黑的曲目不会再次出现在推荐里。
 *
 * @param loading 排序计算中
 * @param refreshing 候选池联网更新中
 * @param visible 本页是否在前台可见
 * @param onBlacklist 拉黑该项，交由调用方写入黑名单
 */
@Composable
internal fun DailyRecommendCarousel(
    songs: List<NeteaseSongSearchResult>,
    loading: Boolean,
    refreshing: Boolean,
    visible: Boolean,
    onSongClick: (NeteaseSongSearchResult) -> Unit,
    onBlacklist: (NeteaseSongSearchResult) -> Unit,
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
                enabled = !loading && !refreshing,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Refresh,
                    contentDescription = null,
                    // 禁用两种进行中：排序计算中重复触发只会把在途任务作废重来；
                    // 候选池更新中手动刷新不会打断更新，但算完仍要等同一轮更新，白等一次
                    tint = Color.White.copy(alpha = if (loading || refreshing) 0.3f else 1f),
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
                // 自动循环滚动：停留 AUTO_SCROLL_INTERVAL_MS 后推进一项。
                // 用户正在拖动（含惯性滑动）时跳过本次推进，不抢手势；只有一首时也不推进 ——
                // 循环推进改变的只是页码，展示内容始终是同一首
                LaunchedEffect(pagerState, songs.size, visible) {
                    if (!visible || songs.size < 2) return@LaunchedEffect
                    while (true) {
                        delay(AUTO_SCROLL_INTERVAL_MS)
                        if (!pagerState.isScrollInProgress) {
                            pagerState.animateScrollToPage(
                                page = pagerState.currentPage + 1,
                                animationSpec = tween(AUTO_SCROLL_DURATION_MS),
                            )
                        }
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxWidth(),
                    pageSpacing = 8.dp,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { page ->
                    RecommendCard(
                        song = songs[page % songs.size],
                        onClick = onSongClick,
                        onBlacklist = onBlacklist,
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

// 轮播项：左侧封面，中间两行文案（歌名 / 歌手），末端心碎按钮
@Composable
private fun RecommendCard(
    song: NeteaseSongSearchResult,
    onClick: (NeteaseSongSearchResult) -> Unit,
    onBlacklist: (NeteaseSongSearchResult) -> Unit,
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
            // 歌名不折行：按钮占去右侧宽度后单行能容纳的字数更少，超出部分靠来回滚动完整展示
            MarqueeText(
                text = song.title,
                color = Color.White,
                style = TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Text(
                text = song.artist,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
                lineHeight = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 心碎：把该曲目交给调用方拉黑，点击被按钮自身消费，不会同时触发行点击的播放流程
        IconButton(
            onClick = { onBlacklist(song) },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = AppIcons.HeartBroken,
                contentDescription = stringResource(R.string.music_panel_daily_recommend_blacklist),
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// 加载中与空态：占用与轮播项一致的高度，避免状态切换时搜索框位置跳动。
// 首次生成要等整池歌词拉完，用圆环给出持续可见的进行中反馈，而非一行静态文案
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
        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.9f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.music_panel_daily_recommend_loading),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
            }
        } else {
            Text(
                text = stringResource(R.string.music_panel_daily_recommend_empty),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        }
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