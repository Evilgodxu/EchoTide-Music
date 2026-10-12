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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.panel.performSearch
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.launch

// 单个历史标签的最大宽度：超长关键词截断，避免一枚标签独占整行、把其余历史挤出视野
private val TagMaxWidth = 180.dp

// 标签外形：10dp 微圆角 + 12% 白填充 + 18% 白描边。
// 半透明取白而非取底色：首页底色是随封面变化的沉浸渐变，只有半透明白能在任意底色上成立
private val TagShape = RoundedCornerShape(10.dp)
private val TagHeight = 32.dp
private val TagFill = Color.White.copy(alpha = 0.12f)
private val TagBorder = Color.White.copy(alpha = 0.18f)
private val TagTextColor = Color.White.copy(alpha = 0.92f)
private val TagCloseColor = Color.White.copy(alpha = 0.6f)

// 两侧渐隐宽度：作为「还能往该方向横滑」的提示，仅在对应方向确有余量时绘制
private val TagFadeWidth = 40.dp

// 标签行横滑与翻页互斥。
// 标签行自身滚到尽头后，剩余位移与剩余速度默认会顺着嵌套滚动上交给父级 Pager，于是拖着标签
// 会翻页。这里把余量与余速全部认领，标签行上的横滑就只滚标签、绝不动页面。
// 只认领横向分量：纵向位移与本行无关，拦下来只会挡住上层的纵向手势
private val TagScrollBlocker = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = Offset(available.x, 0f)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(available.x, 0f)
}

// 搜索历史：单行横向滚动的半透明标签流。
// 历史条数只改变横向内容长度、不改变本区块高度，结果区的位置不随历史增减而跳动
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
                // 上边距即搜索条与本区块的间距，取面板统一的区块间距（下边距 4dp 是标签紧跟标题的距离，不属区块间距）
                .padding(start = 12.dp, end = 12.dp, top = SearchPanelSectionGap, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.music_panel_search_history_recent),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            // 此处与单条删除同处一个面板，仅凭图标无法区分清空与删除
            SearchPanelAction(
                icon = AppIcons.Delete,
                label = stringResource(R.string.music_panel_search_history_clear_action),
                onClick = { playbackState.clearSearchHistory() },
            )
        }
        val listState = rememberLazyListState()
        val fadeWidthPx = with(LocalDensity.current) { TagFadeWidth.toPx() }
        // 已滑到尽头的一侧不再渐隐：那一侧没有内容可提示，渐隐只会凭空压暗边上的标签
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
                        // 用 DstOut 直接抹掉内容自身的透明度，而不是叠一层底色渐变：
                        // 面板底色是沉浸渐变，任何固定颜色的渐变都会在标签下方留下一条色带
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

// 单枚历史标签：点正文发起检索，点右侧关闭图标只删除该条
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
            // fill = false：短关键词按内容收窄，标签宽度贴合文字而不是撑到上限
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 12.dp),
        )
        // 关闭用 Box 而非 IconButton：IconButton 会强制 48dp 最小交互尺寸，把标签整体撑高
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
