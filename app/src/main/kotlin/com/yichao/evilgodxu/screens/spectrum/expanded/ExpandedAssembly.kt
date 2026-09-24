package com.yichao.evilgodxu.screens.spectrum.expanded

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.spectrum.SpectrumUiState
import com.yichao.evilgodxu.screens.spectrum.component.SpectrumBody
import com.yichao.evilgodxu.ui.component.PageTopBar
import com.yichao.evilgodxu.windowsize.rememberWindowLandscape

// 宽屏下图的四周留白：可视区充裕，让时频图与屏幕边缘分离
private val EXPANDED_CONTENT_PADDING = 16.dp

// 宽屏组装器：常驻标题栏 + 留白内铺满的频谱图
@Composable
internal fun ExpandedAssembly(
    uiState: SpectrumUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            PageTopBar(
                title = uiState.title.ifBlank { stringResource(R.string.spectrum_screen_title) },
                onBack = onBack,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        SpectrumBody(
            uiState = uiState,
            // 方向按窗口实测朝向判定：宽屏也可能是竖握，图须与屏幕长边同向
            vertical = !rememberWindowLandscape(),
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(EXPANDED_CONTENT_PADDING),
        )
    }
}
