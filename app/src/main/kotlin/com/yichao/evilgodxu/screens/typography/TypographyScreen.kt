package com.yichao.evilgodxu.screens.typography

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yichao.evilgodxu.LocalApplication
import com.yichao.evilgodxu.screens.typography.compact.CompactAssembly
import com.yichao.evilgodxu.screens.typography.expanded.ExpandedAssembly
import com.yichao.evilgodxu.theme.StatusBarStyleEffect
import com.yichao.evilgodxu.windowsize.rememberExpandedForm

// 页面入口：形态分发 + 跨形态副作用，不承载布局
@Composable
fun TypographyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalApplication.current
    val viewModel: TypographyViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                TypographyViewModel(application = application)
            }
        },
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 状态栏图标跟随主题：浅色主题深色图标，深色主题白色图标
    StatusBarStyleEffect()

    // 形态分派：旋转状态与窗口宽度尺寸类共同决定显示内容
    if (rememberExpandedForm()) {
        ExpandedAssembly(
            uiState = uiState,
            onBack = onBack,
            onMusicPanelFontSizeChange = viewModel::adjustMusicPanelFontSize,
            onMusicPanelLinesChange = viewModel::adjustMusicPanelLines,
            onMusicPanelLineSpacingChange = viewModel::adjustMusicPanelLineSpacing,
            onHomePortraitFontSizeChange = viewModel::adjustHomePortraitFontSize,
            onHomePortraitLinesChange = viewModel::adjustHomePortraitLines,
            onHomePortraitLineSpacingChange = viewModel::adjustHomePortraitLineSpacing,
            onLandscapeFontSizeChange = viewModel::adjustLandscapeFontSize,
            onLandscapeLinesChange = viewModel::adjustLandscapeLines,
            onLandscapeLineSpacingChange = viewModel::adjustLandscapeLineSpacing,
            onLandscape3DChange = viewModel::adjustLandscape3D,
            modifier = modifier,
        )
    } else {
        CompactAssembly(
            uiState = uiState,
            onBack = onBack,
            onMusicPanelFontSizeChange = viewModel::adjustMusicPanelFontSize,
            onMusicPanelLinesChange = viewModel::adjustMusicPanelLines,
            onMusicPanelLineSpacingChange = viewModel::adjustMusicPanelLineSpacing,
            onHomePortraitFontSizeChange = viewModel::adjustHomePortraitFontSize,
            onHomePortraitLinesChange = viewModel::adjustHomePortraitLines,
            onHomePortraitLineSpacingChange = viewModel::adjustHomePortraitLineSpacing,
            onLandscapeFontSizeChange = viewModel::adjustLandscapeFontSize,
            onLandscapeLinesChange = viewModel::adjustLandscapeLines,
            onLandscapeLineSpacingChange = viewModel::adjustLandscapeLineSpacing,
            onLandscape3DChange = viewModel::adjustLandscape3D,
            modifier = modifier,
        )
    }
}
