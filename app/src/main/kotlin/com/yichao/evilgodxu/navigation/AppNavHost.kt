package com.yichao.evilgodxu.navigation

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.LocalMusicPanelStateHolder
import com.yichao.evilgodxu.screens.cache.CacheScreen
import com.yichao.evilgodxu.screens.home.HomeScreen
import com.yichao.evilgodxu.screens.settings.SettingsScreen
import com.yichao.evilgodxu.screens.spectrum.SpectrumScreen
import com.yichao.evilgodxu.screens.typography.TypographyScreen

// 导航宿主：统一走路由栈
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    onExit: () -> Unit,
) {
    val backStack = rememberNavBackStack(Home)
    val context = LocalContext.current
    val stateHolder = LocalMusicPanelStateHolder.current
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
            if (stateHolder.state.isPlayerActive) {
                // 播放中：返回桌面，保留后台播放与迷你播放器
                activity?.moveTaskToBack(true)
            } else {
                stateHolder.releaseIfIdle()
                onExit()
            }
        } else {
            lastHomeBackTime = now
            val hint =
                if (stateHolder.state.isPlayerActive) goHomeHint else exitHint
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
        entryProvider = { key ->
            when (key) {
                is Home -> NavEntry(key) {
                    HomeScreen(
                        onOpenSettings = { backStack.add(Settings) },
                        onOpenSpectrum = { trackId -> backStack.add(Spectrum(trackId)) },
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
                else -> error("Unknown NavKey: $key")
            }
        },
    )
}

// 首页双击返回间隔
private const val DOUBLE_BACK_EXIT_MS = 2000L
