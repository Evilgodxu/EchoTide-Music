package com.yichao.evilgodxu.ui.component.dialog

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.panel.loadMoreSearchResults
import com.yichao.evilgodxu.data.music.panel.performSearch
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.player.HeaderIconButton
import com.yichao.evilgodxu.ui.component.player.MusicErrorBanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchOverlay(
    playbackState: MusicPlaybackState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(
        modifier = modifier.pointerInput(Unit) {
            detectHorizontalDragGestures { _, dragAmount ->
                if (dragAmount < -50f) {
                    playbackState.setSearchMode(false)
                    playbackState.setSearchResultsVisible(false)
                }
            }
        }
    ) {
        Text(
            text = stringResource(R.string.music_panel_search_title),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(24.dp)
                ),
            contentAlignment = Alignment.CenterStart
        ) {
            Icon(
                imageVector = AppIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 16.dp)
                    .size(20.dp)
            )
            BasicTextField(
                value = playbackState.searchQuery,
                onValueChange = { playbackState.searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, end = 44.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        val query = playbackState.searchQuery.trim()
                        if (query.isNotBlank()) {
                            scope.launch {
                                performSearch(playbackState, context)
                            }
                        }
                    }
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (playbackState.searchQuery.isEmpty()) {
                            Text(
                                text = stringResource(R.string.music_panel_search_placeholder),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                fontSize = 14.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (playbackState.searchQuery.isNotEmpty()) {
                IconButton(
                    onClick = { playbackState.setSearchQuery("") },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(32.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (playbackState.searchHistory.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.music_panel_search_history),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                IconButton(
                    onClick = { playbackState.clearSearchHistory() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Delete,
                        contentDescription = stringResource(R.string.music_panel_search_history_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(playbackState.searchHistory, key = { it }) { query ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                playbackState.setSearchQuery(query)
                                scope.launch { performSearch(playbackState, context) }
                            }
                            .padding(start = 8.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = query,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { playbackState.removeSearchHistory(query) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = AppIcons.Close,
                                contentDescription = stringResource(R.string.music_panel_search_history_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchResultsOverlay(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    context: Context,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onTrackSelected: (NeteaseSongSearchResult) -> Unit,
) {
    AnimatedContent(
        targetState = visible,
        transitionSpec = {
            (slideInVertically { it } + fadeIn()).togetherWith(slideOutVertically { it } + fadeOut())
        },
        label = "search_results"
    ) { show ->
        if (show) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_search_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.music_panel_track_count, playbackState.searchResults.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                        HeaderIconButton(
                            icon = AppIcons.Refresh,
                            onClick = { if (!playbackState.isSearching) onRefresh() },
                            modifier = Modifier.size(24.dp),
                            enabled = !playbackState.isSearching
                        )
                        HeaderIconButton(
                            icon = AppIcons.Close,
                            onClick = onClose,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                val errorMsg = playbackState.errorMsg
                if (errorMsg != null) {
                    MusicErrorBanner(
                        message = errorMsg,
                        onDismiss = { playbackState.setErrorMsg(null) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (playbackState.isSearching) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (playbackState.searchResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.music_panel_search_no_results),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    SearchResultsLazyList(
                        playbackState = playbackState,
                        context = context,
                        onResultClick = onTrackSelected,
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun SearchResultRow(
    result: NeteaseSongSearchResult,
    onClick: () -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
) {
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
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(coverModel)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build(),
                    contentDescription = result.title,
                    contentScale = ContentScale.Crop,
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

        Text(
            text = stringResource(
                when (result.source) {
                    MusicSearchSource.QQ -> R.string.music_panel_search_source_qq
                    MusicSearchSource.KUGOU -> R.string.music_panel_search_source_kugou
                    MusicSearchSource.KUWO -> R.string.music_panel_search_source_kuwo
                    MusicSearchSource.MIGU -> R.string.music_panel_search_source_migu
                    MusicSearchSource.NETEASE -> R.string.music_panel_search_source
                }
            ),
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .background(
                    Color.White.copy(alpha = 0.10f),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

// 提示行完全展开所需的上拉距离：只决定展开快慢，不参与是否加载的判定
private val SEARCH_LOAD_ROW_FULL_PULL_DP = 60.dp

// 底部加载提示行完全展开后的行高，展开过程中据此按比例取当前行高
private val SEARCH_LOAD_ROW_HEIGHT = 40.dp

// 提示行的最短展开时长：代理音源的分页是在本地缓冲里切分，毫秒级即返回，
// 若随即收起会让人以为没触发加载，故对展开态做最短保持
private const val MIN_LOAD_ROW_MS = 500L

// 搜索结果列表：列表已在底部时上拉即展开提示行，松手后加载下一页；
// 上拉量与加载状态共同驱动提示行展开，列表同步整体上移为其让位
@Composable
internal fun SearchResultsLazyList(
    playbackState: MusicPlaybackState,
    context: Context,
    onResultClick: (NeteaseSongSearchResult) -> Unit,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val listState = rememberLazyListState()
    // 兜底去重：上游即使仍重复下发同一条目，渲染前按键过滤，杜绝 LazyColumn key 冲突
    val uniqueResults = remember(playbackState.searchResults) {
        playbackState.searchResults.distinctBy { it.source to it.id }
    }
    // 累计的底部上拉距离：决定提示行展开多少，同时标记本次上拉确有加载意图
    var pullDistance by remember { mutableFloatStateOf(0f) }
    // 本次触发是否仍在加载：不直接用 playbackState.isLoadingMore，因本地切分的分页不置该标记
    var loadInProgress by remember { mutableStateOf(false) }
    val fullPullPx = with(LocalDensity.current) { SEARCH_LOAD_ROW_FULL_PULL_DP.toPx() }
    val connection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                // 只有两种情况作废未完成的上拉加载意图：列表仍能继续上滚（内容还在动，尚未到底）、
                // 或用户反向下拉。可用偏移为零不在此列 —— 到底后的上拉量会被滚动容器的过滚效果吞掉，
                // 把「这一帧没有溢出」当成「用户松手离开底部」，会在手指还按着时把提示行中途收回
                if (listState.canScrollForward || available.y > 0f) {
                    pullDistance = 0f
                } else if (available.y < 0f) {
                    // 已在底部继续上拉：累计过拉量，只增不减，直到松手或意图作废
                    pullDistance -= available.y
                }
                return Offset.Zero
            }
        }
    }
    // 手指松开（滚动停止）时判定：只要本次上拉在底部拉出过溢出即加载下一页。
    // 不设距离门槛 —— 列表已在底部时任何上拉都算明确的加载意图，免得同一位置要拉第二次。
    // 仍要求此刻确在底部：惯性把列表带离底部后残留的上拉量不该兑现成加载
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .collect {
                if (pullDistance > 0f && !listState.canScrollForward &&
                    playbackState.hasMoreSearchResults &&
                    !playbackState.isSearching && playbackState.searchResults.isNotEmpty()
                ) {
                    loadInProgress = true
                    val startedAt = SystemClock.elapsedRealtime()
                    try {
                        loadMoreSearchResults(playbackState, context)
                        // 补足最短展开时长，使本地切分的秒回分页同样有可见的加载反馈
                        val remaining = MIN_LOAD_ROW_MS - (SystemClock.elapsedRealtime() - startedAt)
                        if (remaining > 0) delay(remaining)
                    } finally {
                        loadInProgress = false
                    }
                }
                pullDistance = 0f
            }
    }
    // 展开比例：上拉期间随手指出量，加载中保持完全展开，已无更多可加载时收起
    val expandFraction by animateFloatAsState(
        targetValue = when {
            loadInProgress -> 1f
            !playbackState.hasMoreSearchResults -> 0f
            else -> (pullDistance / fullPullPx).coerceIn(0f, 1f)
        },
        // 上拉时须紧跟手指，故取高刚度无回弹弹簧；收起同样据此平滑收回
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
        label = "search_load_more_expand",
    )
    val expandHeight = with(LocalDensity.current) { SEARCH_LOAD_ROW_HEIGHT.toPx() } * expandFraction
    // 加载结束后提示行还要收拢，收拢途中继续按「加载中」呈现：若这时回落到手势文案，
    // 看着就像又被拉了一次。行完全收起即复位
    var settlingAfterLoad by remember { mutableStateOf(false) }
    LaunchedEffect(loadInProgress, expandFraction <= 0f) {
        when {
            loadInProgress -> settlingAfterLoad = true
            expandFraction <= 0f -> settlingAfterLoad = false
        }
    }

    // 列表为让位而上移，顶部行会被截断 —— 与真实滚动的观感一致，但须裁剪以免画到上方标题区
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(connection)
                // 提示行占多高，列表就上移多少：二者由同一展开比例算出，不会错位或露缝
                .graphicsLayer { translationY = -expandHeight },
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(
                items = uniqueResults,
                // 聚合两种来源后 id 可能重复，key 需结合来源保证唯一
                key = { _, result -> "${result.source}-${result.id}" }
            ) { _, result ->
                SearchResultRow(
                    result = result,
                    titleColor = titleColor,
                    onClick = { onResultClick(result) }
                )
            }
            // 底部脚注只留终态；上拉提示与加载中由列表外展开的提示行承担
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

// 底部加载提示行：行高随上拉量自列表底部向上展开，加载中保持完全展开；
// 行内按整行高度布局再整体裁剪，故展开途中内容只是被逐段露出，不会随行高被压扁。
// 到底部后松手即加载，故上拉期间只提示「松开」，不区分是否拉过某个距离
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
            // 转圈只在加载时出现：上拉阶段只是提示，不该让人以为已经在加载
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
            // 两者皆非即收拢途中（取消的上拉），此时既非上拉也非加载，不给文案
        }
    }
}
