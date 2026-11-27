package com.yichao.evilgodxu.floatingwindow

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.mutableStateOf
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
import com.yichao.evilgodxu.data.music.playback.MusicPlaybackState
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_BUTTON_COUNT
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_BUTTON_DP
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_COVER_DP
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_PADDING_H_DP
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_PLAYLIST_ANIM_MS
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_PLAYLIST_CONTENT_PADDING_V_DP
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_PLAYLIST_ROW_HEIGHT_DP
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_PLAYLIST_ROW_SPACING_DP
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_PLAYLIST_VISIBLE_ROWS
import com.yichao.evilgodxu.floatingwindow.miniplayer.MiniPlayerOverlay
import kotlin.math.max
import kotlin.math.roundToInt

class MiniPlayerViewManager(
    private val context: Context,
    private val playbackState: MusicPlaybackState,
    private val onOpenApp: () -> Unit,
    private val onSwipedDismiss: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var composeView: ComposeView? = null
    private var isDismissing = false

    /** 轮询顶部状态栏 inset 以贴合位置；息屏时挂起 */
    private val statusBarWatcher = object : Runnable {
        override fun run() {
            val view = composeView ?: return
            if (!powerManager.isInteractive) return
            applyWindowLayout()
            view.postDelayed(this, STATUS_BAR_POLL_MS)
        }
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val view = composeView ?: return
            view.removeCallbacks(statusBarWatcher)
            view.post(statusBarWatcher)
        }
    }

    // 播放列表展开态：窗口高度随之动画
    private val playlistExpanded = mutableStateOf(false)
    private var heightAnimator: ValueAnimator? = null

    val isShowing: Boolean get() = composeView != null

    // 悬浮窗组合树经 Application 取单例
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

    // 只消费窗口外按下以收起列表
    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    fun show() {
        if (composeView != null) return

        val barH = barHeightPx()
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // 窗口外触摸用于收起列表
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

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
                // 悬浮窗须自备应用级依赖，否则读取局部会崩溃
                ProvideAppDependencies(app().container) {
                    MiniPlayerOverlay(
                        playbackState = playbackState,
                        barHeightPx = barH,
                        barWidthPx = barWidthPx(),
                        playlistExpanded = playlistExpanded.value,
                        onTogglePlaylist = { togglePlaylist() },
                        onOpenApp = onOpenApp,
                        onSwipeDismiss = { temporaryDismiss() }
                    )
                }
            }
        }
        // 消费窗口外触摸，不流进 Compose
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                if (playlistExpanded.value) togglePlaylist()
                true
            } else {
                false
            }
        }

        savedStateRegistryOwner.performAttach()
        savedStateRegistryOwner.performRestore()
        view.setViewTreeLifecycleOwner(lifecycleOwner)
        view.setViewTreeViewModelStoreOwner(viewModelStoreOwner)
        view.setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)

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
        context.registerReceiver(
            screenOnReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON),
            Context.RECEIVER_NOT_EXPORTED
        )
        view.postDelayed(statusBarWatcher, STATUS_BAR_POLL_MS)

        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // 贴合状态栏重算位置与高度；展开动画中不动高度
    private fun applyWindowLayout() {
        val view = composeView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val targetY = topOffsetPx()
        val targetHeight = when {
            heightAnimator?.isRunning == true -> params.height
            playlistExpanded.value -> expandedHeightPx()
            else -> barHeightPx()
        }
        if (targetY == params.y && targetHeight == params.height) return
        params.y = targetY
        params.height = targetHeight
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /** 展开/收起播放列表：对窗口高度做补间 */
    private fun togglePlaylist() {
        val view = composeView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val expand = !playlistExpanded.value
        val targetHeight = if (expand) expandedHeightPx() else barHeightPx()
        playlistExpanded.value = expand

        heightAnimator?.cancel()
        heightAnimator = ValueAnimator.ofInt(params.height, targetHeight).apply {
            duration = MINI_PLAYLIST_ANIM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                params.height = animator.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, params) }
            }
            start()
        }
    }

    // 展开高度 = 紧凑条 + 五行列表高度
    private fun expandedHeightPx(): Int = barHeightPx() + dpToPx(
        MINI_PLAYLIST_VISIBLE_ROWS * MINI_PLAYLIST_ROW_HEIGHT_DP +
            (MINI_PLAYLIST_VISIBLE_ROWS - 1) * MINI_PLAYLIST_ROW_SPACING_DP +
            MINI_PLAYLIST_CONTENT_PADDING_V_DP * 2
    )

    private fun barHeightPx(): Int = dpToPx(BAR_HEIGHT_DP)

    // 宽度 = 内边距 + 封面 + 按钮
    private fun barWidthPx(): Int =
        dpToPx(MINI_PADDING_H_DP * 2 + MINI_COVER_DP + MINI_BUTTON_COUNT * MINI_BUTTON_DP)

    /** 顶部纵坐标：贴状态栏 inset，最低 1dp；不按朝向判断 */
    private fun topOffsetPx(): Int {
        val topInset = runCatching {
            windowManager.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.statusBars()).top
        }.getOrDefault(0)
        return max(topInset, dpToPx(MIN_TOP_GAP_DP))
    }

    private fun dpToPx(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    private fun temporaryDismiss() {
        dismiss(notifySwiped = true)
    }

    fun dismiss(notifySwiped: Boolean = false) {
        val view = composeView ?: return
        if (isDismissing) return
        isDismissing = true
        // 先停高度动画，避免争用窗口参数
        heightAnimator?.cancel()
        heightAnimator = null
        view.removeCallbacks(statusBarWatcher)
        context.unregisterReceiver(screenOnReceiver)

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
                composeView = null
                isDismissing = false
                if (notifySwiped) onSwipedDismiss()
            }
            .start()
    }

    companion object {
        // 紧凑容纳两行文本与最小触控热区
        private const val BAR_HEIGHT_DP = 32
        // 状态栏不占顶部时的最小间距
        private const val MIN_TOP_GAP_DP = 1
        // 轮询间隔：兼顾延迟隐藏与开销
        private const val STATUS_BAR_POLL_MS = 500L
    }
}
