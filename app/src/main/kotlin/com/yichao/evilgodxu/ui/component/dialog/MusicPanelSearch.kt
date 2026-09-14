package com.yichao.evilgodxu.ui.component.dialog

import android.content.Context
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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

// 搜索结果列表底部脚注：加载更多时显示进度，全部加载完成时显示提示
@Composable
internal fun SearchLoadMoreFooter(
    playbackState: MusicPlaybackState,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    when {
        playbackState.isLoadingMore -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
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
        }
        !playbackState.hasMoreSearchResults && playbackState.searchResults.isNotEmpty() -> Text(
            text = stringResource(R.string.music_panel_search_load_all),
            color = tint.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            textAlign = TextAlign.Center
        )
    }
}

// 上拉加载所需的松手触发阈值
private val SEARCH_PULL_LOAD_THRESHOLD_DP = 60.dp

// 搜索结果列表：触底后继续上拉（overscroll）达到阈值才加载下一页，避免误触；上拉过程展示提示
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
    // 累计的底部上拉距离，达到阈值松手后触发加载下一页
    var pullDistance by remember { mutableFloatStateOf(0f) }
    val loadThreshold = with(LocalDensity.current) { SEARCH_PULL_LOAD_THRESHOLD_DP.toPx() }
    val connection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                // 已滚动到底部且继续上拉（可用偏移为负）时累计距离
                if (available.y < 0 && listState.isAtBottom()) {
                    pullDistance -= available.y
                } else if (pullDistance > 0f) {
                    // 反向滚动离开底部时取消未完成的上拉加载意图
                    pullDistance = 0f
                }
                return Offset.Zero
            }
        }
    }
    // 手指松开（滚动停止）时按累计距离决定是否加载下一页，并复位累计距离
    LaunchedEffect(listState, loadThreshold) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { !it }
            .collect {
                if (pullDistance >= loadThreshold && playbackState.hasMoreSearchResults &&
                    !playbackState.isSearching && playbackState.searchResults.isNotEmpty()
                ) {
                    loadMoreSearchResults(playbackState, context)
                }
                pullDistance = 0f
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(connection),
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
        // 底部脚注：加载中 / 上拉加载提示 / 全部加载完成
        item(key = "load-more-footer") {
            when {
                playbackState.isLoadingMore -> SearchLoadMoreFooter(playbackState, tint)
                playbackState.hasMoreSearchResults -> SearchPullLoadHint(pullDistance, loadThreshold, tint)
                else -> SearchLoadMoreFooter(playbackState, tint)
            }
        }
    }
}

// 列表是否已滚动到底部（最后一项可见）
private fun LazyListState.isAtBottom(): Boolean {
    val info = layoutInfo
    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
    return info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 1
}

// 上拉加载提示：未达阈值提示继续上拉，达到阈值提示松手加载
@Composable
private fun SearchPullLoadHint(pullDistance: Float, threshold: Float, tint: Color) {
    val canRelease = pullDistance >= threshold
    Text(
        text = stringResource(
            if (canRelease) R.string.music_panel_search_release_load
            else R.string.music_panel_search_pull_load
        ),
        color = tint.copy(alpha = if (canRelease) 1f else 0.6f),
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    )
}
