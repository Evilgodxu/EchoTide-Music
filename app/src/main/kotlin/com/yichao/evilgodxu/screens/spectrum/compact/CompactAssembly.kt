package com.yichao.evilgodxu.screens.spectrum.compact

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

// 窄屏下内容底缘留白
private val COMPACT_BOTTOM_PADDING = 8.dp

// 窄屏组装器：常驻标题栏 + 内容区的频谱内容。
// 窄屏横向空间本就紧张，图不设左右边距，只在底部留出余量；
// 顶部不留白：图与标题区的间距由内容区自身固定为 4dp，两处叠加会拉大间距
@Composable
internal fun CompactAssembly(
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
                .padding(bottom = COMPACT_BOTTOM_PADDING),
        )
    }
}
