package com.yichao.evilgodxu.screens.settings.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yichao.evilgodxu.screens.settings.SettingsPage
import com.yichao.evilgodxu.screens.settings.SettingsUiState
import com.yichao.evilgodxu.screens.settings.component.appearance.AppearanceEntry
import com.yichao.evilgodxu.screens.settings.component.cache.CacheEntry
import com.yichao.evilgodxu.screens.settings.component.info.AppInfo
import com.yichao.evilgodxu.screens.settings.component.playback.PlaybackModeEntry
import com.yichao.evilgodxu.screens.settings.component.proxy.ProxySourceEntry
import com.yichao.evilgodxu.screens.settings.component.typography.TypographyEntry

/** 设置根页：五个入口 + 品牌与版本 */
@Composable
internal fun SettingsPane(
    uiState: SettingsUiState,
    innerPadding: PaddingValues,
    onOpenPage: (SettingsPage) -> Unit,
    onVersionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScrollColumn(innerPadding = innerPadding, modifier = modifier) {
        AppearanceEntry(onClick = { onOpenPage(SettingsPage.APPEARANCE_LANGUAGE) })
        PlaybackModeEntry(onClick = { onOpenPage(SettingsPage.PLAYBACK_MODE) })
        TypographyEntry(onClick = { onOpenPage(SettingsPage.TYPOGRAPHY) })
        ProxySourceEntry(
            sourceCount = uiState.proxySources.size,
            onClick = { onOpenPage(SettingsPage.PROXY_SOURCE) },
        )
        CacheEntry(
            totalBytes = uiState.cacheTotalBytes,
            onClick = { onOpenPage(SettingsPage.STORAGE) },
        )
        AppInfo(uiState.version, onVersionClick)
    }
}
