package com.yichao.evilgodxu.screens.typography.expanded

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.typography.component.TypographyGroups
import com.yichao.evilgodxu.screens.typography.TypographyUiState
import com.yichao.evilgodxu.ui.component.PageTopBar

// 宽屏下排版分组的可读宽度上限，避免超宽窗口把调节行拉伸过长
private val TYPOGRAPHY_CONTENT_MAX_WIDTH = 720.dp

// 宽屏组装器：限宽居中的排版分组
@Composable
internal fun ExpandedAssembly(
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
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
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
                modifier = Modifier.widthIn(max = TYPOGRAPHY_CONTENT_MAX_WIDTH),
            )
        }
    }
}
