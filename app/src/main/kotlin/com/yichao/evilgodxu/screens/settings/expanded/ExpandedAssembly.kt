package com.yichao.evilgodxu.screens.settings.expanded

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.SettingsPane
import com.yichao.evilgodxu.screens.settings.SettingsUiState
import com.yichao.evilgodxu.ui.component.PageTopBar

// 宽屏下设置内容的可读宽度上限，避免超宽窗口把设置项拉伸过长
private val SETTINGS_CONTENT_MAX_WIDTH = 720.dp

// 宽屏组装器：限宽居中的设置内容
@Composable
internal fun ExpandedAssembly(
    uiState: SettingsUiState,
    blockedCount: Int,
    onBack: () -> Unit,
    onThemeSelected: (ThemeMode) -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
    onThemeClick: (Offset) -> Unit,
    onMiniPlayerEnabledChange: (Boolean) -> Unit,
    onWordByWordRenderingChange: (Boolean) -> Unit,
    onSwipeToChangeTrackChange: (Boolean) -> Unit,
    onBackgroundFlowChange: (Boolean) -> Unit,
    onVersionClick: () -> Unit,
    onOpenTypography: () -> Unit,
    onProxySourceImport: (String) -> Unit,
    onProxySourceToggle: (String, Boolean) -> Unit,
    onProxySourceRemove: (String) -> Unit,
    onProxyImportMessageDismiss: () -> Unit,
    onOpenCache: () -> Unit,
    onResetBlacklist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            PageTopBar(title = stringResource(R.string.settings_title), onBack = onBack)
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            SettingsPane(
                uiState = uiState,
                blockedCount = blockedCount,
                innerPadding = innerPadding,
                onThemeSelected = onThemeSelected,
                onLanguageSelected = onLanguageSelected,
                onThemeClick = onThemeClick,
                onMiniPlayerEnabledChange = onMiniPlayerEnabledChange,
                onWordByWordRenderingChange = onWordByWordRenderingChange,
                onSwipeToChangeTrackChange = onSwipeToChangeTrackChange,
                onBackgroundFlowChange = onBackgroundFlowChange,
                onVersionClick = onVersionClick,
                onOpenTypography = onOpenTypography,
                onProxySourceImport = onProxySourceImport,
                onProxySourceToggle = onProxySourceToggle,
                onProxySourceRemove = onProxySourceRemove,
                onProxyImportMessageDismiss = onProxyImportMessageDismiss,
                onOpenCache = onOpenCache,
                onResetBlacklist = onResetBlacklist,
                modifier = Modifier.widthIn(max = SETTINGS_CONTENT_MAX_WIDTH),
            )
        }
    }
}
