package com.yichao.evilgodxu.screens.settings.assembly

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.screens.settings.SettingsUiState
import com.yichao.evilgodxu.screens.settings.component.SettingsPane
import com.yichao.evilgodxu.ui.component.ContentPane
import com.yichao.evilgodxu.ui.component.PageTopBar
import com.yichao.evilgodxu.windowsize.WindowSizeClass
import com.yichao.evilgodxu.windowsize.contentMaxWidth

// 设置页组装器：骨架为 Scaffold + 单栏内容区，形态差异经 ContentPane 的行宽上限表达
@Composable
internal fun SettingsAssembly(
    windowSizeClass: WindowSizeClass,
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onThemeSelected: (ThemeMode) -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
    onThemeClick: (Offset) -> Unit,
    onMiniPlayerEnabledChange: (Boolean) -> Unit,
    onWordByWordRenderingChange: (Boolean) -> Unit,
    onSwipeToChangeTrackChange: (Boolean) -> Unit,
    onBackgroundFlowChange: (Boolean) -> Unit,
    onUsbDirectOutputChange: (Boolean) -> Unit,
    onUsbExclusiveOutputChange: (Boolean) -> Unit,
    onVersionClick: () -> Unit,
    onOpenTypography: () -> Unit,
    onProxySourceImport: (String) -> Unit,
    onProxySourceToggle: (String, Boolean) -> Unit,
    onProxySourceRemove: (String) -> Unit,
    onProxyImportMessageDismiss: () -> Unit,
    onOpenCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            PageTopBar(title = stringResource(R.string.settings_title), onBack = onBack)
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        ContentPane(
            maxContentWidth = windowSizeClass.contentMaxWidth,
            modifier = Modifier.fillMaxSize(),
        ) {
            SettingsPane(
                uiState = uiState,
                innerPadding = innerPadding,
                onThemeSelected = onThemeSelected,
                onLanguageSelected = onLanguageSelected,
                onThemeClick = onThemeClick,
                onMiniPlayerEnabledChange = onMiniPlayerEnabledChange,
                onWordByWordRenderingChange = onWordByWordRenderingChange,
                onSwipeToChangeTrackChange = onSwipeToChangeTrackChange,
                onBackgroundFlowChange = onBackgroundFlowChange,
                onUsbDirectOutputChange = onUsbDirectOutputChange,
                onUsbExclusiveOutputChange = onUsbExclusiveOutputChange,
                onVersionClick = onVersionClick,
                onOpenTypography = onOpenTypography,
                onProxySourceImport = onProxySourceImport,
                onProxySourceToggle = onProxySourceToggle,
                onProxySourceRemove = onProxySourceRemove,
                onProxyImportMessageDismiss = onProxyImportMessageDismiss,
                onOpenCache = onOpenCache,
            )
        }
    }
}
