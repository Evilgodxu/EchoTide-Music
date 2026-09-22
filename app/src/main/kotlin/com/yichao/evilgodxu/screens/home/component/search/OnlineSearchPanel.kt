package com.yichao.evilgodxu.screens.home.component.search

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.api.MusicQuality
import com.yichao.evilgodxu.data.music.blacklist.BlacklistStore
import com.yichao.evilgodxu.data.music.model.MusicSearchSource
import com.yichao.evilgodxu.data.music.panel.performSearch
import com.yichao.evilgodxu.data.music.panel.playSearchResultWithQuality
import com.yichao.evilgodxu.data.music.panel.tryPlayLocalMatch
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.LocalMetadataEnricher
import com.yichao.evilgodxu.LocalPlaylistRefresher
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.QualityOptionCard
import com.yichao.evilgodxu.ui.component.dialog.SearchResultsLazyList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 首页专属在线搜索面板：搜索输入/历史/结果逻辑与其样式在此独立封装
@Composable
internal fun OnlineSearchPanel(
    playbackState: MusicPlaybackState,
    menuBackgroundColor: Color,
    // 本页是否在前台可见：自动轮播等持续动效据此决定是否推进
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    // 搜索输入框聚焦状态：键盘展开期间显示拦截层，点击面板空白处仅收起键盘并阻断透传
    var searchInputFocused by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        // 拦截层置于内容之下：仅覆盖面板空白处，不抢占列表项/输入框等上层交互
        if (searchInputFocused) {
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
        // imePadding 收紧面板底部：键盘弹出时仅压缩结果区，输入框保持原位不被整窗顶起
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            PanelHeader()
            // 黑名单快照在推荐生成前载入：候选过滤与偏好基线都依赖它，未载入会误判为「无黑名单」
            LaunchedEffect(Unit) { BlacklistStore.ensureLoaded(context) }
            // 载入完成或黑名单变更时生成：以真实快照为准，避免先按空黑名单算出一版无效结果
            LaunchedEffect(BlacklistStore.isLoaded, BlacklistStore.keys) {
                if (BlacklistStore.isLoaded) playbackState.loadDailyRecommendations(context)
            }
            DailyRecommendCarousel(
                songs = playbackState.dailyRecommendedTracks,
                loading = playbackState.isDailyRecommendLoading,
                refreshing = playbackState.isChartPoolRefreshing,
                visible = visible,
                onSongClick = { song ->
                    // 推荐项与搜索结果同属在线歌曲，同样先由用户选定音质再解析播放地址
                    playbackState.qualityPickTrack = song
                    playbackState.qualityBusy = false
                    playbackState.qualityError = null
                },
                onBlacklist = { song -> playbackState.blacklistTrack(context, song.title, song.artist) },
                onRefresh = { playbackState.loadDailyRecommendations(context, force = true) },
            )
            Spacer(modifier = Modifier.height(8.dp))
            SearchInput(
                playbackState = playbackState,
                menuBackgroundColor = menuBackgroundColor,
                context = context,
                scope = scope,
                onFocusChanged = { searchInputFocused = it },
            )
            if (playbackState.showSearchResults) {
                SearchResultList(
                    playbackState = playbackState,
                    context = context,
                    scope = scope,
                )
            } else if (playbackState.searchHistory.isNotEmpty()) {
                SearchHistoryList(
                    playbackState = playbackState,
                    context = context,
                    scope = scope,
                )
            }
            // 音质选择对话框（独立窗口，不参与面板布局）
            SearchQualityDialog(
                playbackState = playbackState,
                context = context,
                scope = scope,
            )
        }
    }
}

// 面板标题栏：仅显示标题，关闭操作通过父级手势左滑或系统返回键完成
@Composable
private fun PanelHeader() {
    Text(
        text = stringResource(R.string.music_panel_search_title),
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
    )
}

