package com.yichao.evilgodxu.screens.metadata.component

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
import com.yichao.evilgodxu.permission.allFilesAccessIntent
import com.yichao.evilgodxu.permission.hasAllFilesAccess
import com.yichao.evilgodxu.permission.hasImageAccess
import com.yichao.evilgodxu.permission.mediaImagePermission

/**
 * 元数据页的权限前置：打开页面即按「全部文件 → 图片」串行申请。
 *
 * 本页要做的每一件事都撞在这两项上——改动写回音频文件要全部文件，从最近图片里挑封面要图片访问。
 * 等用户改到一半才发现写不进去，比进来就问一次更糟，故把它们提前到页面打开时。
 *
 * 全部文件从本页用 ActivityResult 打开系统授权页：与原任务同一栈，用户返回即回到本页，
 * 不靠后台把应用拉起（与设置页的悬浮窗授权同一条路）。每次进入最多自动跳一次，用户拒绝后
 * 不再反复把他带走。图片一项等全部文件就绪后再问，两者串行不叠加弹窗。
 */
@Composable
internal fun MetadataPermissionRequest() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var allFilesReady by remember { mutableStateOf(hasAllFilesAccess()) }
    var allFilesPrompted by rememberSaveable { mutableStateOf(false) }
    var imagePrompted by rememberSaveable { mutableStateOf(false) }

    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { allFilesReady = hasAllFilesAccess() }
    val imagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 回到前台时复核：ActivityResult 覆盖了正常返回，这里兜住「系统没回调但权限已变」的情形，
    // 复核结果同时把后一项接上
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesReady = hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(allFilesReady) {
        if (!allFilesReady) {
            if (!allFilesPrompted) {
                allFilesPrompted = true
                runCatching { allFilesLauncher.launch(allFilesAccessIntent(context)) }
            }
            return@LaunchedEffect
        }
        if (!imagePrompted && !hasImageAccess(context)) {
            imagePrompted = true
            imagePermissionLauncher.launch(mediaImagePermission())
        }
    }
}
