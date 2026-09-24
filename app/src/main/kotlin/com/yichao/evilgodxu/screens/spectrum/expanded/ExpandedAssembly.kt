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

// 宽屏下内容左右与底部留白：可视区充裕，让时频图与屏幕边缘分离
private val EXPANDED_CONTENT_PADDING = 12.dp

// 宽屏组装器：常驻标题栏 + 留白内的频谱内容。
// 顶部不留白：图与标题区的间距由内容区自身固定为 4dp
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(
                    start = EXPANDED_CONTENT_PADDING,
                    end = EXPANDED_CONTENT_PADDING,
                    bottom = EXPANDED_CONTENT_PADDING,
                ),
        )
    }
}
