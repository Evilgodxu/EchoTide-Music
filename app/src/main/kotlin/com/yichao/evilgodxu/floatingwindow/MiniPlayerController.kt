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
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.data.music.playback.ExternalPlaybackLoader
import com.yichao.evilgodxu.data.settings.miniPlayerEnabledFlow
import com.yichao.evilgodxu.permission.bringAppToFront
import com.yichao.evilgodxu.service.MusicPlaybackService
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

// 向 Compose 层暴露迷你播放器控制器的组合局部
val LocalMiniPlayerController = staticCompositionLocalOf<MiniPlayerController?> { null }

// 迷你播放器控制器：由 Activity 生命周期驱动悬浮播放条的显示与隐藏
class MiniPlayerController(
    private val context: Context,
    private val stateHolder: MusicPanelStateHolder,
    private val playlistRefresher: PlaylistRefresher,
    private val metadataEnricher: MetadataEnricher,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // 退出流程专用作用域：不随控制器自身作用域释放而取消 —— 退出清理与结束进程一旦开始就必须走完，
    // 被半途取消会让进程既不退出也不再清理
    private val exitScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 外部音频的后台播放：会话装载与曲库监听都在其中，不在控制器里重复一份
    private val externalPlaybackLoader = ExternalPlaybackLoader(
        context = context,
        playbackState = stateHolder.state,
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

    // 从外部打开/分享的音频 URI 后台播放：不建立界面，经迷你播放器播放并保持后台运行
    fun playExternalInBackground(uri: Uri) {
        externalPlaybackLoader.play(uri)
        if (miniPlayerEnabled) {
            externalBackgroundPlaying = true
            showMiniPlayer()
        }
    }

    fun showMiniPlayer() {
        if (miniPlayerManager != null) return
        // 未授予悬浮窗权限时无法添加窗口，跳过本次显示
        if (!Settings.canDrawOverlays(context)) return
        miniPlayerManager = MiniPlayerViewManager(
            context = context,
            stateHolder = stateHolder,
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

    // 点封面回到应用主界面：迷你播放器只承载播放与歌单，详情操作在应用内进行
    private fun openApp() = bringAppToFront(context)

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
        externalPlaybackLoader.release()
    }

    /**
     * 结束整个应用：移除悬浮窗、停止播放前台服务，回收孤儿文件后终止进程。
     *
     * 退出前的回收见 [CacheInventory.reclaimOnExit]。调用方须先把任务退到后台（见 AppContent），
     * 让这次清理与随后的进程结束都发生在后台，而不是停在前台界面上把流程走完。
     */
    fun exitApplication() {
        dismissMiniPlayer()
        externalPlaybackLoader.release()
        context.stopService(Intent(context, MusicPlaybackService::class.java))
        // 引用集在这里取好再交给清理：它是「哪些缓存还有主人」的唯一判据，须与此刻的播放状态一致
        val lyricReferences = stateHolder.state.lyricCacheReferences()
        exitScope.launch {
            // 回收失败也要结束进程：退出路径不能因一次清理异常就卡住不退出
            runCatching { CacheInventory.reclaimOnExit(context, lyricReferences) }
            Process.killProcess(Process.myPid())
        }
    }

    // 迷你播放器移除后，后台播放时恢复显示
    private fun maybeShowMiniPlayer() {
        if (appInForeground) return
        if (!miniPlayerEnabled || miniPlayerTemporarilyHidden) return
        if (miniPlayerManager != null) return
        if (!stateHolder.state.isPlaying) return
        showMiniPlayer()
    }
}