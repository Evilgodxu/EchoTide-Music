package com.yichao.evilgodxu.screens.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.screens.settings.SettingsUiState
import com.yichao.evilgodxu.screens.settings.component.appearance.Appearance
import com.yichao.evilgodxu.screens.settings.component.cache.Cache
import com.yichao.evilgodxu.screens.settings.component.info.AppInfo
import com.yichao.evilgodxu.screens.settings.component.language.Language
import com.yichao.evilgodxu.screens.settings.component.playback.Playback
import com.yichao.evilgodxu.screens.settings.component.proxy.ProxySource

// 设置页内容：外观、语言、播放、代理音源、缓存、关于六个分组。
// 主题与语言弹窗挂在页面骨架，这里只上抛"请求打开"，不持有弹窗状态
@Composable
internal fun SettingsPane(
    uiState: SettingsUiState,
    // Scaffold 内边距：由组装器透传，内容避让标题栏
    innerPadding: PaddingValues,
    onThemeClick: (Offset) -> Unit,
    onShowLanguageDialog: () -> Unit,
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
            // 宿主为 edge-to-edge 且页面无底部栏代管：末项（关于分组）须自行避让系统导航栏，
            // 否则滚到底部时被三键导航的虚拟按键压住
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Appearance(
            themeMode = uiState.themeMode,
            onThemeClick = onThemeClick,
        )
        Language(uiState.language, onShowDialog = onShowLanguageDialog)
        Playback(
            miniPlayerEnabled = uiState.playback.miniPlayerEnabled,
            onMiniPlayerEnabledChange = onMiniPlayerEnabledChange,
            wordByWordRendering = uiState.playback.wordByWordRendering,
            onWordByWordRenderingChange = onWordByWordRenderingChange,
            swipeToChangeTrack = uiState.playback.swipeToChangeTrack,
            onSwipeToChangeTrackChange = onSwipeToChangeTrackChange,
            backgroundFlow = uiState.playback.backgroundFlow,
            onBackgroundFlowChange = onBackgroundFlowChange,
            usbDirectOutput = uiState.playback.usbDirectOutput,
            onUsbDirectOutputChange = onUsbDirectOutputChange,
            usbExclusiveOutput = uiState.playback.usbExclusiveOutput,
            onUsbExclusiveOutputChange = onUsbExclusiveOutputChange,
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
        AppInfo(uiState.version, onVersionClick)
    }
}
