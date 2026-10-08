package com.yichao.evilgodxu.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// 可见会话序号：窗口不可见时合成与动画随窗口一并停摆，状态与显示值都停在切后台那一刻；
// 重新可见时，不可见期间累积的变化作为一批「迟到的变化」一次性到达，各过渡会误当作刚刚发生的事件
// 补播一次。故把会话序号交给过渡状态作键：会话重建即视为一次落位，直接贴合当前状态。
//
// 判据取 ON_START 而非 ON_RESUME：ON_RESUME 在对话框、输入法等交互之后也会重放一次，
// 而那些时段页面始终可见，期间的变更仍属「已经发生且看得见」，不该抹掉过渡；
// 只有走到 Stop（页面真正不可见）才算一段没人看见的时间。
@Composable
internal fun rememberVisibleSession(): Int {
    val lifecycleOwner = LocalLifecycleOwner.current
    var session by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) session++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return session
}
