package com.yichao.evilgodxu.ui.component.dialog

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.panel.loadMoreSearchResults
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.LIST_COVER_FADE_IN_MS
import com.yichao.evilgodxu.ui.component.RemoteCover
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.delay

@Composable
internal fun SearchResultRow(
    result: NeteaseSongSearchResult,
    onClick: () -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val coverModel = (result.coverThumbUrl ?: result.coverUrl)?.takeIf { it.isNotBlank() }
            if (coverModel != null) {
                RemoteCover(
                    url = coverModel,
                    contentDescription = result.title,
                    targetSize = 28.dp,
                    // 与列表行同档短淡入
                    fadeInMillis = LIST_COVER_FADE_IN_MS,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = AppIcons.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = result.title,
                color = titleColor,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = result.artist,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// 提示行完全展开的上拉距离
private val SEARCH_LOAD_ROW_FULL_PULL_DP = 60.dp

// 提示行展开后的行高
private val SEARCH_LOAD_ROW_HEIGHT = 40.dp

// 最短展开 500ms，避免秒回像未触发
private const val MIN_LOAD_ROW_MS = 500L

// 列表到底上拉展开提示行，松手加载下一页
@Composable
internal fun SearchResultsLazyList(
    playbackState: MusicPlaybackState,
    context: Context,
    onResultClick: (NeteaseSongSearchResult) -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val listState = rememberLazyListState()
    // 上游兜底去重，避免 LazyColumn key 冲突
    val uniqueResults = remember(playbackState.searchResults) {
        playbackState.searchResults.distinctBy { it.source to it.id }
    }
    // 到底后的过拉量：取自指针位移，列表已不滚动
    // 进入底部时记锚点，之后的净上移即过拉
    var pullDistance by remember { mutableFloatStateOf(0f) }
    // 本次加载中；本地分页不置 isLoadingMore
    var loadInProgress by remember { mutableStateOf(false) }
    // 松手自增以请求加载，不压在指针回调里
    var loadRequest by remember { mutableIntStateOf(0) }
    val fullPullPx = with(LocalDensity.current) { SEARCH_LOAD_ROW_FULL_PULL_DP.toPx() }
    // 抬手结算：底部上拉即加载，不设距离门槛
    // 但须仍在底部，惯性离开后的残留不计
    fun settlePull() {
        // 加载中抬手不重复请求
        val shouldLoad = pullDistance > 0f && !loadInProgress && !listState.canScrollForward &&
            playbackState.hasMoreSearchResults &&
            !playbackState.isSearching && playbackState.searchResults.isNotEmpty()
        if (shouldLoad) {
            loadInProgress = true
            loadRequest++
        }
        pullDistance = 0f
    }
    // 补足最短展开时长，秒回也有反馈
    LaunchedEffect(loadRequest) {
        if (loadRequest == 0) return@LaunchedEffect
        val startedAt = SystemClock.elapsedRealtime()
        try {
            loadMoreSearchResults(playbackState, context)
            val remaining = MIN_LOAD_ROW_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (remaining > 0) delay(remaining)
        } finally {
            loadInProgress = false
        }
    }
    // 展开比例：跟手、加载中全开、无更多收起
    val expandFraction by animateFloatAsState(
        targetValue = when {
            loadInProgress -> 1f
            !playbackState.hasMoreSearchResults -> 0f
            else -> (pullDistance / fullPullPx).coerceIn(0f, 1f)
        },
        // 紧跟手指，高刚度无回弹
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
        label = "search_load_more_expand",
    )
    val expandHeight = with(LocalDensity.current) { SEARCH_LOAD_ROW_HEIGHT.toPx() } * expandFraction
    // 收拢途中仍按加载中呈现，收起后复位
    var settlingAfterLoad by remember { mutableStateOf(false) }
    LaunchedEffect(loadInProgress, expandFraction <= 0f) {
        when {
            loadInProgress -> settlingAfterLoad = true
            expandFraction <= 0f -> settlingAfterLoad = false
        }
    }

    // 上移会截断顶部行，须裁剪
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            // 过拉手势挂在容器上，列表上移不丢指针
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var anchorY: Float? = null
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (listState.canScrollForward) {
                                // 仍可上滚，过拉尚未成立
                                anchorY = null
                                pullDistance = 0f
                            } else {
                                val anchor = anchorY
                                if (anchor == null) {
                                    anchorY = change.position.y
                                    pullDistance = 0f
                                } else {
                                    pullDistance = (anchor - change.position.y).coerceAtLeast(0f)
                                }
                            }
                        }
                        settlePull()
                    }
                }
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // 提示行占多高，列表上移多少
                .graphicsLayer { translationY = -expandHeight },
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(
                items = uniqueResults,
                // 两种来源 id 可能重复，key 结合来源
                key = { _, result -> "${result.source}-${result.id}" }
            ) { _, result ->
                SearchResultRow(
                    result = result,
                    titleColor = titleColor,
                    onClick = { onResultClick(result) }
                )
            }
            // 脚注只留终态，上拉提示另由浮层承担
            if (!playbackState.hasMoreSearchResults && uniqueResults.isNotEmpty()) {
                item(key = "search-load-end") {
                    Text(
                        text = stringResource(R.string.music_panel_search_load_all),
                        color = tint.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                    )
                }
            }
        }
        SearchLoadMoreRow(
            fraction = expandFraction,
            loading = loadInProgress || settlingAfterLoad,
            pulling = pullDistance > 0f,
            tint = tint,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// 底部加载行：随上拉展开，按整行布局再裁剪
// 到底松手即加载，上拉期间只提示「松开」
@Composable
private fun SearchLoadMoreRow(
    fraction: Float,
    loading: Boolean,
    pulling: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SEARCH_LOAD_ROW_HEIGHT * fraction)
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .requiredHeight(SEARCH_LOAD_ROW_HEIGHT),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 转圈只在加载时出现
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = tint
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.music_panel_search_loading_more),
                    color = tint,
                    fontSize = 11.sp
                )
            } else if (pulling) {
                Text(
                    text = stringResource(R.string.music_panel_search_release_load),
                    color = tint,
                    fontSize = 11.sp
                )
            }
            // 收拢途中既非上拉也非加载，不给文案
        }
    }
}
