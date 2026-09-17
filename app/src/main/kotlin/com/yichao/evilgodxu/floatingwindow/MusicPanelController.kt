package com.yichao.evilgodxu.floatingwindow

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Process
import androidx.compose.runtime.staticCompositionLocalOf
import com.yichao.evilgodxu.permission.mediaAudioPermission
import com.yichao.evilgodxu.permission.mediaImagePermission
import com.yichao.evilgodxu.data.settings.miniPlayerEnabledFlow
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.service.MusicPlaybackService
import com.yichao.evilgodxu.floatingwindow.MiniPlayerViewManager
import com.yichao.evilgodxu.floatingwindow.MusicPanelPermissionActivity
import com.yichao.evilgodxu.floatingwindow.MusicPanelPermissionBridge
import com.yichao.evilgodxu.floatingwindow.MusicPanelViewManager
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

// 向 Compose 层暴露音乐面板控制器的组合局部
val LocalMusicPanelController = staticCompositionLocalOf<MusicPanelController?> { null }

// 音乐面板与迷你播放器控制器：由 Activity 生命周期驱动悬浮窗的显示与隐藏
class MusicPanelController(
    private val context: Context,
    private val stateHolder: MusicPanelStateHolder,
    private val playlistRefresher: PlaylistRefresher,
    private val metadataEnricher: MetadataEnricher,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var panelManager: MusicPanelViewManager? = null
    private var miniPlayerManager: MiniPlayerViewManager? = null
    private var miniPlayerTemporarilyHidden = false
    private var miniPlayerEnabled = true
    private var appInForeground = true
    private var prefsJob: Job? = null
    private var pendingExternalPlayUri: android.net.Uri? = null
    private var externalBackgroundPlaying = false

    init {
        // 监听设置页悬浮播放开关，关闭时移除迷你播放器
        prefsJob = scope.launch {
            context.miniPlayerEnabledFlow().collect { enabled ->
                miniPlayerEnabled = enabled
                if (!enabled) dismissMiniPlayer()
            }
        }
        // 定时关闭到点且当前曲目播毕后，结束整个应用
        stateHolder.state.onSleepTimerFinished = { exitApplication() }
    }

    // 展开完整音乐面板；已显示时再次调用收起
    fun openMusicPanel() {
        if (panelManager?.hasWindow == true) {
            panelManager?.dismiss()
            panelManager = null
            return
        }
        showPanelInternal()
    }

    // 从外部打开/分享的音频 URI 后台播放：不展示全屏面板，经迷你播放器播放并保持后台运行
    fun playExternalInBackground(uri: android.net.Uri) {
        val manager = panelManager ?: MusicPanelViewManager(
            context = context,
            stateHolder = stateHolder,
            playlistRefresher = playlistRefresher,
            metadataEnricher = metadataEnricher,
            onDismiss = {
                panelManager = null
                maybeShowMiniPlayer()
            }
        ).also { panelManager = it }
        manager.playExternalInBackground(uri)
        if (miniPlayerEnabled) {
            externalBackgroundPlaying = true
            showMiniPlayer()
        }
    }

    private fun showPanelInternal() {
        dismissMiniPlayer()
        miniPlayerTemporarilyHidden = false

        val showPanel = {
            val manager = panelManager ?: MusicPanelViewManager(
                context = context,
                stateHolder = stateHolder,
                playlistRefresher = playlistRefresher,
                metadataEnricher = metadataEnricher,
                onDismiss = {
                    panelManager = null
                    maybeShowMiniPlayer()
                }
            ).also { panelManager = it }
            manager.show()
            val pendingUri = pendingExternalPlayUri
            if (pendingUri != null) {
                pendingExternalPlayUri = null
                manager.playExternalUri(pendingUri)
            }
            Unit
        }

        if (hasRequiredPermissions()) {
            showPanel()
        } else {
            MusicPanelPermissionBridge.pendingShowAction = showPanel
            val intent = Intent(context, MusicPanelPermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                MusicPanelPermissionBridge.clearPendingShowAction()
                pendingExternalPlayUri = null
                CrashLogManager.logException("MusicPanelController", "启动音乐面板权限页面失败", e)
            }
        }
    }

    fun showMiniPlayer() {
        if (miniPlayerManager != null) return
        // 未授予悬浮窗权限时无法添加窗口，跳过本次显示
        if (!android.provider.Settings.canDrawOverlays(context)) return
        miniPlayerManager = MiniPlayerViewManager(
            context = context,
            stateHolder = stateHolder,
            onExpandPanel = { openMusicPanel() },
            onSwipedDismiss = {
                miniPlayerTemporarilyHidden = true
                miniPlayerManager = null
            }
        ).also { it.show() }
    }

    fun dismissMiniPlayer() {
        miniPlayerManager?.dismiss()
        miniPlayerManager = null
    }

    // 应用进入后台：播放中且开关开启时显示迷你播放器
    fun onAppBackgrounded() {
        appInForeground = false
        externalBackgroundPlaying = false
        maybeShowMiniPlayer()
    }

    // 应用回到前台：重置临时隐藏状态并移除迷你播放器，避免遮挡应用界面
    fun onAppForegrounded() {
        appInForeground = true
        // 重置滑动隐藏状态，此后进入后台播放即可重新唤起迷你播放器，无需重启应用
        miniPlayerTemporarilyHidden = false
        // 外部触发后台播放冷启动时，先保留迷你播放器，不因本次瞬时前台而移除
        if (externalBackgroundPlaying) {
            externalBackgroundPlaying = false
        } else {
            dismissMiniPlayer()
        }
        // 后台切歌后回到前台，校正音频信息使其与当前曲目一致
        stateHolder.state.reconcileTrackFormatInfo(context)
    }

    fun release() {
        prefsJob?.cancel()
        scope.cancel()
        dismissMiniPlayer()
        panelManager?.dismiss()
        panelManager = null
    }

    // 结束整个应用：移除悬浮窗、停止播放前台服务并终止进程
    fun exitApplication() {
        dismissMiniPlayer()
        panelManager?.dismiss()
        panelManager = null
        context.stopService(Intent(context, MusicPlaybackService::class.java))
        Process.killProcess(Process.myPid())
    }

    // 完整面板关闭后，后台播放时恢复迷你播放器
    private fun maybeShowMiniPlayer() {
        if (appInForeground) return
        if (!miniPlayerEnabled || miniPlayerTemporarilyHidden) return
        if (panelManager != null) return
        if (miniPlayerManager != null) return
        if (!stateHolder.state.isPlaying) return
        showMiniPlayer()
    }

    private fun hasRequiredPermissions(): Boolean =
        context.checkSelfPermission(mediaAudioPermission()) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(mediaImagePermission()) == PackageManager.PERMISSION_GRANTED &&
            Environment.isExternalStorageManager()
}
