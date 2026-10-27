package com.yichao.evilgodxu.screens.metadata.component

import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yichao.evilgodxu.permission.ensureAllFilesAccess
import com.yichao.evilgodxu.permission.hasImageAccess
import com.yichao.evilgodxu.permission.mediaImagePermission

/**
 * 元数据页的权限前置：打开页面即按「全部文件 → 图片」串行申请。
 *
 * 本页要做的每一件事都撞在这两项上——改动写回音频文件要全部文件，从最近图片里挑封面要图片访问。
 * 等用户改到一半才发现写不进去，比进来就问一次更糟，故把它们提前到页面打开时。
 *
 * 全部文件走系统设置页，跳转会离开本页，因此每次进入最多自动跳一次：用户明确拒绝后不再反复
 * 把他带走，保存失败的提示会再次点明原因。图片一项等全部文件就绪后再问，两者串行不叠加弹窗。
 */
@Composable
internal fun MetadataPermissionRequest() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var allFilesReady by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    // 每次进入本页最多自动跳一次系统授权页
    var allFilesPrompted by rememberSaveable { mutableStateOf(false) }
    var imagePrompted by rememberSaveable { mutableStateOf(false) }

    val imagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 从系统授权页返回时复核：前一项就绪后把后一项接上
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesReady = Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(allFilesReady) {
        if (!allFilesReady) {
            if (!allFilesPrompted) {
                allFilesPrompted = true
                ensureAllFilesAccess(context)
            }
            return@LaunchedEffect
        }
        if (!imagePrompted && !hasImageAccess(context)) {
            imagePrompted = true
            imagePermissionLauncher.launch(mediaImagePermission())
        }
    }
}
