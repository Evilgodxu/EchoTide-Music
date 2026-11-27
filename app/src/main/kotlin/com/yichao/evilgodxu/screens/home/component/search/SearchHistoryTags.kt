package com.yichao.evilgodxu.screens.home.component.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.panel.performSearch
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.launch

// 标签最大宽 180dp，超长关键词截断
private val TagMaxWidth = 180.dp

// 标签 10dp 圆角，12% 白填充、18% 白描边。
// 用半透明白而非底色，任意渐变底色都成立。
private val TagShape = RoundedCornerShape(10.dp)
private val TagHeight = 32.dp
private val TagFill = Color.White.copy(alpha = 0.12f)
private val TagBorder = Color.White.copy(alpha = 0.18f)
private val TagTextColor = Color.White.copy(alpha = 0.92f)
private val TagCloseColor = Color.White.copy(alpha = 0.6f)

// 渐隐 40dp，仅在有横滑余量时绘制
private val TagFadeWidth = 40.dp

// 认领标签行的横向余量与余速，避免拖标签时翻页；纵向放行
private val TagScrollBlocker = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = Offset(available.x, 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(available.x, 0f)
}

// 搜索历史：单行横滑标签，历史增减不影响区块高度
@Composable
internal fun SearchHistoryTags(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 上边距取区块间距；下 4dp 是标签贴标题
                .padding(start = 12.dp, end = 12.dp, top = SearchPanelSectionGap, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchSectionLabel(
                text = stringResource(R.string.music_panel_search_history_recent),
            )
            // 与单条删除同面板，图标需配文字
            SearchPanelAction(
                icon = AppIcons.Delete,
                label = stringResource(R.string.music_panel_search_history_clear_action),
                onClick = { playbackState.clearSearchHistory() },
            )
        }
        val listState = rememberLazyListState()
        val fadeWidthPx = with(LocalDensity.current) { TagFadeWidth.toPx() }
        // 到尽头的一侧不渐隐，否则白压暗边上的标签
        val fadeLeading = listState.canScrollBackward
        val fadeTrailing = listState.canScrollForward
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .nestedScroll(TagScrollBlocker)
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // DstOut 抹透明度，不叠底色渐变，避免留色带
                        if (fadeLeading) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Black, Color.Transparent),
                                    startX = 0f,
                                    endX = fadeWidthPx,
                                ),
                                blendMode = BlendMode.DstOut,
                            )
                        }
                        if (fadeTrailing) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color.Transparent, Color.Black),
                                    startX = size.width - fadeWidthPx,
                                    endX = size.width,
                                ),
                                blendMode = BlendMode.DstOut,
                            )
                        }
                    },
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(playbackState.searchHistory, key = { it }) { query ->
                    SearchHistoryTag(
                        query = query,
                        onClick = {
                            playbackState.setSearchQuery(query)
                            scope.launch { performSearch(playbackState, context) }
                        },
                        onRemove = { playbackState.removeSearchHistory(query) },
                    )
                }
            }
        }
    }
}

// 点正文检索，点右侧关闭只删除该条
@Composable
private fun SearchHistoryTag(
    query: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .widthIn(max = TagMaxWidth)
            .height(TagHeight)
            .clip(TagShape)
            .background(TagFill)
            .border(width = 1.dp, color = TagBorder, shape = TagShape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = query,
            color = TagTextColor,
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // fill=false 让短词按内容收窄
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 12.dp),
        )
        // 用 Box 避免 IconButton 撑高标签
        Box(
            modifier = Modifier
                .padding(start = 2.dp, end = 4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = AppIcons.Close,
                contentDescription = stringResource(R.string.music_panel_search_history_delete),
                tint = TagCloseColor,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
