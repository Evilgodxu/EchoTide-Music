package com.yichao.evilgodxu.screens.cache.assembly

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.cache.CacheUiState
import com.yichao.evilgodxu.screens.cache.component.CacheUsageGroups
import com.yichao.evilgodxu.ui.component.ContentPane
import com.yichao.evilgodxu.ui.component.PageTopBar
import com.yichao.evilgodxu.windowsize.WindowSizeClass
import com.yichao.evilgodxu.windowsize.contentMaxWidth

// 缓存页组装器：骨架为 Scaffold + 单栏内容区，形态差异经 ContentPane 的行宽上限表达；
// 清理入口随合计占用收在分组末尾
@Composable
internal fun CacheAssembly(
    windowSizeClass: WindowSizeClass,
    uiState: CacheUiState,
    onBack: () -> Unit,
    onClearCache: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            PageTopBar(title = stringResource(R.string.cache_screen_title), onBack = onBack)
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        ContentPane(
            maxContentWidth = windowSizeClass.contentMaxWidth,
            modifier = Modifier.fillMaxSize(),
        ) {
            CacheUsageGroups(
                usages = uiState.usages,
                clearing = uiState.clearing,
                refreshing = uiState.refreshing,
                onRefresh = onRefresh,
                onClear = onClearCache,
                innerPadding = innerPadding,
            )
        }
    }
}
