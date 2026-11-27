package com.yichao.evilgodxu.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.LocalProxySourceStore
import com.yichao.evilgodxu.data.music.proxy.OnlinePlatformOption
import com.yichao.evilgodxu.data.music.proxy.OnlinePlatformRegistry

// 平台候选项随音源增删即时变化
@Composable
internal fun rememberOnlinePlatformOptions(): List<OnlinePlatformOption> {
    val context = LocalContext.current
    val sources by LocalProxySourceStore.current.sources.collectAsStateWithLifecycle()
    return remember(context, sources) { OnlinePlatformRegistry.options(context, sources) }
}
