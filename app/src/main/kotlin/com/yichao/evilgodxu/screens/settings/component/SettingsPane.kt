package com.yichao.evilgodxu.screens.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.screens.settings.component.appearance.Appearance
import com.yichao.evilgodxu.screens.settings.component.blacklist.Blacklist
import com.yichao.evilgodxu.screens.settings.component.cache.Cache
import com.yichao.evilgodxu.screens.settings.component.dialog.LanguageSelectionDialog
import com.yichao.evilgodxu.screens.settings.component.dialog.ThemeSelectionDialog
import com.yichao.evilgodxu.screens.settings.component.info.AppInfo
import com.yichao.evilgodxu.screens.settings.component.language.Language
import com.yichao.evilgodxu.screens.settings.component.playback.Playback
import com.yichao.evilgodxu.screens.settings.component.proxy.ProxySource
import com.yichao.evilgodxu.screens.settings.SettingsUiState

// 设置页内容：外观、语言、播放、代理音源、缓存、黑名单、关于七个分组，以及主题与语言选择对话框
@Composable
internal fun SettingsPane(
    uiState: SettingsUiState,
    // 已拉黑歌曲数：黑名单存于独立存储，不走 SettingsUiState，由页面直接传入
    blockedCount: Int,
    // Scaffold 内边距：由形态组装器透传，内容避让标题栏
    innerPadding: PaddingValues,
    onThemeSelected: (ThemeMode) -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
    onThemeClick: (Offset) -> Unit,
    onMiniPlayerEnabledChange: (Boolean) -> Unit,
    onWordByWordRenderingChange: (Boolean) -> Unit,
    onSwipeToChangeTrackChange: (Boolean) -> Unit,
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
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var pendingLanguage by remember { mutableStateOf<AppLanguage?>(null) }
    var pendingThemeClickPosition by remember { mutableStateOf(Offset.Zero) }

    // 先关闭对话框，下一帧再切语言，避免切换瞬间闪现旧语言
    LaunchedEffect(pendingLanguage) {
        val language = pendingLanguage ?: return@LaunchedEffect
        pendingLanguage = null
        onLanguageSelected(language)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Appearance(
            themeMode = uiState.themeMode,
            onThemeClick = { position ->
                pendingThemeClickPosition = position
                showThemeDialog = true
            },
        )
        Language(uiState.language, onLanguageSelected, onShowDialog = { showLanguageDialog = true })
        Playback(
            miniPlayerEnabled = uiState.miniPlayerEnabled,
            onMiniPlayerEnabledChange = onMiniPlayerEnabledChange,
            wordByWordRendering = uiState.wordByWordRendering,
            onWordByWordRenderingChange = onWordByWordRenderingChange,
            swipeToChangeTrack = uiState.swipeToChangeTrack,
            onSwipeToChangeTrackChange = onSwipeToChangeTrackChange,
            onTypographyClick = onOpenTypography,
        )
        ProxySource(
            sources = uiState.proxySources,
            importMessage = uiState.proxyImportMessage,
            importFailed = uiState.proxyImportFailed,
            onImport = onProxySourceImport,
            onToggle = onProxySourceToggle,
            onRemove = onProxySourceRemove,
            onMessageDismiss = onProxyImportMessageDismiss,
        )
        Cache(
            totalBytes = uiState.cacheTotalBytes,
            onClick = onOpenCache,
        )
        Blacklist(
            blockedCount = blockedCount,
            onReset = onResetBlacklist,
        )
        AppInfo(uiState.version, onVersionClick)
    }

    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentTheme = uiState.themeMode,
            onDismiss = { showThemeDialog = false },
            onThemeSelected = { mode ->
                onThemeClick(pendingThemeClickPosition)
                onThemeSelected(mode)
                showThemeDialog = false
            },
        )
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = uiState.language,
            onDismiss = { showLanguageDialog = false },
            onLanguageSelected = { language ->
                showLanguageDialog = false
                pendingLanguage = language
            },
        )
    }
}
