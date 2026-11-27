package com.yichao.evilgodxu.screens.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yichao.evilgodxu.LocalApplication
import com.yichao.evilgodxu.LocalProxySourceStore
import com.yichao.evilgodxu.permission.notificationPolicyAccessIntent
import com.yichao.evilgodxu.LocalLocalizationManager
import com.yichao.evilgodxu.LocalSettingsRepository
import com.yichao.evilgodxu.screens.settings.assembly.SettingsAssembly
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.screens.settings.component.dialog.LanguageSelectionDialog
import com.yichao.evilgodxu.screens.settings.component.dialog.ThemeSelectionDialog
import com.yichao.evilgodxu.theme.LocalThemeTransitionController
import com.yichao.evilgodxu.theme.StatusBarStyleEffect
import com.yichao.evilgodxu.windowsize.rememberWindowSizeClass
import com.yichao.evilgodxu.update.LocalUpdateViewModel

// 页面入口：分发与副作用，不承载布局
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalApplication.current
    val settingsRepository = LocalSettingsRepository.current
    val localizationManager = LocalLocalizationManager.current
    val proxySourceStore = LocalProxySourceStore.current
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = settingsRepository,
                    environment = AndroidSettingsEnvironment(application, localizationManager, proxySourceStore),
                )
            }
        },
    )
    val updateViewModel = LocalUpdateViewModel.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val revealThemeAt: (Offset) -> Unit = LocalThemeTransitionController.current::revealAt
    val context = LocalContext.current

    // 用 Launcher 打开悬浮窗设置，重建后仍能对账
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onOverlaySettingsReturned()
    }

    // 免打扰页同理：返回时对账
    val notificationPolicyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onNotificationPolicySettingsReturned()
    }
    LaunchedEffect(uiState.notificationPolicyPermissionPending) {
        if (uiState.notificationPolicyPermissionPending) {
            runCatching { notificationPolicyLauncher.launch(notificationPolicyAccessIntent()) }
                .onFailure { viewModel.onNotificationPolicySettingsReturned() }
        }
    }

    // 需授权时跳系统悬浮窗设置
    LaunchedEffect(uiState.overlayPermissionPending) {
        if (uiState.overlayPermissionPending) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri(),
            )
            runCatching { overlayPermissionLauncher.launch(intent) }
                .onFailure { viewModel.onOverlaySettingsReturned() }
        }
    }

    // 状态栏图标跟随主题
    StatusBarStyleEffect()

    // 层级先接管返回，根页才交给导航栈
    BackHandler(enabled = uiState.page != SettingsPage.ROOT) {
        viewModel.backToRoot()
    }

    // 弹层挂在骨架层，组装器只上抛请求
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var pendingLanguage by remember { mutableStateOf<AppLanguage?>(null) }
    var pendingThemeClickPosition by remember { mutableStateOf(Offset.Zero) }

    // 下一帧再切语言，避免闪现旧语言
    LaunchedEffect(pendingLanguage) {
        val language = pendingLanguage ?: return@LaunchedEffect
        pendingLanguage = null
        viewModel.setLanguage(language)
    }

    // 尺寸类在此计算后下传
    SettingsAssembly(
        windowSizeClass = rememberWindowSizeClass(),
        uiState = uiState,
        onBack = onBack,
        onPageBack = viewModel::backToRoot,
        onOpenPage = viewModel::openPage,
        onThemeClick = { position ->
            pendingThemeClickPosition = position
            showThemeDialog = true
        },
        onShowLanguageDialog = { showLanguageDialog = true },
        onMiniPlayerEnabledChange = viewModel::setMiniPlayerEnabled,
        onWordByWordRenderingChange = viewModel::setWordByWordRendering,
        onSwipeToChangeTrackChange = viewModel::setSwipeToChangeTrack,
        onBackgroundFlowChange = viewModel::setBackgroundFlow,
        onUsbDirectOutputChange = viewModel::setUsbDirectOutput,
        onUsbExclusiveOutputChange = viewModel::setUsbExclusiveOutput,
        onVolumeGainChange = viewModel::setVolumeGain,
        onVersionClick = { updateViewModel.checkForUpdate(force = true) },
        onProxySourceImport = viewModel::importProxySource,
        onProxySourceToggle = viewModel::setProxySourceEnabled,
        onProxySourceRemove = viewModel::removeProxySource,
        onProxyImportMessageDismiss = viewModel::clearProxyImportMessage,
        modifier = modifier,
    )

    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentTheme = uiState.themeMode,
            onDismiss = { showThemeDialog = false },
            onThemeSelected = { mode ->
                // 先播放揭示动画再落库主题
                revealThemeAt(pendingThemeClickPosition)
                viewModel.setThemeMode(mode)
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
