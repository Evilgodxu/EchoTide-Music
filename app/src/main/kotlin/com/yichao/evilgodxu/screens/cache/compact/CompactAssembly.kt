package com.yichao.evilgodxu.screens.cache.compact

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.cache.CacheUiState
import com.yichao.evilgodxu.screens.cache.component.CacheClearBar
import com.yichao.evilgodxu.screens.cache.component.CacheUsageGroups
import com.yichao.evilgodxu.ui.component.PageTopBar

// 窄屏组装器：常驻标题栏 + 满宽缓存分组 + 底部清理栏
@Composable
internal fun CompactAssembly(
    uiState: CacheUiState,
    onBack: () -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            PageTopBar(title = stringResource(R.string.cache_screen_title), onBack = onBack)
        },
        bottomBar = {
            CacheClearBar(
                totalBytes = uiState.usages.sumOf { it.sizeBytes },
                clearing = uiState.clearing,
                onClear = onClearCache,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        CacheUsageGroups(usages = uiState.usages, innerPadding = innerPadding)
    }
}
