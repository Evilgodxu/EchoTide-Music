package com.yichao.evilgodxu.screens.cache.assembly

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.cache.CacheCategory
import com.yichao.evilgodxu.data.cache.CacheScope
import com.yichao.evilgodxu.data.cache.CacheUsage
import com.yichao.evilgodxu.screens.cache.CacheUiState
import com.yichao.evilgodxu.screens.cache.component.CacheUsageGroups
import com.yichao.evilgodxu.theme.MyApplicationTheme
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

// 预览用样本：三类占用覆盖可清理与用户数据两种归属
private fun previewCacheState() = CacheUiState(
    usages = listOf(
        CacheUsage(CacheCategory.IMAGE, CacheScope.CLEARABLE, fileCount = 128, sizeBytes = 24L * 1024 * 1024),
        CacheUsage(CacheCategory.AUDIO, CacheScope.USER_DATA, fileCount = 12, sizeBytes = 156L * 1024 * 1024),
        CacheUsage(CacheCategory.LOG, CacheScope.CLEARABLE, fileCount = 6, sizeBytes = 512L * 1024),
    ),
)

@Composable
private fun CacheAssemblyPreviewContent(windowSizeClass: WindowSizeClass) {
    MyApplicationTheme {
        CacheAssembly(
            windowSizeClass = windowSizeClass,
            uiState = previewCacheState(),
            onBack = {},
            onClearCache = {},
            onRefresh = {},
        )
    }
}

@Preview(name = "缓存 · 窄窗", widthDp = 412, heightDp = 860, showBackground = true)
@Composable
private fun CacheAssemblyCompactPreview() = CacheAssemblyPreviewContent(WindowSizeClass.Compact)

@Preview(name = "缓存 · 宽窗", widthDp = 1000, heightDp = 700, showBackground = true)
@Composable
private fun CacheAssemblyExpandedPreview() = CacheAssemblyPreviewContent(WindowSizeClass.Expanded)
