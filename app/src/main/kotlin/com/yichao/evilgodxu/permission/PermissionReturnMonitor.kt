package com.yichao.evilgodxu.permission

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 设置页授权后归位：轮询到授权带回前台，上限三分钟 */
object PermissionReturnMonitor {
    // 500ms 一次，再慢会像没生效
    private const val POLL_INTERVAL_MS = 500L
    private const val TIMEOUT_MS = 180_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** 跟随该权限；已授予则不轮询 */
    fun watch(context: Context, type: PermissionType) {
        job?.cancel()
        val appContext = context.applicationContext
        job = scope.launch {
            val granted = withTimeoutOrNull(TIMEOUT_MS) {
                PermissionMonitor(appContext).monitorPermission(type, POLL_INTERVAL_MS).first { it }
            } ?: false
            if (granted) bringAppToFront(appContext)
        }
    }

    /** 停止跟随；回到前台或流程结束 */
    fun stop() {
        job?.cancel()
        job = null
    }
}
