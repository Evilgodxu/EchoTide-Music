package com.yichao.evilgodxu.floatingwindow

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
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
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var composeView: ComposeView? = null
    private var isDismissing = false

    // 旋转不改窗口尺寸，故窗口自身不会重排；顶部位置需随屏幕朝向重新贴合
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            // 系统广播朝向变化时，窗口指标尚可能停留在旧值，推到下一帧再读
            composeView?.post { applyWindowLayout() }
        }

        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit
    }

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

        // 系统栏显隐、高度变化后重新贴合；目标位置由显示指标统一推算，重复回调会因目标未变而提前返回
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            applyWindowLayout()
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
        displayManager.registerDisplayListener(displayListener, null)

        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // 校正窗口纵向位置与展开高度：屏幕朝向、系统栏或尺寸变化后重新贴合
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
     * 窗口高度就是列表可见高度，故直接对窗口高度做补间：窗口长高多少就露出多少行，
     * Compose 侧同步铺开列表。
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

    // 展开高度 = 紧凑条 + 恰好五行的列表高度：按整行数、行间距与列表留白推算，
    // 与屏幕尺寸无关，横竖屏展开后都完整露出同样多的曲目
    private fun expandedHeightPx(): Int = barHeightPx() + dpToPx(
        MINI_PLAYLIST_VISIBLE_ROWS * MINI_PLAYLIST_ROW_HEIGHT_DP +
            (MINI_PLAYLIST_VISIBLE_ROWS - 1) * MINI_PLAYLIST_ROW_SPACING_DP +
            MINI_PLAYLIST_CONTENT_PADDING_V_DP * 2
    )

    private fun barHeightPx(): Int = dpToPx(BAR_HEIGHT_DP)

    // 迷你条宽度 = 左右内边距 + 封面 + 全部按钮
    private fun barWidthPx(): Int =
        dpToPx(MINI_PADDING_H_DP * 2 + MINI_COVER_DP + MINI_BUTTON_COUNT * MINI_BUTTON_DP)

    /**
     * 迷你播放器顶部纵坐标。
     *
     * 竖屏时状态栏在顶部，横屏时在侧边（顶部 inset 随之变为 0），两种朝向都只看顶部 inset，
     * 再加 1dp 下限使 inset 为 0 时仍与屏幕边缘留出间距。
     * 朝向不另取配置值：旋转瞬间配置与 inset 的更新先后不一，混用两者会配出「旧朝向 + 新 inset」
     * 这类不存在的组合，迷你条于是要么残留竖屏的状态栏占位，要么嵌进状态栏区域。
     *
     * 取不到指标时按间距下限放置，而非读系统内部尺寸：`status_bar_height` 是私有的框架资源，
     * 各厂商与版本都不保证存在或准确。
     */
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
                displayManager.unregisterDisplayListener(displayListener)
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
        // 状态栏不占顶部时的最小间距（横屏状态栏在侧边）
        private const val MIN_TOP_GAP_DP = 1
    }
}
