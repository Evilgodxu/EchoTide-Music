package com.yichao.evilgodxu.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.LocalProxySourceStore
import com.yichao.evilgodxu.data.music.proxy.OnlinePlatformOption
import com.yichao.evilgodxu.data.music.proxy.OnlinePlatformRegistry

// 平台切换菜单的候选项：随音源导入 / 移除即时变化。
// 订阅存储的状态流而非在每次重组时重新求值，既避免搜索输入等高频重组反复解析音源，
// 也保证菜单在音源被增删后立即反映最新候选
@Composable
internal fun rememberOnlinePlatformOptions(): List<OnlinePlatformOption> {
    val context = LocalContext.current
    val sources by LocalProxySourceStore.current.sources.collectAsStateWithLifecycle()
    return remember(context, sources) { OnlinePlatformRegistry.options(context, sources) }
}
