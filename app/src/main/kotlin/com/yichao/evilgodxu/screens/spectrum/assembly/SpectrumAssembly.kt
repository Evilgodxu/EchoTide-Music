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

// 留白：窄窗只留底部 8dp，宽窗四周 12dp
private val COMPACT_CONTENT_PADDING = PaddingValues(bottom = 8.dp)
private val EXPANDED_CONTENT_PADDING = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp)

// 组装器：Scaffold + 内容区，差异在留白
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
