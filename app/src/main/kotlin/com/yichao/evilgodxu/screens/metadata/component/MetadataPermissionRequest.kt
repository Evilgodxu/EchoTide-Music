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
import com.yichao.evilgodxu.permission.hasAllFilesAccess
import com.yichao.evilgodxu.permission.hasImageAccess
import com.yichao.evilgodxu.permission.mediaImagePermission
import com.yichao.evilgodxu.permission.rememberAllFilesAccessRequest

/** 进入即串行申请全部文件与图片权限，各最多一次 */
@Composable
internal fun MetadataPermissionRequest() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var allFilesReady by remember { mutableStateOf(hasAllFilesAccess()) }
    var allFilesPrompted by rememberSaveable { mutableStateOf(false) }
    var imagePrompted by rememberSaveable { mutableStateOf(false) }

    val requestAllFilesAccess = rememberAllFilesAccessRequest {
        allFilesReady = hasAllFilesAccess()
    }
    val imagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 回前台复核权限，兜住无回调的情况
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
                requestAllFilesAccess()
            }
            return@LaunchedEffect
        }
        if (!imagePrompted && !hasImageAccess(context)) {
            imagePrompted = true
            imagePermissionLauncher.launch(mediaImagePermission())
        }
    }
}
