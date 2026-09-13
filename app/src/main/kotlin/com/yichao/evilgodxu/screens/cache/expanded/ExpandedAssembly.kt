package com.yichao.evilgodxu.screens.cache.expanded

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.cache.CacheUiState
import com.yichao.evilgodxu.screens.cache.component.CacheUsageGroups
import com.yichao.evilgodxu.ui.component.PageTopBar

// 宽屏下缓存内容的可读宽度上限，避免超宽窗口把明细行拉伸过长
private val CACHE_CONTENT_MAX_WIDTH = 720.dp

// 宽屏组装器：限宽居中的缓存分组，清理入口随合计占用收在末尾
@Composable
internal fun ExpandedAssembly(
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            CacheUsageGroups(
                usages = uiState.usages,
                clearing = uiState.clearing,
                onClear = onClearCache,
                innerPadding = innerPadding,
                modifier = Modifier.widthIn(max = CACHE_CONTENT_MAX_WIDTH),
            )
        }
    }
}
