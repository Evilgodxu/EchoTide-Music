package com.yichao.evilgodxu.screens.home.component.queue

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.clip.RingtoneUsage
import com.yichao.evilgodxu.data.music.clip.shareTrack
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.LocalMetadataEnricher
import com.yichao.evilgodxu.LocalPlaylistRefresher
import com.yichao.evilgodxu.LocalPlaylistStore
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSortField
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.music.playback.switchPlaylistSource
import com.yichao.evilgodxu.data.music.playback.switchToPlaylistQueue
import com.yichao.evilgodxu.data.music.playback.togglePlayPause
import com.yichao.evilgodxu.data.playlist.isViewSourceValid
import com.yichao.evilgodxu.data.playlist.resolveSourceTracks
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.AppDialog
import com.yichao.evilgodxu.ui.component.BottomSearchBarOverlay
import com.yichao.evilgodxu.ui.component.CoverPrefetch
import com.yichao.evilgodxu.ui.component.DIALOG_LIST_HEIGHT_FRACTION
import com.yichao.evilgodxu.ui.component.DialogOption
import com.yichao.evilgodxu.ui.component.player.HeaderIconButton
import com.yichao.evilgodxu.ui.component.player.PlaylistRow
import com.yichao.evilgodxu.ui.component.RemoveTrackDialog
import com.yichao.evilgodxu.ui.component.SEARCH_ACTION_GAP_DP
import com.yichao.evilgodxu.ui.component.SEARCH_BAR_REGION_DP
import com.yichao.evilgodxu.windowsize.rememberWindowLandscape
import com.yichao.evilgodxu.ui.component.scrollPlaylistTo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 播放列表面板：点击遮罩或关闭按钮收起
@Composable
internal fun PlaylistSheet(
    visible: Boolean,
    playbackState: MusicPlaybackState,
    onDismiss: () -> Unit,
    // 高级菜单的「查看频谱」：跳转频谱分析页，只传曲目标识
    onViewSpectrum: (Long) -> Unit = {},
    // 高级菜单的「编辑元数据」：跳转元数据编辑页，只传曲目标识
    onEditMetadata: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val playlistRefresher = LocalPlaylistRefresher.current
    val metadataEnricher = LocalMetadataEnricher.current
    // 竖屏播放列表高度减半，横屏全高显示
    val isPortrait = !rememberWindowLandscape()
    val sheetHeightFraction = if (isPortrait) 0.5f else 1f
    // 歌单副标题点击后的快捷切换弹层
    var showSwitcher by remember { mutableStateOf(false) }
    // 面板展示的曲目：跟随播放队列时取播放队列，浏览态按来源歌单从全量库解析。
    // 浏览态只决定展示内容，与播放队列解耦，切换时不动播放器
    val playlistStore = LocalPlaylistStore.current
    // 自定义歌单需先完成读盘才能解析，否则浏览态会短暂展示空列表
    var playlistStoreLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        playlistStore.awaitLoaded(context)
        playlistStoreLoaded = true
    }
    val followsQueue = playbackState.viewedFollowsQueue
    val viewedSource = playbackState.viewedSource
    val library = playbackState.libraryTracks
    val tracks = remember(
        followsQueue,
        viewedSource,
        library,
        playlistStore.playlists,
        playbackState.playlist,
        playbackState.likedIds,
        playbackState.recentPlayedIds,
    ) {
        when {
            followsQueue -> playbackState.playlist
            viewedSource == null -> library
            else -> resolveSourceTracks(
                library,
                playlistStore.playlists,
                playbackState.likedIds,
                playbackState.recentPlayedIds,
                viewedSource,
            )
        }
    }
    val viewedKey = if (followsQueue) playbackState.playlistSource?.key else viewedSource?.key
    val viewedName = if (followsQueue) playbackState.playlistSource?.name else viewedSource?.name
    // 浏览的歌单已失效（被删除、专辑/艺术家分组消失）时回落到默认播放列表，避免面板停留在空列表；
    // 读盘完成前自定义歌单必然解析为空、扫描期间全量库可能暂时为空，都不作为失效依据
    LaunchedEffect(playlistStoreLoaded, playbackState.isScanning, followsQueue, viewedSource, tracks) {
        if (playlistStoreLoaded && !playbackState.isScanning && !followsQueue && viewedSource != null &&
            !isViewSourceValid(playlistStore.playlists, tracks, viewedSource)
        ) {
            playbackState.viewPlaylist(null)
        }
    }
    // 排序对话框显隐
    var showSortDialog by remember { mutableStateOf(false) }
    // 长按删除目标：非空时显示确认弹窗
    var deleteTrack by remember { mutableStateOf<MusicTrack?>(null) }
    // 右滑高级菜单目标：非空时显示菜单对话框
    var advancedTrack by remember { mutableStateOf<MusicTrack?>(null) }
    // 左滑拉黑目标：非空时显示确认对话框。黑名单没有单曲撤销入口，误触只能整表重置，故写入前须二次确认；
    // 是否一并删除本地文件由确认框内的选中项决定，默认仅拉黑
    var blacklistTarget by remember { mutableStateOf<MusicTrack?>(null) }
    // 设为默认铃声的请求：非空时由安装组件提交并在授权往返后自动续做
    var soundRequest by remember { mutableStateOf<DefaultSoundRequest?>(null) }
    val shareChooserTitle = stringResource(R.string.playlist_advanced_menu_share)
    // 后台预读整份播放列表的歌词：曲目集合变化即触发，不等面板展开逐行懒加载，
    // 展开时歌词已就绪；幂等，已缓存/补全中/全量补全中的曲目自动跳过。
    // 封面不在此列：它按显示尺寸取图，由可视区邻域预取负责（见 CoverPrefetch）
    val playlistTrackIds = remember(tracks) { tracks.map { it.id } }
    LaunchedEffect(playlistTrackIds) {
        val currentId = playbackState.currentTrack?.id
        tracks
            .sortedBy { it.id != currentId }
            .forEach { playbackState.requestMetadata(it) }
    }
    Box(Modifier.fillMaxSize()) {
        // 遮罩，点击收起
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        // 从底部滑入的面板
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(animationSpec = tween(300)) { it } + fadeIn(),
            exit = slideOutVertically(animationSpec = tween(300)) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(sheetHeightFraction)
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    // 键盘弹出时面板内容整体上移避开键盘，窗口与其他页面保持原位
                    .imePadding(),
            ) {
                // 展开就绪：等面板滑入动画完成后再定位当前曲目，避免滚动与展开动画叠加卡顿
                var playlistSettled by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    playlistSettled = false
                    delay(PLAYLIST_EXPAND_ANIM_MS)
                    playlistSettled = true
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.music_panel_playlist_title),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    // 歌单副标题：浅色小字常驻显示当前浏览的歌单，点击快捷切换
                    Text(
                        text = viewedName ?: stringResource(R.string.playlist_switch_default),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showSwitcher = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = pluralStringResource(R.plurals.music_panel_track_count, tracks.size, tracks.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        HeaderIconButton(
                            icon = AppIcons.Refresh,
                            onClick = {
                                if (!playbackState.isScanning) {
                                    scope.launch {
                                        playlistRefresher.refresh(
                                            context, playbackState, restoreCurrent = true
                                        ) {
                                            // 刷新后后台加载封面与歌词；刚完成全量扫描，引用集可信，
                                            // 允许参与孤儿缓存的窗口回收
                                            scope.launch {
                                                metadataEnricher.enrichAndCleanup(
                                                    context, playbackState, reclaimOrphans = true
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.size(28.dp),
                            enabled = !playbackState.isScanning,
                        )
                        HeaderIconButton(
                            icon = AppIcons.Sort,
                            contentDescription = stringResource(R.string.music_panel_sort),
                            onClick = { showSortDialog = true },
                            modifier = Modifier.size(28.dp),
                            // 排序规则只作用于默认全量播放队列，浏览其它歌单或队列为歌单来源时不可用
                            enabled = followsQueue &&
                                playbackState.playlistSource == null &&
                                !playbackState.isScanning,
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = AppIcons.Close,
                                contentDescription = stringResource(R.string.home_player_close_playlist),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (playbackState.isScanning) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (tracks.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.home_player_empty),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val listState = rememberLazyListState()
                    // 列表滚动中隐藏悬浮控件，滚动停止自动恢复
                    val isScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
                    // 列表内搜索关键词：仅过滤展示，不改变播放队列
                    var searchQuery by remember { mutableStateOf("") }
                    // 搜索框聚焦状态：键盘展开期间用拦截层接住列表点击，仅收起键盘避免误触播放
                    var searchFocused by remember { mutableStateOf(false) }
                    // 过滤后仍保留原展示列表索引：点击播放与定位需回填真实索引
                    // 索引仅来自当前 tracks 快照；列表收缩后布局期可能读到过期索引，须容忍缺失
                    val filteredIndices = remember(tracks, searchQuery) {
                        if (searchQuery.isBlank()) {
                            tracks.indices.toList()
                        } else {
                            tracks.indices.filter { index ->
                                val track = tracks.getOrNull(index) ?: return@filter false
                                track.title.contains(searchQuery, ignoreCase = true) ||
                                    track.artist.contains(searchQuery, ignoreCase = true)
                            }
                        }
                    }
                    // 曲目行封面：可视区邻域提前取图，滚动进入视口时不再先闪占位符。
                    // 列表项是过滤后的下标，须经 filteredIndices 回填到真实曲目
                    CoverPrefetch(listState) { index ->
                        filteredIndices.getOrNull(index)?.let { tracks.getOrNull(it) }
                    }
                    // 滚动到顶部后继续下拉：累计下拉距离超过阈值即收起面板
                    val density = LocalDensity.current
                    val dismissOverscrollPx = with(density) { PLAYLIST_DISMISS_OVERSCROLL_DP.toPx() }
                    // 底部悬浮区占用高度：搜索框及其上方操作按钮组共同遮挡列表末端，
                    // 判定滚到底部需把按钮组高度一并计入，否则末项会被按钮压住
                    val searchBarRegionPx = with(density) {
                        (SEARCH_BAR_REGION_DP + SEARCH_ACTION_BUTTON_DP + SEARCH_ACTION_GAP_DP).toPx()
                    }
                    // 滚到底部判定：最后一项已到达列表底部（底缘进入搜索框遮挡区）；
                    // 列表不足一屏时最后一项不会触底，搜索框保持常驻
                    val atBottom by remember {
                        derivedStateOf {
                            val layout = listState.layoutInfo
                            val last = layout.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
                            last.index == layout.totalItemsCount - 1 &&
                                last.offset + last.size >= layout.viewportEndOffset - searchBarRegionPx
                        }
                    }
                    // 搜索框显隐：列表滚动中或滚到底部时隐藏，避免遮挡底部曲目；
                    // 输入/聚焦期间常驻（即使已有搜索词，滚动到底部仍应隐藏），切歌触发的自动滚动不中断输入
                    val searchHidden = (isScrolling || atBottom) && !searchFocused
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
                    Box(modifier = Modifier.fillMaxSize()) {
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
                                    .nestedScroll(dismissNestedScroll),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                itemsIndexed(
                                    items = filteredIndices,
                                    key = { _, index -> tracks.getOrNull(index)?.id ?: -1L },
                                ) { _, index ->
                                    val track = tracks.getOrNull(index) ?: return@itemsIndexed
                                    // 展示列表可能与播放队列不同，播放态按曲目 id 判定而非列表下标
                                    val isActive = track.id == playbackState.currentTrack?.id
                                    // 右滑露出高级菜单，左滑露出拉黑：拉黑只写入黑名单算法，列表项本身保持可见
                                    TrackSwipeRow(
                                        // 左滑仅选中目标，由确认对话框决定是否真正写入黑名单
                                        onSwipeBlacklist = { blacklistTarget = track },
                                        onSwipeAdvanced = { advancedTrack = track },
                                    ) {
                                        PlaylistRow(
                                            track = track,
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
                                                    // 浏览态点击其它曲目：把该歌单设为播放队列并起播，随后回到跟随播放队列
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
                                            onLongClick = { deleteTrack = track },
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
                        // 底部搜索框：滚到底部或滚动中隐藏，避免遮挡底部曲目；输入中常驻。
                        // 右上角两个操作按钮与搜索框同容器，显隐随之同步
                        BottomSearchBarOverlay(
                            hidden = searchHidden,
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
            }
        }
        PlaylistSwitcher(
            visible = showSwitcher,
            playbackState = playbackState,
            currentKey = viewedKey,
            // 切换歌单即把所选歌单设为播放队列：当前曲目在新歌单中则按新歌单顺序继续播放，
            // 不在则从新歌单首曲起播；面板同步改为展示所选歌单
            onSwitch = { source ->
                switchPlaylistSource(
                    context = context,
                    state = playbackState,
                    source = source,
                    playlists = playlistStore.playlists,
                    metadataEnricher = metadataEnricher,
                )
                playbackState.viewPlaylist(source)
            },
            onDismiss = { showSwitcher = false },
        )
        PlaylistSortDialog(
            visible = showSortDialog,
            currentField = playbackState.playlistSortField,
            descending = playbackState.playlistSortDescending,
            onApply = { field, descending -> playbackState.setPlaylistSort(field, descending) },
            onDismiss = { showSortDialog = false },
        )
        RemoveTrackDialog(
            track = deleteTrack,
            titleRes = R.string.music_panel_delete_title,
            messageRes = R.string.music_panel_delete_message,
            confirmRes = R.string.music_panel_delete_confirm,
            onConfirm = { track ->
                scope.launch { playbackState.deleteSongPermanently(context, track) }
                deleteTrack = null
            },
            onDismiss = { deleteTrack = null },
        )
        BlacklistConfirmDialog(
            track = blacklistTarget,
            onConfirm = { track, deleteFile ->
                playbackState.blacklistTrack(context, track)
                // 删除与拉黑在数据层保持解耦：只有用户显式勾选才落到文件层，并复用统一删除路径
                // （音频源文件 + 仅该曲引用的歌词缓存，同步曲库、播放队列与歌单引用）。
                // 黑名单条目不随之清除：文本键与文件是否存在无关，用户日后重新放入该文件仍应保持拉黑
                if (deleteFile) scope.launch { playbackState.deleteSongPermanently(context, track) }
                blacklistTarget = null
            },
            onDismiss = { blacklistTarget = null },
        )
        TrackAdvancedMenuDialog(
            visible = advancedTrack != null,
            actionsEnabled = advancedTrack?.isLocalAudioSource == true,
            onShare = {
                advancedTrack?.let { shareTrack(context, it, shareChooserTitle) }
                advancedTrack = null
            },
            onSetRingtone = {
                advancedTrack?.let { soundRequest = DefaultSoundRequest(it, RingtoneUsage.RINGTONE) }
                advancedTrack = null
            },
            onSetAlarm = {
                advancedTrack?.let { soundRequest = DefaultSoundRequest(it, RingtoneUsage.ALARM) }
                advancedTrack = null
            },
            onViewSpectrum = {
                // 先收起面板再跳转，从频谱页返回时不会停在展开的面板上
                val target = advancedTrack
                advancedTrack = null
                if (target != null) {
                    onDismiss()
                    onViewSpectrum(target.id)
                }
            },
            onEditMetadata = {
                // 与频谱页同一处理：先收起面板再跳转，返回时不停在展开的面板上
                val target = advancedTrack
                advancedTrack = null
                if (target != null) {
                    onDismiss()
                    onEditMetadata(target.id)
                }
            },
            onDismiss = { advancedTrack = null },
        )
        // 安装不依赖对话框存活：面板收起后授权往返仍能续做
        DefaultSoundInstallerHost(
            request = soundRequest,
            onRequestChange = { soundRequest = it },
        )
    }
}

// 播放列表展开进入动画时长：等动画完成后才滚动定位当前曲目，避免动画叠加卡顿
private const val PLAYLIST_EXPAND_ANIM_MS = 300L
// 列表顶部继续下拉的收起阈值：累计下拉超过该距离即收起面板
private val PLAYLIST_DISMISS_OVERSCROLL_DP = 64.dp
// 搜索框右上角操作按钮的直径
private val SEARCH_ACTION_BUTTON_DP = 32.dp
// 「滚动至顶」平滑滚动的范围：可见首项距顶部超过该值即直接跳转
private const val SEARCH_ACTION_SCROLL_RANGE = 10

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

// 拉黑确认对话框：删除与否以选中高亮表达（与切换歌单、排序方式同一交互），默认选中「仅拉黑歌曲」。
// 选中删除项后确认按钮改为「拉黑并删除」并显示不可恢复提示：不可逆动作既不在选项行上直接触发，
// 又在最终按钮上直接可见
@Composable
private fun BlacklistConfirmDialog(
    track: MusicTrack?,
    onConfirm: (MusicTrack, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (track == null) return
    // 选项态随对话框存活：关闭时目标置空、本组件不再组合，下次打开回到默认选项
    var deleteFile by remember { mutableStateOf(false) }
    RemoveTrackDialog(
        track = track,
        titleRes = R.string.playlist_blacklist_title,
        messageRes = R.string.playlist_blacklist_message,
        confirmRes = if (deleteFile) R.string.playlist_blacklist_delete_confirm
            else R.string.playlist_blacklist_confirm,
        option = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DialogOption(
                    label = stringResource(R.string.playlist_blacklist_option_block_only),
                    selected = !deleteFile,
                    onClick = { deleteFile = false },
                )
                DialogOption(
                    label = stringResource(R.string.playlist_blacklist_delete_option),
                    selected = deleteFile,
                    onClick = { deleteFile = true },
                )
                if (deleteFile) {
                    Text(
                        text = stringResource(R.string.playlist_blacklist_delete_hint),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        onConfirm = { onConfirm(it, deleteFile) },
        onDismiss = onDismiss,
    )
}

// 排序对话框：外壳与切换歌单面板一致（全宽圆角、同高），标题居中、右侧小字「逆序/正序」切换方向，字段列表居中高亮
@Composable
private fun PlaylistSortDialog(
    visible: Boolean,
    currentField: PlaylistSortField,
    descending: Boolean,
    onApply: (PlaylistSortField, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    // 方向本地态：点击标题右侧文案即时切换生效但不关闭对话框，便于连续调整字段与方向
    var reverse by remember(visible) { mutableStateOf(descending) }
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.music_panel_sort_title),
        // 标题右侧小字：文案为可切换到的目标方向（当前正序显示「逆序」）
        trailing = {
            Text(
                text = stringResource(
                    if (reverse) R.string.music_panel_sort_ascending
                    else R.string.music_panel_sort_descending
                ),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        reverse = !reverse
                        onApply(currentField, reverse)
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        },
        // 标题左对齐：标题区右侧带文字按钮（逆序/正序）时，左对齐标题视觉更均衡、更美观
        titleAlignment = TextAlign.Start,
        contentHeightFraction = DIALOG_LIST_HEIGHT_FRACTION,
    ) {
        PlaylistSortField.entries.forEach { field ->
            DialogOption(
                label = stringResource(sortFieldLabelRes(field)),
                selected = currentField == field,
                onClick = {
                    onApply(field, reverse)
                    onDismiss()
                },
            )
        }
    }
}

// 排序字段对应的文案资源
private fun sortFieldLabelRes(field: PlaylistSortField): Int = when (field) {
    PlaylistSortField.DEFAULT -> R.string.music_panel_sort_default
    PlaylistSortField.FAVORITE -> R.string.music_panel_sort_by_favorite
    PlaylistSortField.MODIFIED_TIME -> R.string.music_panel_sort_modified
    PlaylistSortField.TITLE -> R.string.music_panel_sort_by_title
    PlaylistSortField.ARTIST -> R.string.music_panel_sort_by_artist
    PlaylistSortField.ALBUM -> R.string.music_panel_sort_by_album
    PlaylistSortField.DURATION -> R.string.music_panel_sort_by_duration
}
