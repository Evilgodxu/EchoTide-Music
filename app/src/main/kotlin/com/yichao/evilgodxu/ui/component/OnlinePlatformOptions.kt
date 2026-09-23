package com.yichao.evilgodxu.ui.component

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yichao.evilgodxu.data.music.proxy.OnlinePlatformOption
import com.yichao.evilgodxu.data.music.proxy.OnlinePlatformRegistry
import com.yichao.evilgodxu.data.music.proxy.ProxySourceStore

// 平台切换菜单的候选项：随音源导入 / 移除即时变化。
// 订阅存储变更而非在每次重组时重新求值，既避免搜索输入等高频重组反复解析音源，
// 也保证菜单在音源被增删后立即反映最新候选
@Composable
internal fun rememberOnlinePlatformOptions(): List<OnlinePlatformOption> {
    val context = LocalContext.current
    var options by remember { mutableStateOf(OnlinePlatformRegistry.options(context)) }
    DisposableEffect(context) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            options = OnlinePlatformRegistry.options(context)
        }
        ProxySourceStore.registerChangeListener(context, listener)
        onDispose { ProxySourceStore.unregisterChangeListener(context, listener) }
    }
    return options
}
