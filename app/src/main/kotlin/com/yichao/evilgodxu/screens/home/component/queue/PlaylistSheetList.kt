package com.yichao.evilgodxu.screens.home.component.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.LocalMetadataEnricher
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSource
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.music.playback.switchToPlaylistQueue
import com.yichao.evilgodxu.data.music.playback.togglePlayPause
import com.yichao.evilgodxu.ui.component.BottomSearchBarOverlay
import com.yichao.evilgodxu.ui.component.CoverPrefetch
import com.yichao.evilgodxu.ui.component.SEARCH_ACTION_GAP_DP
import com.yichao.evilgodxu.ui.component.SEARCH_BAR_EDGE_FADE_DP
import com.yichao.evilgodxu.ui.component.fadeUnderBottomSearchBar
import com.yichao.evilgodxu.ui.component.player.PlaylistRow
import com.yichao.evilgodxu.ui.component.scrollPlaylistTo
import com.yichao.evilgodxu.ui.icons.AppIcons
import kotlinx.coroutines.launch

// 顶部下拉 64dp 累计即收起面板
private val PLAYLIST_DISMISS_OVERSCROLL_DP = 64.dp
// 搜索框右上角操作按钮的直径
private val SEARCH_ACTION_BUTTON_DP = 32.dp
// 首项距顶超过 10 项即直接跳转
private const val SEARCH_ACTION_SCROLL_RANGE = 10

