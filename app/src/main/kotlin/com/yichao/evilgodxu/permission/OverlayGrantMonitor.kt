package com.yichao.evilgodxu.permission

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

// 悬浮窗授权监控：不随 Activity 销毁
object OverlayGrantMonitor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    // 只保留最新回调，避免持有旧实例
    @Volatile
    private var onGranted: ((Context) -> Unit)? = null

    @Synchronized
    fun start(context: Context, onGranted: (Context) -> Unit) {
        this.onGranted = onGranted
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            flow {
                while (true) {
                    val granted = Settings.canDrawOverlays(appContext)
                    emit(granted)
                    if (granted) break
                    delay(500)
                }
            }
                .flowOn(Dispatchers.IO)
                .collect { granted ->
                    if (granted) this@OverlayGrantMonitor.onGranted?.invoke(appContext)
                }
        }
    }

    // 结束监控，避免无意义轮询
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        onGranted = null
    }
}
