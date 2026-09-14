package com.yichao.evilgodxu

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.navigation.AppNavHost
import com.yichao.evilgodxu.theme.MyApplicationTheme
import com.yichao.evilgodxu.floatingwindow.LocalMusicPanelController
import com.yichao.evilgodxu.update.LocalUpdateViewModel
import com.yichao.evilgodxu.update.UpdateDialog
import com.yichao.evilgodxu.update.UpdateManager
import com.yichao.evilgodxu.update.UpdateViewModel

// 应用宿主内容：挂载主题、导航与全局副作用（更新检查、焦点清理、更新对话框），
// 由 Activity 作为入口调用，Activity 本身不持有界面内容
@Composable
fun AppContent() {
    val updateViewModel = LocalUpdateViewModel.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val musicPanelController = LocalMusicPanelController.current

    // 回前台时自动检查更新（每日仅检查一次）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                UpdateManager.shouldCheckUpdate(context.applicationContext)
            ) {
                updateViewModel.checkForUpdate()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 退后台时清除输入焦点，避免回前台时系统按残留焦点偶发自动弹出键盘
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

    // 更新对话框与手动检查反馈（全局弹出，覆盖所有页面）
    val updateInfo by updateViewModel.updateInfo.collectAsStateWithLifecycle()
    val showUpdateDialog by updateViewModel.showUpdateDialog.collectAsStateWithLifecycle()
    val downloadState by updateViewModel.downloadState.collectAsStateWithLifecycle()

    // 手动检查结果提示：一次性事件，自动检查静默；以文案为键重建收集，避免语言切换后仍弹旧语言文案
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
            AppNavHost(onExit = {
                // 暂停/未播放时的退出需彻底结束应用：仅 finish() 会因前台服务与悬浮窗
                // 残留导致进程未退出，此处经控制器移除悬浮窗、停止服务并终止进程
                musicPanelController?.exitApplication()
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
                        activity?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    updateViewModel.dismissUpdateDialog()
                },
                onDismiss = { updateViewModel.dismissUpdateDialog() }
            )
        }
    }
}