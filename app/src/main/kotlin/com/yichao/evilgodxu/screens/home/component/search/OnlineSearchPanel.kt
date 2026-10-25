package com.yichao.evilgodxu.screens.home.component.search

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.api.MusicQuality
import com.yichao.evilgodxu.data.music.panel.performSearch
import com.yichao.evilgodxu.data.music.panel.playSearchResultWithQuality
import com.yichao.evilgodxu.data.music.panel.tryPlayLocalMatch
import com.yichao.evilgodxu.data.music.playback.HighlightExitReason
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.LocalMetadataEnricher
import com.yichao.evilgodxu.LocalPlaylistRefresher
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.AppDialog
import com.yichao.evilgodxu.ui.component.DialogOption
import com.yichao.evilgodxu.ui.component.ExpandDirection
import com.yichao.evilgodxu.ui.component.ExpandPicker
import com.yichao.evilgodxu.ui.component.qualityLabelRes
import com.yichao.evilgodxu.ui.component.rememberOnlinePlatformOptions
import com.yichao.evilgodxu.ui.component.SEARCH_BAR_BORDER_ALPHA
import com.yichao.evilgodxu.ui.component.SEARCH_BAR_FILL_ALPHA
import com.yichao.evilgodxu.ui.component.dialog.SearchResultsLazyList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// 搜索条外形：44dp 胶囊 + 8% 白填充 + 22% 白描边。
// 填充取半透明白，使搜索条与下方的历史标签处在同一套质感语言里（配色不依赖封面底色）
private val SearchBarHeight = 44.dp
private val SearchBarShape = RoundedCornerShape(22.dp)
private val SearchBarFill = Color.White.copy(alpha = SEARCH_BAR_FILL_ALPHA)
private val SearchBarBorder = Color.White.copy(alpha = SEARCH_BAR_BORDER_ALPHA)

// 面板内相邻区块的统一纵向间距：标题、搜索条、历史标签、搜索结果一律取这一档。
// 四处各写一个数会让间距参差，故收敛成一个共用值，由本面板与其组件共同引用
internal val SearchPanelSectionGap = 12.dp

