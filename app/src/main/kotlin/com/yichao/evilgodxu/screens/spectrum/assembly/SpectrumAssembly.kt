package com.yichao.evilgodxu.screens.spectrum.assembly

import androidx.compose.foundation.layout.PaddingValues
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
import com.yichao.evilgodxu.windowsize.WindowSizeClass

// 内容留白：窄窗横向空间紧张，图不设左右边距、只在底部留余量；
// 宽窗可视区充裕，四周留白让时频图与屏幕边缘分离。
// 顶部一律不留白：图与标题区的间距由内容区自身固定为 4dp，两处叠加会拉大间距
private val COMPACT_CONTENT_PADDING = PaddingValues(bottom = 8.dp)
private val EXPANDED_CONTENT_PADDING = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp)

// 频谱页组装器：骨架为 Scaffold + 内容区，形态差异只体现在内容留白
@Composable
internal fun SpectrumAssembly(
    windowSizeClass: WindowSizeClass,
    uiState: SpectrumUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentPadding = if (windowSizeClass == WindowSizeClass.Compact) {
        COMPACT_CONTENT_PADDING
    } else {
        EXPANDED_CONTENT_PADDING
    }
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(contentPadding),
        )
    }
}
