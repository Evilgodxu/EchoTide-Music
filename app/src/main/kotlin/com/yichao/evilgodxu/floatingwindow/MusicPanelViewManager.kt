package com.yichao.evilgodxu.floatingwindow

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yichao.evilgodxu.App
import com.yichao.evilgodxu.ProvideAppDependencies
import com.yichao.evilgodxu.data.music.model.MusicTrack
import com.yichao.evilgodxu.data.music.MusicScanner
import com.yichao.evilgodxu.data.music.normalizedAudioUri
import com.yichao.evilgodxu.data.music.PlaylistRefresher
import com.yichao.evilgodxu.data.music.resolveLocalPath
import com.yichao.evilgodxu.data.music.metadata.MetadataEnricher
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.data.music.playback.playTrackAt
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.ui.component.player.MusicPanelOverlay
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// 音乐面板悬浮窗管理器
class MusicPanelViewManager(
    private val context: Context,
    private val stateHolder: MusicPanelStateHolder,
    private val playlistRefresher: PlaylistRefresher,
    private val metadataEnricher: MetadataEnricher,
    private val onDismiss: () -> Unit,
    private val onShowFailed: ((WindowManager.BadTokenException) -> Unit)? = null
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var isDismissing = false
    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(managerJob + Dispatchers.IO)

    private val playbackState: MusicPlaybackState get() = stateHolder.state
    private var pendingExternalUri: android.net.Uri? = null
    private val externalTrackMutex = Mutex()
    private var initialization: Deferred<Unit>? = null
    private var mediaObserverRegistered = false
    private var refreshJob: Job? = null
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            refreshJob?.cancel()
            refreshJob = managerScope.launch {
                delay(300)
                refreshPlaylist()
            }
        }
    }

    fun playExternalUri(uri: android.net.Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            CrashLogManager.logException("MusicPanelViewManager", "获取外部音频持久访问权限失败", e)
            // 外部应用可能只授予临时读取权限，仍需继续播放当前 URI
        }
        pendingExternalUri = uri
        if (composeView == null) {
            show()
        }
        loadExternalTrack()
    }

    // 后台模式播放外部音频：仅初始化播放状态，不展示全屏面板
    fun playExternalInBackground(uri: android.net.Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            CrashLogManager.logException("MusicPanelViewManager", "获取外部音频持久访问权限失败", e)
            // 外部应用可能只授予临时读取权限，仍需继续播放当前 URI
        }
        pendingExternalUri = uri
        if (initialization == null) {
            initialize()
        }
        loadExternalTrack()
    }

    // 当前是否展示全屏悬浮窗
    val hasWindow: Boolean get() = composeView != null

    // 悬浮窗组合树所需的应用级单例宿主：经 Application 取用
    private fun app(): App =
        context.applicationContext as App

    private val lifecycleOwner = object : LifecycleOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        fun handleLifecycleEvent(event: Lifecycle.Event) = lifecycleRegistry.handleLifecycleEvent(event)
    }

    private val viewModelStoreOwner = object : ViewModelStoreOwner {
        private val store = ViewModelStore()
        override val viewModelStore: ViewModelStore get() = store
    }

    private val savedStateRegistryOwner = object : SavedStateRegistryOwner {
        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
        fun performAttach() = controller.performAttach()
        fun performRestore() = controller.performRestore(null)
    }

    // 显示音乐面板悬浮窗
    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (composeView != null) return

        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            blurBehindRadius = 80
        }

        val view = ComposeView(context).apply {
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f
            setContent {
                // 悬浮窗独立于 Activity 组合树，须自行为应用级依赖提供值，
                // 否则内部组件读取组合局部会触发默认 error 崩溃
                ProvideAppDependencies(app()) {
                    MusicPanelOverlay(
                        playbackState = playbackState,
                        onScan = { requestScan() },
                        onDismiss = { dismiss() }
                    )
                }
            }
        }

        savedStateRegistryOwner.performAttach()
        savedStateRegistryOwner.performRestore()
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        view.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)

        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss()
                true
            } else {
                false
            }
        }
        view.isFocusableInTouchMode = true
        view.requestFocus()

        composeView = view
        try {
            windowManager.addView(view, params)
        } catch (e: SecurityException) {
            CrashLogManager.logException("MusicPanelViewManager", "添加音乐面板失败（缺少悬浮窗权限）", e)
            composeView = null
            return
        } catch (e: WindowManager.BadTokenException) {
            CrashLogManager.logException("MusicPanelViewManager", "添加音乐面板失败（窗口令牌失效）", e)
            composeView = null
            onShowFailed?.invoke(e)
            return
        }
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(250)
            .setInterpolator(DecelerateInterpolator())
            .start()

        initialize()
    }

    // 初始化播放状态与外部设备监听；仅执行一次，供全屏面板与后台模式共用
    private fun initialize() {
        if (initialization != null) return
        initialization = managerScope.async {
            playbackState.restoreSavedState(context)
            // MediaController 仅限主线程访问，须在 Main 线程读取其状态
            val playerActive = withContext(Dispatchers.Main) { playbackState.isPlayerActive }
            if (!playerActive) {
                playbackState.removeUnavailableExternalTracks(context)
            }
            if (playbackState.playlist.isEmpty()) {
                scanAndPlay()
            } else {
                withContext(Dispatchers.Main) {
                    restoreCurrentTrack()
                    // 后台预读当前曲目歌词：不阻塞面板首帧，歌词页打开时已就绪
                    playbackState.requestMetadata(playbackState.currentTrack)
                }
                // 封面/歌词由 UI 按需懒加载补齐首屏，全量补全延迟执行，
                // 避免启动瞬间批量提取抢占按需任务的 IO，拖慢封面与歌词显示
                managerScope.launch {
                    delay(3_000)
                    metadataEnricher.enrichAndCleanup(context, playbackState)
                }
            }
            withContext(Dispatchers.Main) {
                playbackState.syncPlaybackState()
                playbackState.updatePosition()
            }
            registerMediaObserver()
        }
    }

    private fun resolveAudioPath(uri: Uri): String? =
        resolveLocalPath(context, uri.toString())

    private fun isSameAudioTrack(track: MusicTrack, targetUri: Uri, targetPath: String?): Boolean {
        return normalizedAudioUri(track.audioUri) == normalizedAudioUri(targetUri.toString()) ||
                (targetPath != null && track.path.isNotBlank() && track.path == targetPath)
    }

    private fun registerMediaObserver() {
        if (mediaObserverRegistered) return
        context.contentResolver.registerContentObserver(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver
        )
        mediaObserverRegistered = true
    }

    private fun requestScan() {
        if (playbackState.isScanning || isDismissing) return
        refreshJob?.cancel()
        refreshJob = managerScope.launch {
            scanAndPlay()
        }
    }

    private suspend fun refreshPlaylist() {
        playlistRefresher.refresh(context, playbackState, restoreCurrent = false) {
            // 刷新后后台加载封面与歌词，完成合并后再清理孤立缓存
            managerScope.launch { metadataEnricher.enrichAndCleanup(context, playbackState) }
        }
    }

    private fun deduplicateTracks(tracks: List<MusicTrack>): List<MusicTrack> {
        return tracks.distinctBy { normalizedAudioUri(it.audioUri) }
    }

    private fun loadExternalTrack() {
        val uri = pendingExternalUri ?: return
        managerScope.launch {
            externalTrackMutex.withLock {
                initialization?.await()
                if (pendingExternalUri != uri) return@withLock
                val track = MusicScanner.fromUri(context, uri) ?: return@withLock
                val targetUri = Uri.parse(track.audioUri).normalizeScheme()
                val targetPath = resolveAudioPath(targetUri)
                val targetIndex = withContext(Dispatchers.Main) {
                    val existingIndex = playbackState.playlist.indexOfFirst {
                        isSameAudioTrack(it, targetUri, targetPath)
                    }
                    if (existingIndex >= 0) {
                        existingIndex
                    } else {
                        playbackState.playlist = deduplicateTracks(playbackState.playlist + track)
                        playbackState.playlist.indexOfFirst {
                            isSameAudioTrack(it, targetUri, targetPath)
                        }
                    }
                }
                if (targetIndex < 0) return@withLock
                playbackState.persistPlaylist()
                withContext(Dispatchers.Main) {
                    playbackState.currentIndex = targetIndex
                    playbackState.currentTrack = playbackState.playlist[targetIndex]
                }
                withContext(Dispatchers.Main) {
                    playTrackAt(context, playbackState, targetIndex)
                }
                pendingExternalUri = null
            }
        }
    }

    private suspend fun scanAndPlay() {
        playlistRefresher.refresh(context, playbackState, restoreCurrent = true) {
            // 封面后台加载，不阻塞 isScanning 重置
            managerScope.launch { metadataEnricher.enrichAndCleanup(context, playbackState) }
        }
    }

    private fun restoreCurrentTrack() {
        if (playbackState.playlist.isEmpty() || playbackState.currentTrack != null) return
        val savedUri = playbackState.pendingSavedUri
        val index = savedUri?.let { uri -> playbackState.playlist.indexOfFirst { it.audioUri == uri } }
            ?.takeIf { it >= 0 }
            ?: 0
        playbackState.currentIndex = index
        playbackState.currentTrack = playbackState.playlist[index]
    }

    // 关闭音乐面板（保留播放状态与 ExoPlayer，下次显示直接恢复）
    fun dismiss() {
        val view = composeView ?: return
        if (isDismissing) return
        isDismissing = true

        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)

        view.animate()
            .alpha(0f)
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(200)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                try {
                    if (view.windowToken != null) {
                        windowManager.removeView(view)
                    }
                } catch (e: Exception) {
                    CrashLogManager.logException("MusicPanelViewManager", "移除音乐面板失败", e)
                }
                composeView = null
                isDismissing = false
                if (mediaObserverRegistered) {
                    context.contentResolver.unregisterContentObserver(mediaObserver)
                    mediaObserverRegistered = false
                }
                playbackState.updatePosition()
                if (!playbackState.isPlayerActive) {
                    playbackState.softRelease()
                }
                onDismiss()
                managerJob.cancel()
            }
            .start()
    }
}
