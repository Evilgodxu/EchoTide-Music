package com.yichao.evilgodxu.screens.settings.assembly

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.SettingsPage
import com.yichao.evilgodxu.screens.settings.SettingsUiState
import com.yichao.evilgodxu.screens.settings.component.SettingsPane
import com.yichao.evilgodxu.screens.settings.component.appearance.AppearanceLanguagePane
import com.yichao.evilgodxu.screens.settings.component.playback.PlaybackModePane
import com.yichao.evilgodxu.screens.settings.component.proxy.ProxySourcePane
import com.yichao.evilgodxu.screens.settings.level.storage.CachePane
import com.yichao.evilgodxu.screens.settings.level.typography.TypographyPane
import com.yichao.evilgodxu.theme.AppMotion
import com.yichao.evilgodxu.ui.component.ContentPane
import com.yichao.evilgodxu.ui.component.PageTopBar
import com.yichao.evilgodxu.ui.gesture.swipeBack
import com.yichao.evilgodxu.windowsize.WindowSizeClass
import com.yichao.evilgodxu.windowsize.contentMaxWidth

/** 组装器：共用 Scaffold，层级只换内容 */
@Composable
internal fun SettingsAssembly(
    windowSizeClass: WindowSizeClass,
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onPageBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    onThemeClick: (Offset) -> Unit,
    onShowLanguageDialog: () -> Unit,
    onMiniPlayerEnabledChange: (Boolean) -> Unit,
    onWordByWordRenderingChange: (Boolean) -> Unit,
    onSwipeToChangeTrackChange: (Boolean) -> Unit,
    onBackgroundFlowChange: (Boolean) -> Unit,
    onUsbDirectOutputChange: (Boolean) -> Unit,
    onUsbExclusiveOutputChange: (Boolean) -> Unit,
    onVolumeGainChange: (Boolean) -> Unit,
    onVersionClick: () -> Unit,
    onProxySourceImport: (String) -> Unit,
    onProxySourceToggle: (String, Boolean) -> Unit,
    onProxySourceRemove: (String) -> Unit,
    onProxyImportMessageDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val page = uiState.page
    // 保留各层级滚动位置
    val levelStates = rememberSaveableStateHolder()
    Scaffold(
        // 右滑返回：层级退根页，根页退上一页
        modifier = modifier.swipeBack(
            onBack = { if (page == SettingsPage.ROOT) onBack() else onPageBack() },
        ),
        topBar = {
            PageTopBar(
                title = when (page) {
                    SettingsPage.ROOT -> stringResource(R.string.settings_title)
                    SettingsPage.APPEARANCE_LANGUAGE ->
                        stringResource(R.string.settings_entry_appearance_language)
                    SettingsPage.PLAYBACK_MODE -> stringResource(R.string.settings_entry_playback_mode)
                    SettingsPage.TYPOGRAPHY -> stringResource(R.string.settings_typography_title)
                    SettingsPage.PROXY_SOURCE -> stringResource(R.string.settings_entry_proxy_source)
                    SettingsPage.STORAGE -> stringResource(R.string.settings_cache_entry_title)
                },
                onBack = if (page == SettingsPage.ROOT) onBack else onPageBack,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        ContentPane(
            maxContentWidth = windowSizeClass.contentMaxWidth,
            modifier = Modifier.fillMaxSize(),
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    // 去向非根页即为前进
                    val spec = tween<IntOffset>(AppMotion.Standard)
                    if (targetState != SettingsPage.ROOT) {
                        slideInHorizontally(spec) { it } togetherWith
                            slideOutHorizontally(spec) { -it }
                    } else {
                        slideInHorizontally(spec) { -it } togetherWith
                            slideOutHorizontally(spec) { it }
                    }
                },
                label = "settings_page",
            ) { current ->
                levelStates.SaveableStateProvider(current) {
                    when (current) {
                        SettingsPage.ROOT -> SettingsPane(
                            uiState = uiState,
                            innerPadding = innerPadding,
                            onOpenPage = onOpenPage,
                            onVersionClick = onVersionClick,
                        )

                        SettingsPage.APPEARANCE_LANGUAGE -> AppearanceLanguagePane(
                            themeMode = uiState.themeMode,
                            language = uiState.language,
                            wordByWordRendering = uiState.playback.wordByWordRendering,
                            backgroundFlow = uiState.playback.backgroundFlow,
                            innerPadding = innerPadding,
                            onThemeClick = onThemeClick,
                            onShowLanguageDialog = onShowLanguageDialog,
                            onWordByWordRenderingChange = onWordByWordRenderingChange,
                            onBackgroundFlowChange = onBackgroundFlowChange,
                        )

                        SettingsPage.PLAYBACK_MODE -> PlaybackModePane(
                            miniPlayerEnabled = uiState.playback.miniPlayerEnabled,
                            swipeToChangeTrack = uiState.playback.swipeToChangeTrack,
                            usbDirectOutput = uiState.playback.usbDirectOutput,
                            usbExclusiveOutput = uiState.playback.usbExclusiveOutput,
                            volumeGain = uiState.playback.volumeGain,
                            innerPadding = innerPadding,
                            onMiniPlayerEnabledChange = onMiniPlayerEnabledChange,
                            onSwipeToChangeTrackChange = onSwipeToChangeTrackChange,
                            onUsbDirectOutputChange = onUsbDirectOutputChange,
                            onUsbExclusiveOutputChange = onUsbExclusiveOutputChange,
                            onVolumeGainChange = onVolumeGainChange,
                        )

                        // 排版与存储自带状态，只放进内容区
                        SettingsPage.TYPOGRAPHY -> TypographyPane(innerPadding = innerPadding)

                        SettingsPage.PROXY_SOURCE -> ProxySourcePane(
                            sources = uiState.proxySources,
                            importMessage = uiState.proxyImportMessage,
                            importFailed = uiState.proxyImportFailed,
                            innerPadding = innerPadding,
                            onImport = onProxySourceImport,
                            onToggle = onProxySourceToggle,
                            onRemove = onProxySourceRemove,
                            onMessageDismiss = onProxyImportMessageDismiss,
                        )

                        SettingsPage.STORAGE -> CachePane(innerPadding = innerPadding)
                    }
                }
            }
        }
    }
}
