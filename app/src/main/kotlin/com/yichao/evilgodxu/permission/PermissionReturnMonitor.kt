package com.yichao.evilgodxu.permission

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 系统设置页授权后的归位。
 *
 * 「全部文件」「省电策略」「免打扰」三项都要跳到系统设置页去开，而系统在用户授权后不会自动
 * 返回本应用——用户开完开关仍停在那页，多半不知道还得自己切回来。这里在跳转后轮询授权状态，
 * 一旦授予就把任务带回前台。
 *
 * 轮询有上限：授权是用户在设置页里的一个动作，通常十几秒内完成；留足三分钟容纳「顺手改了
 * 别的设置再回来」，超时只是兜住「根本不打算开」的情况，不为一次没有发生的授权一直问下去。
 * 同一时刻只跟随一项权限，重复发起即取消上一次。
 */
object PermissionReturnMonitor {
    // 轮询间隔取 500ms：授权开关拨下到系统落库通常就在这一档内，再慢会让人以为没生效
    private const val POLL_INTERVAL_MS = 500L
    private const val TIMEOUT_MS = 180_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** 开始跟随 [type] 的授权状态；已授予时不启动轮询。 */
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

    /** 停止跟随：页面自己回到前台或授权流程结束时可调用。 */
    fun stop() {
        job?.cancel()
        job = null
    }
}
