package com.yichao.evilgodxu.screens.spectrum

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yichao.evilgodxu.LocalApplication
import com.yichao.evilgodxu.LocalPlaybackState
import com.yichao.evilgodxu.screens.spectrum.assembly.SpectrumAssembly
import com.yichao.evilgodxu.theme.StatusBarStyleEffect
import com.yichao.evilgodxu.windowsize.rememberWindowSizeClass

// 页面入口：分发与副作用，不承载布局
@Composable
fun SpectrumScreen(
    trackId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalApplication.current
    val playbackState = LocalPlaybackState.current
    val viewModel: SpectrumViewModel = viewModel(
        // 按曲目区分实例，避免复用分析状态
        key = "spectrum-$trackId",
        factory = viewModelFactory {
            initializer {
                SpectrumViewModel(
                    application = application,
                    playbackState = playbackState,
                    trackId = trackId,
                )
            }
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 状态栏图标跟随主题
    StatusBarStyleEffect()

    // 尺寸类在此计算后下传
    SpectrumAssembly(
        windowSizeClass = rememberWindowSizeClass(),
        uiState = uiState,
        onBack = onBack,
        modifier = modifier,
    )
}
