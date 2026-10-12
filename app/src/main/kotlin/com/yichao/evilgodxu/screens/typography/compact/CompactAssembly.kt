package com.yichao.evilgodxu.screens.typography.compact

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.typography.component.TypographyGroups
import com.yichao.evilgodxu.screens.typography.TypographyUiState
import com.yichao.evilgodxu.ui.component.PageTopBar

// 窄屏组装器：常驻标题栏 + 满宽排版分组
@Composable
internal fun CompactAssembly(
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
