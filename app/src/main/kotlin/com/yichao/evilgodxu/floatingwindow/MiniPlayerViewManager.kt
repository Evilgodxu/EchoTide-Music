package com.yichao.evilgodxu.floatingwindow

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.Gravity
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
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_PLAYLIST_ANIM_MS
import com.yichao.evilgodxu.floatingwindow.miniplayer.MINI_PLAYLIST_HEIGHT_FRACTION
import com.yichao.evilgodxu.floatingwindow.miniplayer.MiniPlayerOverlay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// 迷你播放器浮动窗管理器：状态栏下方的紧凑播放条，支持就地展开播放列表、回应用主界面与下滑隐藏
class MiniPlayerViewManager(
    private val context: Context,
    private val stateHolder: MusicPanelStateHolder,
    private val onOpenApp: () -> Unit,
    private val onSwipedDismiss: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var isDismissing = false
    private val playbackState: MusicPlaybackState get() = stateHolder.state

    // 顶部偏移基准。迷你条窗口宽度固定，纵向位置随状态栏高度校正
    private var statusBarHeight = currentTopInset()

    // 播放列表展开态：窗口高度在紧凑条与展开高度之间动画，Compose 侧据此决定是否铺开列表
    private val playlistExpanded = mutableStateOf(false)
    private var heightAnimator: ValueAnimator? = null

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

    // 触摸监听只消费窗口外的按下以便收起列表：这类事件不是落在本视图上的点击，
    // 视图自身也无点击语义，故无需转到 performClick（无障碍点击动作无对应行为）
    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    fun show() {
        if (composeView != null) return
        statusBarHeight = currentTopInset()

        val barH = barHeightPx()
        val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // 展开后才需要窗口外触摸：点击列表以外的区域即收起
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
                // 悬浮窗独立于 Activity 组合树，须自行为应用级依赖提供值，
                // 否则内部组件读取组合局部会触发默认 error 崩溃
                ProvideAppDependencies(app()) {
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
        // 窗口外触摸只用于收起列表，故在监听器里消费掉，不让它流进 Compose
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

        // 实时监听系统状态栏高度变化（刘海屏/分屏/折叠屏等动态调整）
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (top != statusBarHeight) {
                statusBarHeight = top
                applyWindowLayout()
            }
            ViewCompat.dispatchApplyWindowInsets(v, insets)
        }

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
    }

    // 校正窗口纵向位置与展开高度：状态栏高度或屏幕尺寸变化时重新贴合
    private fun applyWindowLayout() {
        val view = composeView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val targetY = topOffsetPx()
        val targetHeight = if (playlistExpanded.value) expandedHeightPx() else barHeightPx()
        if (targetY == params.y && targetHeight == params.height) return
        params.y = targetY
        params.height = targetHeight
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /**
     * 展开/收起播放列表。
     *
     * 窗口高度就是列表可见高度，故直接对窗口高度做补间动画：窗口长高多少就露出多少行，
     * Compose 侧同步铺开列表。收起在窗口外触摸或再次点击播放列表按钮时触发。
     */
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

    // 展开高度：屏幕最窄边的定比例，横竖屏取同一物理边长
    private fun expandedHeightPx(): Int {
        val bounds = windowManager.currentWindowMetrics.bounds
        return (min(bounds.width(), bounds.height()) * MINI_PLAYLIST_HEIGHT_FRACTION).roundToInt()
    }

    private fun barHeightPx(): Int = dpToPx(BAR_HEIGHT_DP)

    // 迷你条宽度 = 左右内边距 + 封面 + 全部按钮
    private fun barWidthPx(): Int =
        dpToPx(MINI_PADDING_H_DP * 2 + MINI_COVER_DP + MINI_BUTTON_COUNT * MINI_BUTTON_DP)

    // 横屏时状态栏位于屏幕侧边，顶部偏移为 0
    private fun isLandscape(): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * 当前窗口顶部状态栏 inset。
     *
     * 取不到 inset 时返回 0 而非读系统内部尺寸：`status_bar_height` 是私有的框架资源，
     * 各厂商与版本都不保证存在或准确。位置会在下一次 inset 回调里按真实值校正，
     * 因此瞬时偏差只影响首帧，不会长期停在错误位置
     */
    private fun currentTopInset(): Int = runCatching {
        windowManager.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.statusBars()).top
    }.getOrDefault(0)

    // 迷你播放器纵向位置：横屏状态栏在侧边，顶部仅保留 1dp 间距；
    // 竖屏位于状态栏下方。max 保留已记录的最大值：状态栏高度在横竖屏切换瞬间可能短暂读到 0，
    // 取较大值可避免迷你条在这一帧跳到屏幕顶端
    private fun topOffsetPx(): Int =
        if (isLandscape()) dpToPx(LANDSCAPE_TOP_GAP_DP)
        else max(statusBarHeight, currentTopInset())

    private fun dpToPx(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()

    // 滑动临时关闭：仅收起并回调给上层记录临时隐藏状态
    private fun temporaryDismiss() {
        dismiss(notifySwiped = true)
    }

    fun dismiss(notifySwiped: Boolean = false) {
        val view = composeView ?: return
        if (isDismissing) return
        isDismissing = true
        // 收起动画与高度动画会争夺同一个窗口参数，收起前先停掉高度动画
        heightAnimator?.cancel()
        heightAnimator = null

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
        // 迷你播放器条高度：紧凑容纳两行文本与 32dp 触控热区
        private const val BAR_HEIGHT_DP = 32
        // 横屏时顶部保留的间距
        private const val LANDSCAPE_TOP_GAP_DP = 1
    }
}
