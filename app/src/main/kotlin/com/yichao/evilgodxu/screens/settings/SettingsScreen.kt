package com.yichao.evilgodxu.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yichao.evilgodxu.LocalApplication
import com.yichao.evilgodxu.LocalLocalizationManager
import com.yichao.evilgodxu.LocalSettingsRepository
import com.yichao.evilgodxu.screens.settings.compact.CompactAssembly
import com.yichao.evilgodxu.screens.settings.expanded.ExpandedAssembly
import com.yichao.evilgodxu.theme.LocalThemeTransitionController
import com.yichao.evilgodxu.theme.StatusBarStyleEffect
import com.yichao.evilgodxu.windowsize.rememberExpandedForm
import com.yichao.evilgodxu.update.LocalUpdateViewModel

// 页面入口：形态分发 + 跨形态副作用，不承载布局
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTypography: () -> Unit = {},
    onOpenCache: () -> Unit = {},
) {
    val application = LocalApplication.current
    val settingsRepository = LocalSettingsRepository.current
    val localizationManager = LocalLocalizationManager.current
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    application = application,
                    settingsRepository = settingsRepository,
                    localizationManager = localizationManager,
                )
            }
        },
    )
    val updateViewModel = LocalUpdateViewModel.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val onThemeClick: (Offset) -> Unit = LocalThemeTransitionController.current::revealAt
    val context = LocalContext.current

    // 用 ActivityResultLauncher 打开悬浮窗系统设置页：结果由 ActivityResultRegistry 托管，
    // 配置变更或进程被回收后重建 Activity 时仍会重新投递，保证授权状态能可靠对账
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onOverlaySettingsReturned()
    }

    // 需要悬浮窗授权时跳转系统设置（仅由迷你播放器开关触发）
    LaunchedEffect(uiState.overlayPermissionPending) {
        if (uiState.overlayPermissionPending) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            runCatching { overlayPermissionLauncher.launch(intent) }
                .onFailure { viewModel.onOverlaySettingsReturned() }
        }
    }

    // 状态栏图标跟随主题：浅色主题深色图标，深色主题白色图标
    StatusBarStyleEffect()

    // 形态分派：旋转状态与窗口宽度尺寸类共同决定显示内容
    if (rememberExpandedForm()) {
        ExpandedAssembly(
            uiState = uiState,
            onBack = onBack,
            onThemeSelected = viewModel::setThemeMode,
            onLanguageSelected = viewModel::setLanguage,
            onThemeClick = onThemeClick,
            onMiniPlayerEnabledChange = viewModel::setMiniPlayerEnabled,
            onWordByWordRenderingChange = viewModel::setWordByWordRendering,
            onSwipeToChangeTrackChange = viewModel::setSwipeToChangeTrack,
            onVersionClick = { updateViewModel.checkForUpdate(force = true) },
            onOpenTypography = onOpenTypography,
            onProxySourceImport = viewModel::importProxySource,
            onProxySourceToggle = viewModel::setProxySourceEnabled,
            onProxySourceRemove = viewModel::removeProxySource,
            onProxyImportMessageDismiss = viewModel::clearProxyImportMessage,
            onOpenCache = onOpenCache,
            modifier = modifier,
        )
    } else {
        CompactAssembly(
            uiState = uiState,
            onBack = onBack,
            onThemeSelected = viewModel::setThemeMode,
            onLanguageSelected = viewModel::setLanguage,
            onThemeClick = onThemeClick,
            onMiniPlayerEnabledChange = viewModel::setMiniPlayerEnabled,
            onWordByWordRenderingChange = viewModel::setWordByWordRendering,
            onSwipeToChangeTrackChange = viewModel::setSwipeToChangeTrack,
            onVersionClick = { updateViewModel.checkForUpdate(force = true) },
            onOpenTypography = onOpenTypography,
            onProxySourceImport = viewModel::importProxySource,
            onProxySourceToggle = viewModel::setProxySourceEnabled,
            onProxySourceRemove = viewModel::removeProxySource,
            onProxyImportMessageDismiss = viewModel::clearProxyImportMessage,
            onOpenCache = onOpenCache,
            modifier = modifier,
        )
    }
}
