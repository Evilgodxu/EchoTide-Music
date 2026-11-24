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

/**
 * 应用根容器：播放页是常驻底层，设置/频谱/元数据作为覆盖层从右侧滑入、滑出。
 *
 * 不做「整页替换」是有意为之：替换式转场在退场时要把播放页重新组合出来，
 * 首帧开销会把退出动画整段吃掉，观感就是页面瞬间关闭；分层式里播放页始终是画好的，
 * 退场只是把上层滑走。
 */
@Composable
fun AppRoot(
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
) {
    // 覆盖层的入口都在播放页内，同一时刻只会有一个
    var overlay by rememberSaveable { mutableStateOf<AppDestination?>(null) }
    val context = LocalContext.current
    val playbackState = LocalPlaybackState.current
    // 覆盖层关闭即为播放页回到顶层。系统栏沉浸由它驱动：若等到播放页组合重建才撤销，
    // 沉浸状态会在切换动画期间残留，使目标页标题栏先按零内边距嵌入状态栏区域、再整体下移
    val isHomeOnTop = overlay == null
    val isPortrait = !rememberWindowLandscape()
    // 冷启动首帧直接落到位，不参与下面的延后逻辑
    var systemBarsSettled by remember { mutableStateOf(false) }
    LaunchedEffect(isHomeOnTop, isPortrait) {
        val immersive = isHomeOnTop && isPortrait
        if (!systemBarsSettled) {
            SystemBarAppearance.isHomePortraitImmersive = immersive
            systemBarsSettled = true
            return@LaunchedEffect
        }
        // 覆盖层退出期间若收系统栏，滑出的上层与底下的播放页会同时经历一次
        // 内边距突变（状态栏/导航栏高度瞬间归零），观感像画面撕裂；故把「回到播放页」
        // 的沉浸切换延后到转场结束。打开覆盖层仍立即显示系统栏，避免目标页标题栏
        // 先按零内边距布局再整体下移。
        if (immersive) delay(AppMotion.Standard.toLong())
        SystemBarAppearance.isHomePortraitImmersive = immersive
    }
    // LocalContext 已被本地化包装，宿主 Activity 需从注册表所有者获取
    val activity = LocalActivityResultRegistryOwner.current as? Activity
    // 预取提示文案，配置变化时由 Compose 自动更新，避免在回调中读取过期资源
    val goHomeHint = stringResource(R.string.back_again_go_home)
    val exitHint = stringResource(R.string.back_again_exit)

    // 返回优先级由注册顺序自然决定：本回调在页面内容之前注册，故优先级最低。
    // 覆盖层打开时页面自身的返回处理都让出，这里直接关覆盖层；回到播放页后，
    // 页内层级（搜索、歌单）各自的 BackHandler 会先接管，都没接管才轮到双击退出
    var lastHomeBackTime by remember { mutableLongStateOf(0L) }
    BackHandler {
        if (overlay != null) {
            overlay = null
            return@BackHandler
        }
        val now = System.currentTimeMillis()
        if (now - lastHomeBackTime < DOUBLE_BACK_EXIT_MS) {
            if (playbackState.isPlayerActive) {
                // 播放中：返回桌面，保留后台播放与迷你播放器
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

    // 与页内层级（设置页、播放列表下钻）同速整宽位移，避免两处观感不一
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
            // 进入：覆盖层自右整宽滑入，底层播放页保持不动；
            // 退出：只把覆盖层向右滑走，播放页始终是已经画好的
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
                // 无覆盖层时留空：底下的播放页自己显示
                Box(Modifier.fillMaxSize())
            }
        }
    }
}

// 覆盖层页面：每次打开给一份独立的 ViewModelStore，关闭即清空，
// 与原「页面级 ViewModel 随导航条目回收」的生命周期一致，重进总是新实例
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
