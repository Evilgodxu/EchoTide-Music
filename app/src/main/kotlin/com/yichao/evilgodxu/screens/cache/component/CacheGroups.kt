package com.yichao.evilgodxu.screens.cache.component

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.cache.CacheCategory
import com.yichao.evilgodxu.data.cache.CacheScope
import com.yichao.evilgodxu.data.cache.CacheUsage
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.section.GroupCard
import com.yichao.evilgodxu.utils.formatBytes

// 刷新提示完全展开后的行高，展开过程中据此按比例取当前行高
private val REFRESH_ROW_HEIGHT = 40.dp

// 缓存明细：按归属方分卡片展示；合计与清理入口收在末尾，清理只作用于「可清理」卡片。
// 明细随内容滚动，故下拉刷新的手势载体也落在此处，两种形态共用同一份刷新行为
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
        // 默认是自顶部拉下的圆形图标；此处不用指示器，改由内容顶部的加载行承担提示
        indicator = {},
        modifier = modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 提示行参与内容布局而非覆盖其上，展开时把明细整体下推
            RefreshLoadingRow(refreshState, refreshing)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // 宿主为 edge-to-edge 且已无底部栏代管，末项须自行避让系统导航栏
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
                // 与「可清理」卡片同一判定口径：该作用域内一个文件都没有时才算无可清理项
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

// 刷新提示行：行高随下拉距离自 0 长到 REFRESH_ROW_HEIGHT，随内容一起向下展开；
// 行内按整行高度布局再整体裁剪，故展开途中文字只是被逐段露出，不会随行高被压扁。
// 未达阈值松手并不会刷新，故下拉提示分「下拉」「松开」两段
@Composable
private fun RefreshLoadingRow(state: PullToRefreshState, refreshing: Boolean) {
    val expanded = state.distanceFraction.coerceIn(0f, 1f)
    // 刷新结束后状态还要动画收回，这段收尾继续按「加载中」呈现：若这时回落到下拉文案，
    // 看着就像又被拉了一次。行完全收起即复位，下次下拉重新从「下拉」开始
    var settlingAfterRefresh by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing, expanded <= 0f) {
        when {
            refreshing -> settlingAfterRefresh = true
            expanded <= 0f -> settlingAfterRefresh = false
        }
    }
    // 下拉由手势直接驱动状态（snapTo），收回才是状态自己在动画，据此区分二者
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
                // 收回途中（未刷新的取消下拉）不给文案：此时既非下拉也非刷新
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

// 单张归属卡片：空组整卡略去，未采到数据时不留空标题
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

// 单行缓存占用：分类名 + 文件数与占用大小
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
            text = stringResource(R.string.cache_usage, usage.fileCount, formatBytes(usage.sizeBytes)),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

// 分类展示名一律取自资源，与日志台账中的内部标签互不牵连
@get:StringRes
private val CacheCategory.labelRes: Int
    get() = when (this) {
        CacheCategory.IMAGE -> R.string.cache_category_image
        CacheCategory.TEMP_FILE -> R.string.cache_category_temp
        CacheCategory.LOG -> R.string.cache_category_log
        CacheCategory.LYRIC -> R.string.cache_category_lyric
        CacheCategory.AUDIO -> R.string.cache_category_audio
        CacheCategory.UPDATE_PACKAGE -> R.string.cache_category_update
        CacheCategory.ANALYSIS -> R.string.cache_category_analysis
        CacheCategory.PREFERENCE -> R.string.cache_category_preference
    }
