package com.yichao.evilgodxu.floatingwindow

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.yichao.evilgodxu.data.music.panel.MusicPanelStateHolder
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_BUTTON_COUNT
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_BUTTON_DP
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_COVER_DP
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_PADDING_H_DP
import com.yichao.evilgodxu.floatingwindow.miniplayer.MiniPlayerOverlay
import kotlin.math.max
import kotlin.math.roundToInt

// 迷你播放器浮动窗管理器：状态栏下方的紧凑播放条，支持展开完整面板与下拉播放列表
class MiniPlayerViewManager(
    private val context: Context,
    private val stateHolder: MusicPanelStateHolder,
    private val onExpandPanel: () -> Unit,
    private val onSwipedDismiss: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var isDismissing = false
    private val playbackState: MusicPlaybackState get() = stateHolder.state

    // 播放列表展开状态（Compose 状态 + 窗口布局共用）
    private val playlistExpanded = mutableStateOf(false)
    // 视觉展开状态：收起动画播放期间保持展开内容与全屏窗口，动画结束才恢复紧凑
    private val visualExpanded = mutableStateOf(false)
    private var statusBarHeight = getStatusBarHeight()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) collapsePlaylist()
        }
    }

    val isShowing: Boolean get() = composeView != null

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

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (composeView != null) return
        statusBarHeight = currentTopInset()
        playlistExpanded.value = false

        val barH = barHeightPx()
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        val params = WindowManager.LayoutParams(
            barWidthPx(),
            barH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topOffsetPx()
        }

        val view = ComposeView(context).apply {
            translationY = (-barH).toFloat()
            setContent {
                // 悬浮窗独立于 Activity 组合树，须自行为应用级依赖提供值，
                // 否则内部组件读取组合局部会触发默认 error 崩溃
                ProvideAppDependencies(app()) {
                    MiniPlayerOverlay(
                        playbackState = playbackState,
                        barHeightPx = barH,
                        barWidthPx = barWidthPx(),
                        playlistExpanded = playlistExpanded.value,
                        visualExpanded = visualExpanded.value,
                        onPlaylistExpandedChange = { expanded -> setPlaylistExpanded(expanded) },
                        onLayoutChanged = { applyWindowLayout() },
                        onCollapseAnimationEnd = { finalizeCollapse() },
                        onExpandPanel = onExpandPanel,
                        onSwipeDismiss = { temporaryDismiss() }
                    )
                }
            }
        }

        savedStateRegistryOwner.performAttach()
        savedStateRegistryOwner.performRestore()
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        view.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)

        // 实时监听系统状态栏高度变化（刘海屏/分屏/折叠屏等动态调整）
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (top != statusBarHeight) {
                statusBarHeight = top
                applyWindowLayout()
            }
            ViewCompat.dispatchApplyWindowInsets(v, insets)
        }

        // 点击迷你播放器窗口以外的区域：收起播放列表
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                collapsePlaylist()
                true
            } else false
        }
        // 系统返回键：收起播放列表
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                collapsePlaylist()
                true
            } else false
        }
        view.isFocusableInTouchMode = true

        composeView = view
        try {
            windowManager.addView(view, params)
        } catch (e: SecurityException) {
            CrashLogManager.logException("MiniPlayerViewManager", "添加迷你播放器失败（缺少悬浮窗权限）", e)
            composeView = null
            return
        } catch (e: WindowManager.BadTokenException) {
            CrashLogManager.logException("MiniPlayerViewManager", "添加迷你播放器失败（窗口令牌失效）", e)
            composeView = null
            return
        }
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .start()

        context.registerReceiver(
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    private fun setPlaylistExpanded(expanded: Boolean) {
        if (playlistExpanded.value == expanded) return
        playlistExpanded.value = expanded
        // 展开/收起时切换窗口焦点：展开移除 NOT_FOCUSABLE，使系统返回键可收起列表
        val view = composeView
        val params = view?.layoutParams as? WindowManager.LayoutParams
        if (params != null) {
            params.flags = if (expanded) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            // params 非空即 view 非空（params 由 view.layoutParams 派生），此处无需再断言
            runCatching { windowManager.updateViewLayout(view, params) }
        }
        if (expanded) {
            // 展开：视觉状态同步切换并立即铺满窗口，卡片缩放动画由 Compose 侧播放
            visualExpanded.value = true
            view?.let {
                it.isFocusableInTouchMode = true
                it.requestFocus()
            }
            applyWindowLayout()
        }
        // 收起：保留展开内容与全屏窗口以播放反向缩放动画，动画结束后由 finalizeCollapse 恢复紧凑
    }

    // 收起反向动画结束：切换到紧凑视觉状态并恢复窗口尺寸
    private fun finalizeCollapse() {
        visualExpanded.value = false
        applyWindowLayout()
    }

    private fun collapsePlaylist() {
        if (!playlistExpanded.value) return
        setPlaylistExpanded(false)
    }

    // 设置窗口尺寸与位置：视觉展开时铺满屏幕，收起后恢复紧凑条（不做逐帧动画，避免卡顿）
    private fun applyWindowLayout() {
        val view = composeView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val targetWidth: Int
        val targetHeight: Int
        val targetY: Int
        if (visualExpanded.value) {
            targetWidth = WindowManager.LayoutParams.MATCH_PARENT
            targetHeight = WindowManager.LayoutParams.MATCH_PARENT
            targetY = 0
        } else {
            targetWidth = barWidthPx()
            targetHeight = barHeightPx()
            targetY = topOffsetPx()
        }
        if (targetWidth != params.width || targetHeight != params.height || targetY != params.y) {
            params.width = targetWidth
            params.height = targetHeight
            params.y = targetY
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun barHeightPx(): Int = dpToPx(BAR_HEIGHT_DP)

    // 迷你条宽度 = 左右内边距 + 封面 + 全部按钮
    private fun barWidthPx(): Int =
        dpToPx(MINI_PADDING_H_DP * 2 + MINI_COVER_DP + MINI_BUTTON_COUNT * MINI_BUTTON_DP)

    @SuppressLint("DiscouragedApi")
    private fun getStatusBarHeight(): Int {
        val res = context.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    // 横屏时状态栏位于屏幕侧边，顶部偏移为 0
    private fun isLandscape(): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 实时获取当前窗口顶部状态栏 inset（横屏时状态栏位于侧边，顶部为 0）
    private fun currentTopInset(): Int = runCatching {
        windowManager.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.statusBars()).top
    }.getOrElse { if (isLandscape()) 0 else getStatusBarHeight() }

    // 迷你播放器纵向位置：横屏状态栏在侧边，顶部仅保留 1dp 间距；
    // 竖屏位于状态栏下方，状态栏高度未刷新（横屏遗留）时回退到系统标准高度，避免嵌入状态栏
    private fun topOffsetPx(): Int =
        if (isLandscape()) dpToPx(LANDSCAPE_TOP_GAP_DP)
        else max(statusBarHeight, getStatusBarHeight())

    private fun dpToPx(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    // 滑动临时关闭：仅收起并回调给上层记录临时隐藏状态
    private fun temporaryDismiss() {
        dismiss(notifySwiped = true)
    }

    fun dismiss(notifySwiped: Boolean = false) {
        val view = composeView ?: return
        if (isDismissing) return
        isDismissing = true

        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        view.animate()
            .translationY((-view.height).toFloat())
            .alpha(0f)
            .setDuration(240)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
                try {
                    if (view.windowToken != null) windowManager.removeView(view)
                } catch (e: Exception) {
                    CrashLogManager.logException("MiniPlayerViewManager", "移除迷你播放器失败", e)
                }
                mainHandler.post {
                    try {
                        context.unregisterReceiver(screenOffReceiver)
                    } catch (e: Exception) {
                        CrashLogManager.logException("MiniPlayerViewManager", "注销熄屏监听失败", e)
                    }
                }
                composeView = null
                isDismissing = false
                if (notifySwiped) onSwipedDismiss()
            }
            .start()
    }

    companion object {
        // 迷你播放器条高度：紧凑容纳两行文本与 32dp 触控热区
        private const val BAR_HEIGHT_DP = 32
        // 横屏时顶部保留的间距
        private const val LANDSCAPE_TOP_GAP_DP = 1
        const val MAX_VISIBLE_ROWS = 5
    }
}
