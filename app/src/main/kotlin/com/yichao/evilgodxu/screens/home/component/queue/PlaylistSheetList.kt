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

// 列表顶部继续下拉的收起阈值：累计下拉超过该距离即收起面板
private val PLAYLIST_DISMISS_OVERSCROLL_DP = 64.dp
// 搜索框右上角操作按钮的直径
private val SEARCH_ACTION_BUTTON_DP = 32.dp
// 「滚动至顶」平滑滚动的范围：可见首项距顶部超过该值即直接跳转
private const val SEARCH_ACTION_SCROLL_RANGE = 10

// 播放列表面板列表区：搜索过滤、歌词命中、滚动定位与行内操作。
// 搜索与滚动状态只在本区域内使用，避免回抛到面板骨架
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
    // 列表内搜索关键词：仅过滤展示，不改变播放队列
    var searchQuery by remember { mutableStateOf("") }
    // 搜索框聚焦状态：键盘展开期间用拦截层接住列表点击，仅收起键盘避免误触播放
    var searchFocused by remember { mutableStateOf(false) }
    // 搜索结果：标题/歌手命中在前，歌词命中按相关度排序并附带歌词片段
    val searchHits = remember(tracks, searchQuery) { searchPlaylistTracks(tracks, searchQuery) }
    // 过滤后仍保留原展示列表索引：点击播放与定位需回填真实索引
    // 索引仅来自当前 tracks 快照；列表收缩后布局期可能读到过期索引，须容忍缺失
    val filteredIndices = remember(searchHits) { searchHits.map { it.index } }
    // 歌词命中项的下标 → 匹配片段：行副标题据此改显示歌词片段
    val lyricSnippets = remember(searchHits) {
        searchHits.mapNotNull { hit -> hit.lyricSnippet?.let { hit.index to it } }.toMap()
    }
    // 曲目行封面：滚动中始终以当前视口为中心维持取图窗口，按滚动方向优先取前方
    // （进入首页时由 QueueCoverPreload 以当前曲目为中心先辐射一批，此处接手滚动）。
    // 列表项是过滤后的下标，须经 filteredIndices 回填到真实曲目
    CoverPrefetch(listState) { index ->
        filteredIndices.getOrNull(index)?.let { tracks.getOrNull(it) }
    }
    // 滚动到顶部后继续下拉：累计下拉距离超过阈值即收起面板
    val density = LocalDensity.current
    val dismissOverscrollPx = with(density) { PLAYLIST_DISMISS_OVERSCROLL_DP.toPx() }
    // 列表已在顶部时「滚动至顶」无效果，按钮置灰
    val atListTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset == 0
        }
    }
    // 手动「定位播放」请求：置位后待过滤结果与目标索引对齐再滚动 ——
    // 过滤态下清空关键词需一次重组才恢复全量队列，直接滚动会落到错误曲目
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
                // 底部留白与整条渐隐带同高：过渡段落在框体上方的操作按钮区域内，
                // 末项停在按钮上方，不会被框体压住或被渐隐抹掉
                contentPadding = PaddingValues(bottom = SEARCH_BAR_EDGE_FADE_DP),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(
                    items = filteredIndices,
                    key = { _, index -> tracks.getOrNull(index)?.id ?: -1L },
                ) { _, index ->
                    val track = tracks.getOrNull(index) ?: return@itemsIndexed
                    // 展示列表可能与播放队列不同，播放态按曲目 id 判定而非列表下标
                    val isActive = track.id == playbackState.currentTrack?.id
                    // 右滑露出高级菜单，左滑露出歌单归属：归属弹窗只调整自定义歌单成员，
                    // 列表项本身保持可见
                    TrackSwipeRow(
                        onSwipePlaylist = { onSwipePlaylist(track) },
                        onSwipeAdvanced = { onSwipeAdvanced(track) },
                    ) {
                        PlaylistRow(
                            track = track,
                            // 歌词命中项以匹配片段替代歌手，标出该曲因何出现在结果中
                            subtitle = lyricSnippets[index] ?: track.artist,
                            // 片段内高亮命中的关键词；非歌词命中项无片段，不高亮
                            subtitleHighlight = lyricSnippets[index]?.let { searchQuery.trim() },
                            isActive = isActive,
                            isPlaying = isActive && playbackState.isPlaying,
                            isQueued = playbackState.isInPlayNext(track.id),
                            onClick = {
                                keyboardController?.hide()
                                when {
                                    // 跟随播放队列：切歌或切换播放/暂停，不动队列
                                    followsQueue && isActive -> togglePlayPause(playbackState)
                                    followsQueue -> scope.launch {
                                        playTrackAt(context, playbackState, index)
                                    }
                                    // 浏览态点击正在播放的曲目：只切换播放/暂停，保持浏览内容不变
                                    isActive -> togglePlayPause(playbackState)
                                    // 浏览态点击其它曲目：把该来源设为默认队列的过滤条件并起播，随后回到跟随播放队列
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
            // 面板展开动画完成后：始终将当前曲目滚动到列表居中位置；
            // 浏览的歌单不含当前曲目时无需定位。
            // 以曲目 id 序列而非列表引用为 key：收藏等仅改标记的操作会替换列表实例，
            // 用引用作 key 会把这类无关变更误判为列表变化而重新定位
            LaunchedEffect(playlistSettled, playlistTrackIds) {
                val index = tracks.indexOfFirst { it.id == playbackState.currentTrack?.id }
                if (playlistSettled && searchQuery.isBlank() && index >= 0) {
                    listState.scrollPlaylistTo(index, forceCenter = true)
                }
            }
            // 切歌时定位：当前曲目不在可视区内才滚动到居中位置，避免反复滚动卡顿
            LaunchedEffect(playbackState.currentTrack?.id, playlistTrackIds) {
                val index = tracks.indexOfFirst { it.id == playbackState.currentTrack?.id }
                if (playlistSettled && searchQuery.isBlank() && index >= 0) {
                    listState.scrollPlaylistTo(index)
                }
            }
            // 手动定位播放：待过滤结果与当前曲目对齐后居中滚动。
            // 当前曲目被过滤掉时上游会清空关键词，此处等重组恢复全量列表再定位
            LaunchedEffect(pendingLocate, filteredIndices) {
                if (!pendingLocate) return@LaunchedEffect
                val playingIndex = tracks.indexOfFirst { it.id == playbackState.currentTrack?.id }
                val position = filteredIndices.indexOf(playingIndex)
                if (position < 0) return@LaunchedEffect
                listState.scrollPlaylistTo(position, forceCenter = true)
                pendingLocate = false
            }
        }
        // 键盘展开期间覆盖列表的拦截层：点击列表任意处仅收起键盘，阻断误触播放歌单行
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
        // 底部搜索框常驻：列表底部已按渐隐带高度留白，框体不再压住末项。
        // 右上角两个操作按钮与搜索框同容器
        BottomSearchBarOverlay(
            placeholder = stringResource(R.string.playlist_search_placeholder),
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onFocusChanged = { searchFocused = it },
            // 聚焦进入输入态时隐藏「置顶」「定位」按钮，避免按钮占位挤压搜索框
            actionsVisible = !searchFocused,
            actions = {
                SearchActionButton(
                    icon = AppIcons.VerticalAlignTop,
                    contentDescription = stringResource(R.string.playlist_scroll_to_top),
                    enabled = !atListTop,
                    onClick = {
                        scope.launch {
                            // 距顶部较远时直接跳转，避免长距离动画滚动消耗
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
                        // 当前曲目被过滤掉则先清空关键词，否则该按钮永远定位不到目标
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

// 搜索框上方的悬浮操作按钮：圆形描边 + 透明底，描边与着色沿用底部搜索框的线条风格
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
