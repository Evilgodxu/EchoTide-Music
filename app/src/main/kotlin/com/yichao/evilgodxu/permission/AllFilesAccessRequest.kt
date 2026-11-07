package com.yichao.evilgodxu.permission

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 「全部文件访问」的申请动作。
 *
 * 申请点有三处，各自只决定「什么时候问」——启动链末段、写操作被触发时、元数据页打开时；
 * 「怎么问」收敛在这里：ActivityResult 打开系统授权页，与本任务同栈往返，用户返回即回到原界面。
 * 授权页在用户拨开开关后不会自己关闭，那一半由 [PermissionReturnMonitor] 跟随授权把应用带回前台。
 *
 * [onReturn] 只承载调用方在用户返回后的后续动作（如推进下一段授权），不参与授权判定。
 */
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