// 搜索输入框：左侧放大镜点击弹出平台下拉列表，切换后带已有关键词自动重搜
@Composable
private fun SearchInput(
    playbackState: MusicPlaybackState,
    menuBackgroundColor: Color,
    context: Context,
    scope: CoroutineScope,
    onFocusChanged: (Boolean) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Transparent)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.45f),
                shape = RoundedCornerShape(24.dp)
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 平台切换触发器
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { sourceMenuExpanded = true }
                        .padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = AppIcons.Search,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = sourceName(playbackState.searchSource),
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Icon(
                        imageVector = AppIcons.ArrowDropDown,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                DropdownMenu(
                    expanded = sourceMenuExpanded,
                    onDismissRequest = { sourceMenuExpanded = false },
                    containerColor = menuBackgroundColor,
                ) {
                    MusicSearchSource.entries.forEach { source ->
                        DropdownMenuItem(
                            text = { Text(sourceName(source), color = Color.White) },
                            onClick = {
                                sourceMenuExpanded = false
                                playbackState.setSearchSource(source)
                                val query = playbackState.searchQuery.trim()
                                if (query.isNotBlank()) {
                                    scope.launch { performSearch(playbackState, context) }
                                }
                            },
                            trailingIcon = {
                                if (source == playbackState.searchSource) {
                                    Icon(
                                        imageVector = AppIcons.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                        )
                    }
                }
            }
            BasicTextField(
                value = playbackState.searchQuery,
                onValueChange = { playbackState.searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp)
                    .onFocusChanged { onFocusChanged(it.isFocused) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = Color.White,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        // 回车触发搜索时收起键盘
                        keyboardController?.hide()
                        val query = playbackState.searchQuery.trim()
                        if (query.isNotBlank()) {
                            scope.launch { performSearch(playbackState, context) }
                        }
                    }
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (playbackState.searchQuery.isEmpty()) {
                            Text(
                                text = stringResource(R.string.music_panel_search_placeholder),
                                color = Color.White.copy(alpha = 0.5f),
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
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Close,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// 平台名称文本
@Composable
private fun sourceName(source: MusicSearchSource): String = stringResource(
    when (source) {
        MusicSearchSource.NETEASE -> R.string.music_panel_search_source
        MusicSearchSource.QQ -> R.string.music_panel_search_source_qq
        MusicSearchSource.KUGOU -> R.string.music_panel_search_source_kugou
        MusicSearchSource.KUWO -> R.string.music_panel_search_source_kuwo
        MusicSearchSource.MIGU -> R.string.music_panel_search_source_migu
    }
)

// 搜索历史列表
@Composable
private fun SearchHistoryList(
    playbackState: MusicPlaybackState,
    context: Context,
    scope: CoroutineScope,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 15.dp)
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.music_panel_search_history),
            color = Color.White,
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
                tint = Color.White,
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
                    color = Color.White,
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
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
    }
}

// 搜索状态区：结果计数/刷新/加载/空/列表
@Composable
private fun SearchResultList(
    playbackState: MusicPlaybackState,
    context: Context,
    scope: CoroutineScope,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.music_panel_track_count, playbackState.searchResults.size),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
            IconButton(
                onClick = {
                    if (!playbackState.isSearching) {
                        scope.launch { performSearch(playbackState, context) }
                    }
                },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = AppIcons.Refresh,
                    contentDescription = null,
                    tint = if (playbackState.isSearching) Color.White.copy(alpha = 0.5f)
                    else Color.White
                )
            }
        }
        // 加载/空/结果间淡入淡出过渡，避免搜索结果生硬插入
        AnimatedContent(
            targetState = when {
                playbackState.isSearching -> 0
                playbackState.searchResults.isEmpty() -> 1
                else -> 2
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "search_state",
        ) { state ->
            when (state) {
                0 -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.music_panel_search_loading),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }
                1 -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.music_panel_search_no_results),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }
                else -> {
                    SearchResultsLazyList(
                        playbackState = playbackState,
                        context = context,
                        onResultClick = { result ->
                            // 本地曲库命中同曲直接播放；否则弹出音质选择对话框由用户选音质
                            scope.launch {
                                if (!tryPlayLocalMatch(result, playbackState, context, scope)) {
                                    playbackState.qualityPickTrack = result
                                    playbackState.qualityBusy = false
                                    playbackState.qualityError = null
                                }
                            }
                        },
                        titleColor = Color.White,
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

// 播放音质选择对话框（与 ui/component 的通用 QualitySelectDialog 是不同实现）：
// 携带待播曲目信息与尝试中状态，音质尝试失败时不关闭，保留供用户更换音质重试
@Composable
private fun SearchQualityDialog(
    playbackState: MusicPlaybackState,
    context: Context,
    scope: CoroutineScope,
) {
    val track = playbackState.qualityPickTrack ?: return
    val metadataEnricher = LocalMetadataEnricher.current
    val playlistRefresher = LocalPlaylistRefresher.current
    AlertDialog(
        onDismissRequest = {
            if (!playbackState.qualityBusy) {
                playbackState.qualityPickTrack = null
                playbackState.qualityError = null
            }
        },
        title = {
            Text(
                text = stringResource(R.string.music_panel_quality_title),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 待播歌曲信息
                Text(
                    text = listOf(track.title, track.artist)
                        .filter { it.isNotBlank() }
                        .joinToString(" - "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                // 音质档位卡片：尝试中整体禁用，防止并发重复尝试
                MusicQuality.entries.forEach { quality ->
                    QualityOptionCard(
                        label = stringResource(
                            when (quality) {
                                MusicQuality.LOSSLESS -> R.string.music_quality_lossless
                                MusicQuality.HIGH -> R.string.music_quality_high
                                MusicQuality.STANDARD -> R.string.music_quality_standard
                            }
                        ),
                        enabled = !playbackState.qualityBusy,
                        onClick = {
                            scope.launch {
                                playbackState.qualityBusy = true
                                playbackState.qualityError = null
                                val started = playSearchResultWithQuality(
                                    track, quality, playbackState, context,
                                    metadataEnricher, playlistRefresher,
                                )
                                // URL 解析失败直接提示；解析成功后保持忙碌态等待播放器就绪/失败回调结算
                                if (!started) {
                                    playbackState.qualityBusy = false
                                    playbackState.qualityError = context.getString(R.string.music_panel_quality_failed)
                                }
                            }
                        },
                    )
                }
                // 尝试中加载指示
                if (playbackState.qualityBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // 最近一次音质尝试失败提示
                if (playbackState.qualityError != null) {
                    Text(
                        text = playbackState.qualityError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {},
    )
}