// 首页专属在线搜索面板：搜索输入/历史/结果逻辑与其样式在此独立封装
@Composable
internal fun OnlineSearchPanel(
    playbackState: MusicPlaybackState,
    menuBackgroundColor: Color,
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
        // 底部收紧：键盘展开时仅压缩结果区，输入框保持原位不被整窗顶起；键盘收起时让出系统导航栏高度
        // （首页全沉浸隐藏了两条系统栏，正常路径下该值为 0，保留是为了导航栏被外部显示出来时末项不被压住）。
        // 两者取并集而非叠加：键盘高度已含导航栏区域
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
        ) {
            PanelHeader()
            // 心动模式自行退出时说明原因 —— 否则用户只看到模式自己变了，会当成失灵
            LaunchedEffect(playbackState.highlightExitNotice) {
                val reason = playbackState.highlightExitNotice ?: return@LaunchedEffect
                playbackState.highlightExitNotice = null
                val message = when (reason) {
                    HighlightExitReason.NoChorus -> R.string.music_panel_highlight_exit_notice
                    HighlightExitReason.ImmersiveOutput ->
                        R.string.music_panel_highlight_exit_immersive_output
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            SearchInput(
                playbackState = playbackState,
                menuBackgroundColor = menuBackgroundColor,
                context = context,
                scope = scope,
                onFocusChanged = { searchInputFocused = it },
            )
            // 历史常驻搜索条下方：搜索结果不再顶掉历史，而是排在历史之后
            if (playbackState.searchHistory.isNotEmpty()) {
                SearchHistoryTags(playbackState = playbackState)
            }
            if (playbackState.showSearchResults) {
                SearchResultList(
                    playbackState = playbackState,
                    context = context,
                    scope = scope,
                    modifier = Modifier.weight(1f),
                )
            } else if (playbackState.searchHistory.isEmpty()) {
                // 既无历史也无结果：输入框下方整片空白，交代此处将出现什么
                SearchEmptyHint(modifier = Modifier.weight(1f))
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

// 面板标题栏：仅显示标题，关闭操作通过父级手势左滑或系统返回键完成。
// 下边距取统一区块间距，使标题与搜索条之间的间距同「搜索条与历史标签」一致
@Composable
private fun PanelHeader() {
    Text(
        text = stringResource(R.string.music_panel_search_title),
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = SearchPanelSectionGap)
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
    val platformOptions = rememberOnlinePlatformOptions()
    // 平台展示名：候选里查不到当前平台时（平台已随音源移除）回退平台键
    val currentPlatformName = platformOptions.firstOrNull { it.source == playbackState.searchSource }?.name
        ?: playbackState.searchSource.key
    // 当前选中平台：菜单高亮以完整候选为准
    val currentPlatform = platformOptions.firstOrNull { it.source == playbackState.searchSource }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp)
            .height(SearchBarHeight)
            .clip(SearchBarShape)
            .background(SearchBarFill)
            .border(
                width = 1.dp,
                color = SearchBarBorder,
                shape = SearchBarShape
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 平台切换触发器：向下展开的动画选择器
            ExpandPicker(
                options = platformOptions,
                selected = currentPlatform,
                expandDirection = ExpandDirection.Down,
                horizontalAlignment = Alignment.Start,
                containerColor = menuBackgroundColor,
                itemHighlightColor = Color.White.copy(alpha = 0.10f),
                trigger = {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = AppIcons.Search,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = currentPlatformName,
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                },
                itemContent = { platform, _ ->
                    Text(
                        text = platform.name,
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                },
                onItemClick = { platform ->
                    playbackState.setSearchSource(platform.source)
                    val query = playbackState.searchQuery.trim()
                    if (query.isNotBlank()) {
                        scope.launch { performSearch(playbackState, context) }
                    }
                },
            )
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

// 结果区空态：既无历史也无结果时输入框下方整片留白，交代此处将出现什么
@Composable
private fun SearchEmptyHint(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.music_panel_search_empty_hint),
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 12.sp,
        )
    }
}

// 搜索状态区：结果计数/刷新/加载/空/列表
@Composable
private fun SearchResultList(
    playbackState: MusicPlaybackState,
    context: Context,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // 上边距取统一区块间距：结果排在历史标签或搜索条之后，与上方保持同一档间距
            .padding(start = 12.dp, end = 12.dp, top = SearchPanelSectionGap)
    ) {
        val resultCount = playbackState.searchResults.size
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 标题贴住自己的内容，间距与历史区块同一规则
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 结果区块标题：与「最近搜索」共用同一款标题样式
            SearchSectionLabel(
                text = pluralStringResource(R.plurals.music_panel_search_result_count, resultCount, resultCount),
            )
            // 与历史区块的「清空」同一款动作按钮：图标尺寸、配色、内边距由组件统一
            SearchPanelAction(
                icon = AppIcons.Refresh,
                label = stringResource(R.string.music_panel_search_refresh),
                // 搜索进行中禁用：此时仍可点会让同一查询被反复发起
                enabled = !playbackState.isSearching,
                onClick = { scope.launch { performSearch(playbackState, context) } },
            )
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
                            // 本地曲库命中同曲直接播放；否则弹出音质选择对话框由用户选音质。
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

// 播放音质选择对话框：携带待播曲目信息与尝试中状态，音质尝试失败时不关闭，保留供用户更换音质重试
@Composable
private fun SearchQualityDialog(
    playbackState: MusicPlaybackState,
    context: Context,
    scope: CoroutineScope,
) {
    val track = playbackState.qualityPickTrack ?: return
    val metadataEnricher = LocalMetadataEnricher.current
    val playlistRefresher = LocalPlaylistRefresher.current
    // 正在尝试的档位：加载指示渲染在该档位行内。对话框重开（换曲）时随组合重建自动复位
    var pendingQuality by remember { mutableStateOf<MusicQuality?>(null) }
    // 尝试进行中不响应收起，避免归还对话框后解析回调丢失宿主
    val dismiss = {
        if (!playbackState.qualityBusy) {
            playbackState.qualityPickTrack = null
            playbackState.qualityError = null
        }
    }
    AppDialog(
        onDismiss = dismiss,
        title = stringResource(R.string.music_panel_quality_title),
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
        // 音质档位卡片：尝试中整体禁用，防止并发重复尝试；仅列用户可选档位
        MusicQuality.entries.filter { it.userSelectable }.forEach { quality ->
            DialogOption(
                label = stringResource(qualityLabelRes(quality)),
                enabled = !playbackState.qualityBusy,
                // 加载态挂到被点选的那一行，而不是另起一行把对话框撑高
                loading = playbackState.qualityBusy && pendingQuality == quality,
                onClick = {
                    pendingQuality = quality
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
}
