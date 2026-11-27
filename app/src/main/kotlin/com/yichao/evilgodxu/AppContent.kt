package com.yichao.evilgodxu

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.navigation.AppRoot
import com.yichao.evilgodxu.theme.MyApplicationTheme
import com.yichao.evilgodxu.floatingwindow.LocalMiniPlayerController
import com.yichao.evilgodxu.update.LocalUpdateViewModel
import com.yichao.evilgodxu.update.UpdateDialog
import com.yichao.evilgodxu.update.UpdateManager
import com.yichao.evilgodxu.update.UpdateViewModel

// 应用宿主：挂主题、导航与全局副作用
@Composable
fun AppContent() {
    val updateViewModel = LocalUpdateViewModel.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val miniPlayerController = LocalMiniPlayerController.current

    // 回前台自动检查更新，每日一次由仓库判定
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                updateViewModel.checkForUpdate()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 退后台清除焦点，避免回前台误弹键盘
    val focusManager = LocalFocusManager.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                focusManager.clearFocus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 更新对话框，全局覆盖
    val updateInfo by updateViewModel.updateInfo.collectAsStateWithLifecycle()
    val showUpdateDialog by updateViewModel.showUpdateDialog.collectAsStateWithLifecycle()
    val downloadState by updateViewModel.downloadState.collectAsStateWithLifecycle()

    // 手动检查提示；以文案为键重建收集以跟随语言
    val upToDateText = stringResource(R.string.update_toast_up_to_date)
    val checkErrorText = stringResource(R.string.update_toast_error)
    LaunchedEffect(upToDateText, checkErrorText) {
        updateViewModel.messages.collect { message ->
            val text = when (message) {
                UpdateViewModel.CheckFeedback.UP_TO_DATE -> upToDateText
                UpdateViewModel.CheckFeedback.ERROR -> checkErrorText
            }
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    MyApplicationTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            AppRoot(onExit = {
                // 先退到后台：界面不留在前台等清理走完
                activity?.moveTaskToBack(true)
                // 仅 finish 会因服务与悬浮窗残留，交由控制器退出
                miniPlayerController?.exitApplication()
            })
        }
    }

    if (showUpdateDialog && updateInfo != null) {
        val info = updateInfo
        if (info != null) {
            UpdateDialog(
                updateInfo = info,
                downloadState = downloadState,
                onDownload = { updateViewModel.downloadAndInstall() },
                onOpenBrowser = {
                    val url = UpdateManager.GITHUB_REPOSITORY_URL
                    if (url.startsWith("http")) {
                        activity?.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    }
                    updateViewModel.dismissUpdateDialog()
                },
                onDismiss = { updateViewModel.dismissUpdateDialog() }
            )
        }
    }
}