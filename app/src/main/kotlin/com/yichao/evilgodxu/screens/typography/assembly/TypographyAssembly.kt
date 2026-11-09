package com.yichao.evilgodxu.screens.typography.assembly

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.typography.TypographyUiState
import com.yichao.evilgodxu.screens.typography.component.TypographyGroups
import com.yichao.evilgodxu.ui.component.ContentPane
import com.yichao.evilgodxu.ui.component.PageTopBar
import com.yichao.evilgodxu.windowsize.WindowSizeClass
import com.yichao.evilgodxu.windowsize.contentMaxWidth

// 字体页组装器：骨架为 Scaffold + 单栏内容区，形态差异经 ContentPane 的行宽上限表达
@Composable
internal fun TypographyAssembly(
    windowSizeClass: WindowSizeClass,
    uiState: TypographyUiState,
    onBack: () -> Unit,
    onHomePortraitFontSizeChange: (Int) -> Unit,
    onHomePortraitLinesChange: (Int) -> Unit,
    onHomePortraitLineSpacingChange: (Int) -> Unit,
    onLandscapeFontSizeChange: (Int) -> Unit,
    onLandscapeLinesChange: (Int) -> Unit,
    onLandscapeLineSpacingChange: (Int) -> Unit,
    onLandscape3DChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            PageTopBar(title = stringResource(R.string.typography_screen_title), onBack = onBack)
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        ContentPane(
            maxContentWidth = windowSizeClass.contentMaxWidth,
            modifier = Modifier.fillMaxSize(),
        ) {
            TypographyGroups(
                uiState = uiState,
                innerPadding = innerPadding,
                onHomePortraitFontSizeChange = onHomePortraitFontSizeChange,
                onHomePortraitLinesChange = onHomePortraitLinesChange,
                onHomePortraitLineSpacingChange = onHomePortraitLineSpacingChange,
                onLandscapeFontSizeChange = onLandscapeFontSizeChange,
                onLandscapeLinesChange = onLandscapeLinesChange,
                onLandscapeLineSpacingChange = onLandscapeLineSpacingChange,
                onLandscape3DChange = onLandscape3DChange,
            )
        }
    }
}
