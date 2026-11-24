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

/**
 * 设置根页：五个入口各占一区，其后是品牌、版本与反馈入口。
 *
 * 五个入口都指向页内层级，根页只留入口与一行摘要；主题与语言弹窗挂在页面骨架，
 * 这里只上抛「请求打开」，不持有弹窗状态。
 */
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
