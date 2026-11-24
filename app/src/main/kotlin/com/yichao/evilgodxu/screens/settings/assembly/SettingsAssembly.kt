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
import com.yichao.evilgodxu.windowsize.WindowSizeClass
import com.yichao.evilgodxu.windowsize.contentMaxWidth

/**
 * 设置页组装器：标题栏与内容区共用一层 Scaffold，页内层级只换内容与标题。
 *
 * 层级切换与路由推入同一观感（同速整宽位移、300ms），故设置页只有一条路由；
 * 形态差异经 ContentPane 的行宽上限表达。
 *
 * @param onBack 根页返回，交给导航栈
 * @param onPageBack 页内层级返回，退回根页
 */
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
    // 层级内容离开组合后仍保留各自的滚动位置：从下方入口进层级再退回，根页不跳回顶部
    val levelStates = rememberSaveableStateHolder()
    Scaffold(
        modifier = modifier,
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
                    // 只有根页与页内层级之间会切换，故去向不是根页即为前进
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

                        // 排版与存储各自持有状态，层级内容自己接线，组装器只把它们放进内容区
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
