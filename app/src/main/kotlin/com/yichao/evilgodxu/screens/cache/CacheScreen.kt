package com.yichao.evilgodxu.screens.cache

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yichao.evilgodxu.LocalApplication
import com.yichao.evilgodxu.screens.cache.compact.CompactAssembly
import com.yichao.evilgodxu.screens.cache.expanded.ExpandedAssembly
import com.yichao.evilgodxu.theme.StatusBarStyleEffect
import com.yichao.evilgodxu.windowsize.rememberExpandedForm

// 页面入口：形态分发 + 跨形态副作用，不承载布局
@Composable
fun CacheScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalApplication.current
    val viewModel: CacheViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                CacheViewModel(application = application)
            }
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 状态栏图标跟随主题：浅色主题深色图标，深色主题白色图标
    StatusBarStyleEffect()
    // 进入页面即采样一次，回到前台同样重采，使占用不受后台期间缓存涨落影响
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // 形态分派：旋转状态与窗口宽度尺寸类共同决定显示内容
    if (rememberExpandedForm()) {
        ExpandedAssembly(
            uiState = uiState,
            onBack = onBack,
            onClearCache = viewModel::clearSystemCache,
            onRefresh = viewModel::refresh,
            modifier = modifier,
        )
    } else {
        CompactAssembly(
            uiState = uiState,
            onBack = onBack,
            onClearCache = viewModel::clearSystemCache,
            onRefresh = viewModel::refresh,
            modifier = modifier,
        )
    }
}
