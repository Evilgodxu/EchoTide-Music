package com.yichao.evilgodxu.navigation

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.LocalPlaybackState
import com.yichao.evilgodxu.screens.cache.CacheScreen
import com.yichao.evilgodxu.screens.home.HomeScreen
import com.yichao.evilgodxu.screens.metadata.MetadataScreen
import com.yichao.evilgodxu.screens.settings.SettingsScreen
import com.yichao.evilgodxu.screens.spectrum.SpectrumScreen
import com.yichao.evilgodxu.screens.typography.TypographyScreen
import com.yichao.evilgodxu.theme.SystemBarAppearance
import com.yichao.evilgodxu.windowsize.rememberWindowLandscape

@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
) {
    val backStack = rememberNavBackStack(Home)
    val context = LocalContext.current
    val playbackState = LocalPlaybackState.current
    // 首页竖屏沉浸（隐藏全部系统栏）：由栈顶目的地驱动。若等到首页组合销毁才撤销，
    // 沉浸状态会在切换动画期间残留，使目标页标题栏先按零内边距嵌入状态栏区域、再整体下移
    val isHomeOnTop = backStack.lastOrNull() is Home
    val isPortrait = !rememberWindowLandscape()
    LaunchedEffect(isHomeOnTop, isPortrait) {
        SystemBarAppearance.isHomePortraitImmersive = isHomeOnTop && isPortrait
    }
    // LocalContext 已被本地化包装，宿主 Activity 需从注册表所有者获取
    val activity = LocalActivityResultRegistryOwner.current as? Activity
    // 预取提示文案，配置变化时由 Compose 自动更新，避免在回调中读取过期资源
    val goHomeHint = stringResource(R.string.back_again_go_home)
    val exitHint = stringResource(R.string.back_again_exit)

    // 首页根节点 NavDisplay 不拦截返回，需自行拦截：双击返回桌面（播放中）或退出（未播放）
    var lastHomeBackTime by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = backStack.size <= 1) {
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
    fun onBack() {
        // 保留根节点，防止 pop 动画期间重复返回把回退栈清空导致 NavDisplay 崩溃
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { onBack() },
        modifier = modifier,
        // 保留默认的可保存状态装饰器，并叠加 ViewModel 作用域装饰器：
        // 页面级 ViewModel 的生命周期与导航条目对齐，出栈即回收，回栈新建
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = { key ->
            when (key) {
                is Home -> NavEntry(key) {
                    HomeScreen(
                        onOpenSettings = { backStack.add(Settings) },
                        onOpenSpectrum = { trackId -> backStack.add(Spectrum(trackId)) },
                        onOpenMetadata = { trackId -> backStack.add(Metadata(trackId)) },
                    )
                }
                is Settings -> NavEntry(key) {
                    SettingsScreen(
                        onBack = { onBack() },
                        onOpenTypography = { backStack.add(Typography) },
                        onOpenCache = { backStack.add(Cache) },
                    )
                }
                is Typography -> NavEntry(key) {
                    TypographyScreen(onBack = { onBack() })
                }
                is Cache -> NavEntry(key) {
                    CacheScreen(onBack = { onBack() })
                }
                is Spectrum -> NavEntry(key) {
                    SpectrumScreen(trackId = key.trackId, onBack = { onBack() })
                }
                is Metadata -> NavEntry(key) {
                    MetadataScreen(trackId = key.trackId, onBack = { onBack() })
                }
                else -> error("Unknown NavKey: $key")
            }
        },
    )
}

// 首页双击返回间隔
private const val DOUBLE_BACK_EXIT_MS = 2000L
