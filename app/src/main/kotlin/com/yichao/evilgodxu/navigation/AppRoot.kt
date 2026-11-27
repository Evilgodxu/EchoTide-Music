package com.yichao.evilgodxu.navigation

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.yichao.evilgodxu.LocalPlaybackState
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.home.HomeScreen
import com.yichao.evilgodxu.screens.metadata.MetadataScreen
import com.yichao.evilgodxu.screens.settings.SettingsScreen
import com.yichao.evilgodxu.screens.spectrum.SpectrumScreen
import com.yichao.evilgodxu.theme.AppMotion
import com.yichao.evilgodxu.theme.SystemBarAppearance
import com.yichao.evilgodxu.windowsize.rememberWindowLandscape
import kotlinx.coroutines.delay

/** 应用根容器：播放页常驻底层，覆盖层自右滑入滑出 */
@Composable
fun AppRoot(
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
) {
    // 同一时刻只有一个覆盖层
    var overlay by rememberSaveable { mutableStateOf<AppDestination?>(null) }
    val context = LocalContext.current
    val playbackState = LocalPlaybackState.current
    // 关闭即回顶层；沉浸须即时撤销，否则标题栏先嵌入再下移
    val isHomeOnTop = overlay == null
    val isPortrait = !rememberWindowLandscape()
    // 首帧直接落位，不延后
    var systemBarsSettled by remember { mutableStateOf(false) }
    LaunchedEffect(isHomeOnTop, isPortrait) {
        val immersive = isHomeOnTop && isPortrait
        if (!systemBarsSettled) {
            SystemBarAppearance.isHomePortraitImmersive = immersive
            systemBarsSettled = true
            return@LaunchedEffect
        }
        // 回播放页的沉浸切换延后到转场结束，避免内边距突变撕裂
        if (immersive) delay(AppMotion.Standard.toLong())
        SystemBarAppearance.isHomePortraitImmersive = immersive
    }
    // Context 已本地化，Activity 从注册表所有者取
    val activity = LocalActivityResultRegistryOwner.current as? Activity
    // 预取文案，避免回调读过期资源
    val goHomeHint = stringResource(R.string.back_again_go_home)
    val exitHint = stringResource(R.string.back_again_exit)

    // 本回调最早注册故优先级最低；页面未处理才轮到这里
    var lastHomeBackTime by remember { mutableLongStateOf(0L) }
    BackHandler {
        if (overlay != null) {
            overlay = null
            return@BackHandler
        }
        val now = System.currentTimeMillis()
        if (now - lastHomeBackTime < DOUBLE_BACK_EXIT_MS) {
            if (playbackState.isPlayerActive) {
                // 播放中则退到桌面，保留后台播放
                activity?.moveTaskToBack(true)
            } else {
                playbackState.releaseIfIdle()
                onExit()
            }
        } else {
            lastHomeBackTime = now
            val hint =
                if (playbackState.isPlayerActive) goHomeHint else exitHint
            Toast.makeText(context, hint, Toast.LENGTH_SHORT).show()
        }
    }

    // 与页内层级同速整宽位移
    val pageTransitionSpec = tween<IntOffset>(AppMotion.Standard)
    Box(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalIsTopLayer provides (overlay == null)) {
            HomeScreen(
                onOpenSettings = { overlay = Settings },
                onOpenSpectrum = { trackId -> overlay = Spectrum(trackId) },
                onOpenMetadata = { trackId -> overlay = Metadata(trackId) },
            )
        }
        AnimatedContent(
            targetState = overlay,
            modifier = Modifier.fillMaxSize(),
            // 进入自右滑入；退场只滑走覆盖层
            transitionSpec = {
                if (targetState != null) {
                    slideInHorizontally(animationSpec = pageTransitionSpec) { it } togetherWith
                        ExitTransition.None
                } else {
                    EnterTransition.None togetherWith
                        slideOutHorizontally(animationSpec = pageTransitionSpec) { it }
                }
            },
            label = "overlay_page",
        ) { destination ->
            if (destination != null) {
                OverlayPage(destination = destination, onBack = { overlay = null })
            } else {
                // 留空，底层播放页自显
                Box(Modifier.fillMaxSize())
            }
        }
    }
}

// 每次打开一份独立 ViewModelStore，关闭即清空
@Composable
private fun OverlayPage(
    destination: AppDestination,
    onBack: () -> Unit,
) {
    val owner = remember(destination) { OverlayViewModelStoreOwner() }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        when (destination) {
            is Home -> Unit
            is Settings -> SettingsScreen(onBack = onBack)
            is Spectrum -> SpectrumScreen(trackId = destination.trackId, onBack = onBack)
            is Metadata -> MetadataScreen(trackId = destination.trackId, onBack = onBack)
        }
    }
}

private class OverlayViewModelStoreOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

// 播放页双击返回间隔
private const val DOUBLE_BACK_EXIT_MS = 2000L
