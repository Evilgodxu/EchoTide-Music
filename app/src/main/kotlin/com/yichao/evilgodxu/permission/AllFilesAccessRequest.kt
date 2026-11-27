package com.yichao.evilgodxu.permission

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** 全部文件访问申请：打开授权页，同栈往返；授权后不自关 */
@Composable
fun rememberAllFilesAccessRequest(onReturn: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onReturn() }
    return remember(context, launcher) {
        {
            PermissionReturnMonitor.watch(context, PermissionType.MANAGE_EXTERNAL_STORAGE)
            runCatching { launcher.launch(allFilesAccessIntent(context)) }
        }
    }
}
