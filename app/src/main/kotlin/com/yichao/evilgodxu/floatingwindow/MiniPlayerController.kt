package com.yichao.evilgodxu.floatingwindow

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf
import com.yichao.evilgodxu.data.cache.CacheInventory
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.playback.ExternalPlaybackLoader
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.settings.miniPlayerEnabledFlow
import com.yichao.evilgodxu.permission.bringAppToFront
import com.yichao.evilgodxu.service.MusicPlaybackService
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

val LocalMiniPlayerController = staticCompositionLocalOf<MiniPlayerController?> { null }

// 悬浮播放条随前后台切换显隐
class MiniPlayerController(
    private val context: Context,
    private val playbackState: MusicPlaybackState,
    private val playlistRefresher: PlaylistRefresher,
    private val metadataEnricher: MetadataEnricher,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // 退出专用作用域：清理与结束进程须走完
    private val exitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 外部音频后台播放：装载与会话监听在此
    private val externalPlaybackLoader = ExternalPlaybackLoader(
        context = context,
        playbackState = playbackState,
        playlistRefresher = playlistRefresher,
        metadataEnricher = metadataEnricher,
    )
    private var miniPlayerManager: MiniPlayerViewManager? = null
    private var miniPlayerTemporarilyHidden = false
    private var miniPlayerEnabled = true
    private var appInForeground = true
    private var prefsJob: Job? = null
    private var externalBackgroundPlaying = false

    init {
        // 悬浮播放开关关闭时移除迷你播放器
        prefsJob = scope.launch {
            context.miniPlayerEnabledFlow().collect { enabled ->
                miniPlayerEnabled = enabled
                if (!enabled) dismissMiniPlayer()
            }
        }
        // 定时关闭到点且曲目播毕后结束应用
        playbackState.onSleepTimerFinished = { exitApplication() }
    }

    // 外部音频后台播放：不建界面，经迷你播放器播放
    fun playExternalInBackground(uri: Uri) {
        externalPlaybackLoader.play(uri)
        if (miniPlayerEnabled) {
            externalBackgroundPlaying = true
            showMiniPlayer()
        }
    }

    fun showMiniPlayer() {
        if (miniPlayerManager != null) return
        // 无悬浮窗权限则跳过
        if (!Settings.canDrawOverlays(context)) return
        miniPlayerManager = MiniPlayerViewManager(
            context = context,
            playbackState = playbackState,
            onOpenApp = { openApp() },
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

    // 点封面回应用；详情操作在应用内
    private fun openApp() = bringAppToFront(context)

    // 进后台时播放中则显示迷你播放器
    fun onAppBackgrounded() {
        appInForeground = false
        externalBackgroundPlaying = false
        maybeShowMiniPlayer()
    }

    // 回前台重置隐藏并移除迷你播放器
    fun onAppForegrounded() {
        appInForeground = true
        // 重置滑动隐藏状态，下次后台可再唤起
        miniPlayerTemporarilyHidden = false
        // 冷启动外部播放时先保留迷你播放器
        if (externalBackgroundPlaying) {
            externalBackgroundPlaying = false
        } else {
            dismissMiniPlayer()
        }
        // 回前台校正音频信息与当前曲目一致
        playbackState.reconcileTrackFormatInfo(context)
    }

    fun release() {
        prefsJob?.cancel()
        scope.cancel()
        dismissMiniPlayer()
        externalPlaybackLoader.release()
    }

    /** 结束整个应用：清理中转文件后杀死进程 */
    fun exitApplication() {
        dismissMiniPlayer()
        externalPlaybackLoader.release()
        context.stopService(Intent(context, MusicPlaybackService::class.java))
        exitScope.launch {
            // 清理失败也要结束进程
            runCatching { CacheInventory.reclaimOnExit(context) }
            Process.killProcess(Process.myPid())
        }
    }

    private fun maybeShowMiniPlayer() {
        if (appInForeground) return
        if (!miniPlayerEnabled || miniPlayerTemporarilyHidden) return
        if (miniPlayerManager != null) return
        if (!playbackState.isPlaying) return
        showMiniPlayer()
    }
}