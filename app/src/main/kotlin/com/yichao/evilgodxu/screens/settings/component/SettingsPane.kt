package com.yichao.evilgodxu.screens.settings.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yichao.evilgodxu.screens.settings.SettingsPage
import com.yichao.evilgodxu.screens.settings.SettingsUiState
import com.yichao.evilgodxu.screens.settings.component.appearance.AppearanceEntry
import com.yichao.evilgodxu.screens.settings.component.cache.Cache
import com.yichao.evilgodxu.screens.settings.component.info.AppInfo
import com.yichao.evilgodxu.screens.settings.component.playback.PlaybackModeEntry
import com.yichao.evilgodxu.screens.settings.component.proxy.ProxySource
import com.yichao.evilgodxu.screens.settings.component.typography.TypographyEntry

/**
 * 设置根页：语言外观、播放模式、歌词排版三个入口，其后是代理音源、存储与关于。
 *
 * 前两项的读写全在本页状态里，因此只是页内层级；歌词排版与存储各自持有状态，走独立路由。
 * 主题与语言弹窗挂在页面骨架，这里只上抛「请求打开」，不持有弹窗状态。
 */
@Composable
internal fun SettingsPane(
    uiState: SettingsUiState,
    innerPadding: PaddingValues,
    onOpenPage: (SettingsPage) -> Unit,
    onOpenTypography: () -> Unit,
    onVersionClick: () -> Unit,
    onProxySourceImport: (String) -> Unit,
    onProxySourceToggle: (String, Boolean) -> Unit,
    onProxySourceRemove: (String) -> Unit,
    onProxyImportMessageDismiss: () -> Unit,
    onOpenCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScrollColumn(innerPadding = innerPadding, modifier = modifier) {
        AppearanceEntry(onClick = { onOpenPage(SettingsPage.APPEARANCE_LANGUAGE) })
        PlaybackModeEntry(onClick = { onOpenPage(SettingsPage.PLAYBACK_MODE) })
        TypographyEntry(onClick = onOpenTypography)
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
