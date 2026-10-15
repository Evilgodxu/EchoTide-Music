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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.yichao.evilgodxu.data.music.model.NeteaseSongSearchResult
import com.yichao.evilgodxu.data.music.panel.loadMoreSearchResults
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.R
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
                AsyncImage(
                    model = coverModel,
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
    // 到底后的过拉量：决定提示行展开多少，同时标记本次上拉确有加载意图。
    // 取自原始指针位移而非滚动量 —— 列表到底后本就不再滚动，滚动量恒为零，方向与幅度都无从取得。
    // 以进入底部那一刻的手指位置为锚点，其后的净位移即过拉量：手指上移拉开、下移收回
    var pullDistance by remember { mutableFloatStateOf(0f) }
    // 本次触发是否仍在加载：不直接用 playbackState.isLoadingMore，因本地切分的分页不置该标记
    var loadInProgress by remember { mutableStateOf(false) }
    // 松手时自增以请求加载：加载要耗时，不压在指针回调里做
    var loadRequest by remember { mutableIntStateOf(0) }
    val fullPullPx = with(LocalDensity.current) { SEARCH_LOAD_ROW_FULL_PULL_DP.toPx() }
    // 手指离开屏幕即结算：本次上拉在底部拉出过溢出就加载下一页。不设距离门槛 ——
    // 列表已在底部时任何上拉都算明确的加载意图，免得同一位置要拉第二次；
    // 但此刻仍须确在底部，惯性把列表带离底部后残留的过拉量不该兑现成加载
    fun settlePull() {
        // 加载进行中抬手不计入，避免同一页被反复请求
        val shouldLoad = pullDistance > 0f && !loadInProgress && !listState.canScrollForward &&
            playbackState.hasMoreSearchResults &&
            !playbackState.isSearching && playbackState.searchResults.isNotEmpty()
        if (shouldLoad) {
            loadInProgress = true
            loadRequest++
        }
        pullDistance = 0f
    }
    // 松手后的加载：补足最短展开时长，使本地切分的秒回分页同样有可见的加载反馈
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
            // 过拉手势挂在列表外的容器上：列表自身随展开上移，挂在它身上会让拖动中途丢失指针。
            // 只读不消费，列表的滚动与结果行的点击都不受影响
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
                                // 列表还能继续上滚：内容仍在滚动，过拉尚未成立
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