// 列表区：搜索过滤、歌词命中、滚动定位与行内操作
@Composable
internal fun PlaylistSheetList(
    playbackState: MusicPlaybackState,
    tracks: List<MusicTrack>,
    followsQueue: Boolean,
    viewedSource: PlaylistSource?,
    playlistSettled: Boolean,
    playlistTrackIds: List<Long>,
    onDismiss: () -> Unit,
    onLongPressTrack: (MusicTrack) -> Unit,
    onSwipePlaylist: (MusicTrack) -> Unit,
    onSwipeAdvanced: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val metadataEnricher = LocalMetadataEnricher.current
    val listState = rememberLazyListState()
    // 搜索关键词，只过滤展示，不改队列
    var searchQuery by remember { mutableStateOf("") }
    // 聚焦态，键盘展开时拦截列表点击
    var searchFocused by remember { mutableStateOf(false) }
    // 结果：标题/歌手在前，歌词按相关度
    val searchHits = remember(tracks, searchQuery) { searchPlaylistTracks(tracks, searchQuery) }
    // 保留原索引供回填；列表收缩后须容忍过期索引
    val filteredIndices = remember(searchHits) { searchHits.map { it.index } }
    // 下标 → 歌词片段，供行副标题使用
    val lyricSnippets = remember(searchHits) {
        searchHits.mapNotNull { hit -> hit.lyricSnippet?.let { hit.index to it } }.toMap()
    }
    // 取图窗口按视口维持，下标经映射回填
    CoverPrefetch(listState) { index ->
        filteredIndices.getOrNull(index)?.let { tracks.getOrNull(it) }
    }
    // 到顶后累计下拉过阈值即收起
    val density = LocalDensity.current
    val dismissOverscrollPx = with(density) { PLAYLIST_DISMISS_OVERSCROLL_DP.toPx() }
    // 已在顶部时置灰
    val atListTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
        }
    }
    // 定位请求：待过滤结果对齐后再滚动，否则落错曲目
    var pendingLocate by remember { mutableStateOf(false) }
    val dismissNestedScroll = remember(listState) {
        object : NestedScrollConnection {
            private var overscrollAccum = 0f
            private var dismissed = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (dismissed || source != NestedScrollSource.UserInput) return Offset.Zero
                val dy = available.y
                val atTop = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                if (dy > 0f && atTop) {
                    overscrollAccum += dy
                    if (overscrollAccum > dismissOverscrollPx) {
                        dismissed = true
                        onDismiss()
                    }
                } else {
                    overscrollAccum = 0f
                }
                return Offset.Zero
            }
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        if (searchQuery.isNotBlank() && filteredIndices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.playlist_search_no_results),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(dismissNestedScroll)
                    .fadeUnderBottomSearchBar(),
                // 底部留白同渐隐带高度，末项停在按钮上方
                contentPadding = PaddingValues(bottom = SEARCH_BAR_EDGE_FADE_DP),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(
                    items = filteredIndices,
                    key = { _, index -> tracks.getOrNull(index)?.id ?: -1L },
                ) { _, index ->
                    val track = tracks.getOrNull(index) ?: return@itemsIndexed
                    // 播放态按曲目 id 判定，不按列表下标
                    val isActive = track.id == playbackState.currentTrack?.id
                    // 右滑高级菜单，左滑归属；归属弹窗不隐藏列表
                    TrackSwipeRow(
                        onSwipePlaylist = { onSwipePlaylist(track) },
                        onSwipeAdvanced = { onSwipeAdvanced(track) },
                    ) {
                        PlaylistRow(
                            track = track,
                            // 歌词命中用片段替代歌手，标出命中原因
                            subtitle = lyricSnippets[index] ?: track.artist,
                            // 片段内高亮关键词，非歌词命中不高亮
                            subtitleHighlight = lyricSnippets[index]?.let { searchQuery.trim() },
                            isActive = isActive,
                            isPlaying = isActive && playbackState.isPlaying,
                            isQueued = playbackState.isInPlayNext(track.id),
                            onClick = {
                                keyboardController?.hide()
                                when {
                                    // 跟随队列：切歌或播放/暂停
                                    followsQueue && isActive -> togglePlayPause(playbackState)
                                    followsQueue -> scope.launch {
                                        playTrackAt(context, playbackState, index)
                                    }
                                    // 浏览态点当前曲目只切播放/暂停
                                    isActive -> togglePlayPause(playbackState)
                                    // 浏览态点其它曲目：设为过滤并起播，随后跟随队列
                                    else -> {
                                        switchToPlaylistQueue(
                                            context,
                                            playbackState,
                                            tracks,
                                            viewedSource,
                                            metadataEnricher,
                                            startTrackId = track.id,
                                            autoPlay = true,
                                        )
                                        playbackState.followPlaybackQueue()
                                    }
                                }
                                onDismiss()
                            },
                            onLongClick = { onLongPressTrack(track) },
                            onFavoriteClick = { playbackState.toggleFavorite(track.id) },
                            onPlayNextClick = { playbackState.togglePlayNext(track) },
                        )
                    }
                }
            }
            // 展开后居中当前曲目，用 id 序列作 key
            LaunchedEffect(playlistSettled, playlistTrackIds) {
                val index = tracks.indexOfFirst { it.id == playbackState.currentTrack?.id }
                if (playlistSettled && searchQuery.isBlank() && index >= 0) {
                    listState.scrollPlaylistTo(index, forceCenter = true)
                }
            }
            // 切歌时不在可视区才居中，避免反复滚动
            LaunchedEffect(playbackState.currentTrack?.id, playlistTrackIds) {
                val index = tracks.indexOfFirst { it.id == playbackState.currentTrack?.id }
                if (playlistSettled && searchQuery.isBlank() && index >= 0) {
                    listState.scrollPlaylistTo(index)
                }
            }
            // 定位播放：对齐过滤结果后居中；关键词由上游清空
            LaunchedEffect(pendingLocate, filteredIndices) {
                if (!pendingLocate) return@LaunchedEffect
                val playingIndex = tracks.indexOfFirst { it.id == playbackState.currentTrack?.id }
                val position = filteredIndices.indexOf(playingIndex)
                if (position < 0) return@LaunchedEffect
                listState.scrollPlaylistTo(position, forceCenter = true)
                pendingLocate = false
            }
        }
        // 键盘展开时拦截列表点击，只收键盘
        if (searchFocused) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(focusManager) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val up = waitForUpOrCancellation()
                            if (up != null) {
                                up.consume()
                                focusManager.clearFocus()
                            }
                        }
                    }
            )
        }
        // 搜索框常驻，底部留白后不再压末项
        BottomSearchBarOverlay(
            placeholder = stringResource(R.string.playlist_search_placeholder),
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onFocusChanged = { searchFocused = it },
            // 输入态隐藏置顶与定位按钮，避免挤压
            actionsVisible = !searchFocused,
            actions = {
                SearchActionButton(
                    icon = AppIcons.VerticalAlignTop,
                    contentDescription = stringResource(R.string.playlist_scroll_to_top),
                    enabled = !atListTop,
                    onClick = {
                        scope.launch {
                            // 距顶较远直接跳转，避免长动画消耗
                            if (listState.firstVisibleItemIndex > SEARCH_ACTION_SCROLL_RANGE) {
                                listState.scrollToItem(0)
                            } else {
                                listState.animateScrollToItem(0)
                            }
                        }
                    },
                )
                Spacer(Modifier.width(SEARCH_ACTION_GAP_DP))
                SearchActionButton(
                    icon = AppIcons.MyLocation,
                    contentDescription = stringResource(R.string.playlist_locate_playing),
                    // 当前曲目不在展示列表内时无从定位
                    enabled = tracks.any { it.id == playbackState.currentTrack?.id },
                    onClick = {
                        // 被过滤掉则先清空关键词，否则定位不到
                        val playingIndex = tracks.indexOfFirst {
                            it.id == playbackState.currentTrack?.id
                        }
                        if (!filteredIndices.contains(playingIndex)) {
                            searchQuery = ""
                        }
                        pendingLocate = true
                    },
                )
            },
        )
    }
}

// 悬浮操作按钮：圆形描边 + 透明底
@Composable
private fun SearchActionButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(SEARCH_ACTION_BUTTON_DP)
            .clip(CircleShape)
            .border(
                width = 1.dp,
                color = tint.copy(alpha = if (enabled) 0.45f else 0.25f),
                shape = CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.3f),
            modifier = Modifier.size(18.dp),
        )
    }
}
