package com.yichao.evilgodxu.screens.settings.level.storage

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.cache.CacheCategory
import com.yichao.evilgodxu.data.cache.CacheScope
import com.yichao.evilgodxu.data.cache.CacheUsage
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.theme.MyApplicationTheme
import com.yichao.evilgodxu.ui.component.section.GroupCard
import com.yichao.evilgodxu.utils.formatBytes

// 提示行展开后高度 40dp
private val REFRESH_ROW_HEIGHT = 40.dp

// 缓存明细：按归属分卡片，末尾合计与清理
@Composable
internal fun CacheUsageGroups(
    usages: List<CacheUsage>,
    clearing: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    innerPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val refreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = refreshState,
        // 不用默认指示器，改由顶部加载行提示
        indicator = {},
        modifier = modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 提示行参与布局，展开时下推明细
            RefreshLoadingRow(refreshState, refreshing)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 无底部栏代管，末项避让导航栏
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CacheUsageCard(R.string.cache_group_system, usages, CacheScope.CLEARABLE)
                CacheUsageCard(R.string.cache_group_private, usages, CacheScope.APP_DATA)
                CacheUsageCard(R.string.cache_group_user, usages, CacheScope.USER_DATA)
                Text(
                    text = stringResource(R.string.cache_total, formatBytes(usages.sumOf { it.sizeBytes })),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                    textAlign = TextAlign.Center,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                // 与可清理卡片同口径：无文件即无可清理
                val hasClearable = usages.any { it.scope == CacheScope.CLEARABLE && it.fileCount > 0 }
                CacheClearAction(
                    clearing = clearing,
                    hasClearable = hasClearable,
                    onClear = onClear,
                )
            }
        }
    }
}

// 提示行随下拉展开，按整行裁剪；分下拉/松开两段
@Composable
private fun RefreshLoadingRow(state: PullToRefreshState, refreshing: Boolean) {
    val expanded = state.distanceFraction.coerceIn(0f, 1f)
    // 收尾期间保持加载中，收起后再复位
    var settlingAfterRefresh by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing, expanded <= 0f) {
        when {
            refreshing -> settlingAfterRefresh = true
            expanded <= 0f -> settlingAfterRefresh = false
        }
    }
    // 手势驱动为下拉，动画为收回
    val pulling = !state.isAnimating
    val loading = refreshing || settlingAfterRefresh
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(REFRESH_ROW_HEIGHT * expanded)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .requiredHeight(REFRESH_ROW_HEIGHT),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.cache_refreshing),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 收回途中不显示文案
                pulling -> Text(
                    text = stringResource(
                        if (expanded >= 1f) R.string.cache_release_refresh
                        else R.string.cache_pull_refresh
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// 空组整卡略去
@Composable
private fun CacheUsageCard(
    @StringRes titleRes: Int,
    usages: List<CacheUsage>,
    scope: CacheScope,
) {
    val group = usages.filter { it.scope == scope }
    if (group.isEmpty()) return
    GroupCard(title = stringResource(titleRes)) {
        group.forEach { usage -> CacheUsageRow(usage) }
    }
}

// 单行：分类名 + 文件数与大小
@Composable
private fun CacheUsageRow(usage: CacheUsage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(usage.category.labelRes),
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = pluralStringResource(
                R.plurals.cache_usage,
                usage.fileCount,
                usage.fileCount,
                formatBytes(usage.sizeBytes),
            ),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

// 分类名取自资源
@get:StringRes
private val CacheCategory.labelRes: Int
    get() = when (this) {
        CacheCategory.IMAGE -> R.string.cache_category_image
        CacheCategory.TEMP_FILE -> R.string.cache_category_temp
        CacheCategory.LOG -> R.string.cache_category_log
        CacheCategory.LYRIC -> R.string.cache_category_lyric
        CacheCategory.AUDIO -> R.string.cache_category_audio
        CacheCategory.UPDATE_PACKAGE -> R.string.cache_category_update
        CacheCategory.PLAYLIST -> R.string.cache_category_playlist
        CacheCategory.ARTIST_ORDER -> R.string.cache_category_artist_order
        CacheCategory.ALBUM_ORDER -> R.string.cache_category_album_order
        CacheCategory.FAVORITE_ORDER -> R.string.cache_category_favorite_order
        CacheCategory.PROXY_SOURCE -> R.string.cache_category_proxy_source
        CacheCategory.RECENT_PLAYED -> R.string.cache_category_recent_played
        CacheCategory.SEARCH_HISTORY -> R.string.cache_category_search_history
        CacheCategory.PLAYLIST_CACHE -> R.string.cache_category_playlist_cache
        CacheCategory.BOOT_MIRROR -> R.string.cache_category_boot_mirror
        CacheCategory.UPDATE_INFO -> R.string.cache_category_update_info
        CacheCategory.SETTINGS -> R.string.cache_category_settings
        CacheCategory.HIGHLIGHT -> R.string.cache_category_highlight
        CacheCategory.OTHER_CACHE -> R.string.cache_category_other_cache
        CacheCategory.OTHER_APP_DATA -> R.string.cache_category_other_app_data
        CacheCategory.OTHER_PERSISTED -> R.string.cache_category_other
    }

// 预览样本：覆盖两种归属
private fun previewUsages() = listOf(
    CacheUsage(CacheCategory.IMAGE, CacheScope.CLEARABLE, fileCount = 128, sizeBytes = 24L * 1024 * 1024),
    CacheUsage(CacheCategory.AUDIO, CacheScope.USER_DATA, fileCount = 12, sizeBytes = 156L * 1024 * 1024),
    CacheUsage(CacheCategory.LOG, CacheScope.CLEARABLE, fileCount = 6, sizeBytes = 512L * 1024),
)

@Preview(name = "存储管理 · 窄窗", widthDp = 412, heightDp = 860, showBackground = true)
@Composable
private fun CacheUsageGroupsPreview() {
    MyApplicationTheme {
        CacheUsageGroups(
            usages = previewUsages(),
            clearing = false,
            refreshing = false,
            onRefresh = {},
            onClear = {},
            innerPadding = PaddingValues(0.dp),
        )
    }
}
