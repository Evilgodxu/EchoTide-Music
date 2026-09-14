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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.LocalMetadataEnricher
import com.yichao.evilgodxu.LocalPlaylistRefresher
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.PlaylistSortField
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.data.music.playback.togglePlayPause
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.BottomSearchBarOverlay
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
    // 排序对话框显隐
    var showSortDialog by remember { mutableStateOf(false) }
    // 长按删除目标：非空时显示确认弹窗
    var deleteTrack by remember { mutableStateOf<MusicTrack?>(null) }
    // 后台预取整个播放列表缩略图：曲目集合变化即触发，不等面板展开逐行懒加载，
    // 展开时封面已就绪；幂等，已缓存/补全中/全量补全中的曲目自动跳过
    val playlistTrackIds = remember(playbackState.playlist) { playbackState.playlist.map { it.id } }
    LaunchedEffect(playlistTrackIds) {
        val currentId = playbackState.currentTrack?.id
        playbackState.playlist
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
                    // 歌单副标题：浅色小字常驻显示，点击快捷切换歌单；默认列表显示默认播放列表
                    Text(
                        text = playbackState.playlistSource?.name
                            ?: stringResource(R.string.playlist_switch_default),
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
                            text = stringResource(R.string.music_panel_track_count, playbackState.playlist.size),
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
                            enabled = playbackState.playlistSource == null && !playbackState.isScanning,
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
                } else if (playbackState.playlist.isEmpty()) {
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
                    // 过滤后仍保留原队列索引：点击播放与定位需回填真实索引
                    // 索引仅来自当前 playlist 快照；playlist 收缩后布局期可能读到过期索引，须容忍缺失
                    val filteredIndices = remember(playbackState.playlist, searchQuery) {
                        if (searchQuery.isBlank()) {
                            playbackState.playlist.indices.toList()
                        } else {
                            playbackState.playlist.indices.filter { index ->
                                val track = playbackState.playlist.getOrNull(index) ?: return@filter false
                                track.title.contains(searchQuery, ignoreCase = true) ||
                                    track.artist.contains(searchQuery, ignoreCase = true)
                            }
                        }
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
                                    key = { _, index -> playbackState.playlist.getOrNull(index)?.id ?: -1L },
                                ) { _, index ->
                                    val track = playbackState.playlist.getOrNull(index) ?: return@itemsIndexed
                                    val isActive = index == playbackState.currentIndex
                                    PlaylistRow(
                                        track = track,
                                        isActive = isActive,
                                        isPlaying = isActive && playbackState.isPlaying,
                                        isQueued = playbackState.isInPlayNext(track.id),
                                        onClick = {
                                            keyboardController?.hide()
                                            if (isActive) {
                                                togglePlayPause(playbackState)
                                            } else {
                                                scope.launch { playTrackAt(context, playbackState, index) }
                                            }
                                            onDismiss()
                                        },
                                        onLongClick = { deleteTrack = track },
                                        onFavoriteClick = { playbackState.toggleFavorite(track.id) },
                                        onPlayNextClick = { playbackState.togglePlayNext(track) },
                                    )
                                }
                            }
                            // 面板展开动画完成后：始终将当前曲目滚动到列表居中位置
                            LaunchedEffect(playlistSettled) {
                                if (playlistSettled && searchQuery.isBlank() && playbackState.currentIndex >= 0 && playbackState.playlist.isNotEmpty()) {
                                    listState.scrollPlaylistTo(
                                        playbackState.currentIndex.coerceIn(0, playbackState.playlist.size - 1),
                                        forceCenter = true
                                    )
                                }
                            }
                            // 切歌时定位：当前曲目不在可视区内才滚动到居中位置，避免反复滚动卡顿
                            LaunchedEffect(playbackState.currentTrack?.id) {
                                if (playlistSettled && searchQuery.isBlank() && playbackState.currentIndex >= 0 && playbackState.playlist.isNotEmpty()) {
                                    listState.scrollPlaylistTo(
                                        playbackState.currentIndex.coerceIn(0, playbackState.playlist.size - 1)
                                    )
                                }
                            }
                            // 手动定位播放：待过滤结果与当前曲目对齐后居中滚动。
                            // 当前曲目被过滤掉时上游会清空关键词，此处等重组恢复全量队列再定位
                            LaunchedEffect(pendingLocate, filteredIndices) {
                                if (!pendingLocate) return@LaunchedEffect
                                val position = filteredIndices.indexOf(playbackState.currentIndex)
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
                                    enabled = playbackState.currentIndex >= 0,
                                    onClick = {
                                        // 当前曲目被过滤掉则先清空关键词，否则该按钮永远定位不到目标
                                        if (!filteredIndices.contains(playbackState.currentIndex)) {
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
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 方向本地态：点击标题右侧文案即时切换生效但不关闭对话框，便于连续调整字段与方向
    var reverse by remember(visible) { mutableStateOf(descending) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.36f)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.music_panel_sort_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 标题右侧小字：文案为可切换到的目标方向（当前正序显示「逆序」）
                Text(
                    text = stringResource(
                        if (reverse) R.string.music_panel_sort_ascending
                        else R.string.music_panel_sort_descending
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            reverse = !reverse
                            onApply(currentField, reverse)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PlaylistSortField.entries.forEach { field ->
                    val isSelected = currentField == field
                    Text(
                        text = stringResource(sortFieldLabelRes(field)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    isSelected && isDarkTheme -> MaterialTheme.colorScheme.primaryContainer
                                    isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else -> MaterialTheme.colorScheme.surface
                                }
                            )
                            .clickable {
                                onApply(field, reverse)
                                onDismiss()
                            }
                            .padding(vertical = 14.dp),
                        textAlign = TextAlign.Center,
                        color = when {
                            isSelected && isDarkTheme -> MaterialTheme.colorScheme.onPrimaryContainer
                            isSelected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

// 排序字段对应的文案资源
private fun sortFieldLabelRes(field: PlaylistSortField): Int = when (field) {
    PlaylistSortField.DEFAULT -> R.string.music_panel_sort_default
    PlaylistSortField.MODIFIED_TIME -> R.string.music_panel_sort_modified
    PlaylistSortField.TITLE -> R.string.music_panel_sort_by_title
    PlaylistSortField.ARTIST -> R.string.music_panel_sort_by_artist
    PlaylistSortField.ALBUM -> R.string.music_panel_sort_by_album
    PlaylistSortField.DURATION -> R.string.music_panel_sort_by_duration
}
