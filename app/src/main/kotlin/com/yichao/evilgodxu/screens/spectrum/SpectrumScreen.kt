package com.yichao.evilgodxu.screens.spectrum

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yichao.evilgodxu.LocalMusicPanelStateHolder
import com.yichao.evilgodxu.screens.spectrum.compact.CompactAssembly
import com.yichao.evilgodxu.screens.spectrum.expanded.ExpandedAssembly
import com.yichao.evilgodxu.theme.StatusBarStyleEffect
import com.yichao.evilgodxu.windowsize.rememberExpandedForm

// 页面入口：形态分发 + 跨形态副作用，不承载布局
@Composable
fun SpectrumScreen(
    trackId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateHolder = LocalMusicPanelStateHolder.current
    val viewModel: SpectrumViewModel = viewModel(
        // 按曲目区分实例：同一路由重复进入不同曲目时不复用上一次的分析状态
        key = "spectrum-$trackId",
        factory = viewModelFactory {
            initializer { SpectrumViewModel(stateHolder = stateHolder, trackId = trackId) }
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 状态栏图标跟随主题：浅色主题深色图标，深色主题白色图标
    StatusBarStyleEffect()

    // 形态分派：旋转状态与窗口宽度尺寸类共同决定显示内容
    if (rememberExpandedForm()) {
        ExpandedAssembly(uiState = uiState, onBack = onBack, modifier = modifier)
    } else {
        CompactAssembly(uiState = uiState, onBack = onBack, modifier = modifier)
    }
}
