package com.yichao.evilgodxu

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yichao.evilgodxu.data.music.proxy.ProxyParseResult
import com.yichao.evilgodxu.permission.PermissionReturnMonitor
import com.yichao.evilgodxu.theme.SystemBarAppearance
import com.yichao.evilgodxu.floatingwindow.MiniPlayerController
import com.yichao.evilgodxu.localization.LocalizationManager
import com.yichao.evilgodxu.localization.ProvideLocalizedContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // 系统栏目标：首页全沉浸隐藏，其余全显示
    private enum class SystemBarTarget { HIDE_ALL, VISIBLE }

    private companion object {
        // 系统栏被外部显示后压回隐藏的最大延迟
        const val SYSTEM_BARS_AUTO_HIDE_DELAY_MS = 2000L
        // 外部文本导入上限 8MB，超限按读取失败
        const val MAX_PROXY_SOURCE_BYTES = 8 * 1024 * 1024
    }

    // 同一帧内的多次系统栏请求合并为一次下发
    private var systemBarsScheduled = false

    // 系统栏被外部显示后 2 秒压回，部分 ROM 收不到事件
    private val autoHideSystemBars = object : Runnable {
        override fun run() {
            val controller = window.insetsController ?: return
            if (systemBarTarget() == SystemBarTarget.VISIBLE) return
            applySystemBarsVisibility(controller)
            window.decorView.postDelayed(this, SYSTEM_BARS_AUTO_HIDE_DELAY_MS)
        }
    }

    // 手动 DI：经 Application 取单例
    private val app: App
        get() = application as App
    private val localizationManager: LocalizationManager
        get() = app.container.localizationManager
    private val miniPlayerController: MiniPlayerController
        get() = app.container.miniPlayerController
    private val activityViewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                MainViewModel(
                    settingsRepository = app.container.settingsRepository,
                    appVersion = app.container.appVersion,
                )
            }
        }
    }

    // 语言由系统应用语言偏好预置，无需自建镜像
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            // 图标外观由 Compose 控制，此处仅作初始兜底
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // 关掉导航栏强制对比度，让背景真透明，占位由界面侧避让
        window.isNavigationBarContrastEnforced = false
        setupSystemBars()
        // 绑定 Activity，使独立窗口切语言时同步资源
        localizationManager.bindActivity(this)

        handleExternalIntent(intent)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                miniPlayerController.onAppForegrounded()
            }

            override fun onStop(owner: LifecycleOwner) {
                miniPlayerController.onAppBackgrounded()
            }
        })

        setContent {
            val appUiState by activityViewModel.uiState.collectAsStateWithLifecycle()
            ProvideAppDependencies(app.container) {
                CompositionLocalProvider(LocalAppUiState provides appUiState) {
                    ProvideLocalizedContext(localizationManager) {
                        AppContent()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // 解除语言与系统栏回调的持有，避免泄漏
        stopSystemBarsAutoHide()
        window.decorView.removeOnLayoutChangeListener(layoutOrientationListener)
        SystemBarAppearance.onChanged = null
        localizationManager.unbindActivity(this)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode 变化可能重置系统栏，按朝向复读状态
        scheduleSystemBars()
    }

    override fun onResume() {
        super.onResume()
        scheduleSystemBars()
        startSystemBarsAutoHide()
        // 回前台即停止授权跟随轮询
        PermissionReturnMonitor.stop()
    }

    override fun onPause() {
        // 退后台停止守护，避免无意义下发
        stopSystemBarsAutoHide()
        super.onPause()
    }

    override fun onStop() {
        // 收起键盘，避免回前台误弹
        window.insetsController?.hide(WindowInsets.Type.ime())
        super.onStop()
    }

    // 窗口重获焦点可能重置系统栏，复读状态
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) scheduleSystemBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    // 处理外部打开/分享：音频后台播放，文本按代理导入
    private fun handleExternalIntent(intent: Intent) {
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        if (isAudioUri(uri, intent.type)) {
            miniPlayerController.playExternalInBackground(uri)
            moveTaskToBack(true)
        } else {
            importProxySource(uri)
        }
    }

    // 优先用 Intent 声明的类型，避免音频被当文本
    private fun isAudioUri(uri: Uri, declaredType: String?): Boolean =
        declaredType?.startsWith("audio/") == true ||
            contentResolver.getType(uri)?.startsWith("audio/") == true

    // 导入代理文本；读取与写盘阻塞，置于 IO 线程
    private fun importProxySource(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val text = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    readTextWithLimit(input, MAX_PROXY_SOURCE_BYTES)
                }
            }.getOrNull()
            val message = when {
                text.isNullOrBlank() -> getString(R.string.settings_proxy_source_import_read_error)
                else -> when (val result = app.container.proxySourceStore.import(text)) {
                    is ProxyParseResult.Success ->
                        getString(R.string.settings_proxy_source_import_success)
                    is ProxyParseResult.Failure -> getString(
                        R.string.settings_proxy_source_import_failed,
                        result.reason,
                    )
                }
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 超 limit 即中止，不整份读入
    private fun readTextWithLimit(input: InputStream, limit: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) throw IllegalStateException("外部文本超过读取上限: $limit 字节")
            output.write(buffer, 0, read)
        }
        return output.toString("UTF-8")
    }

    private fun setupSystemBars() {
        // 窗口操作统一收敛到 applySystemBars
        SystemBarAppearance.onChanged = { scheduleSystemBars() }
        installSystemBarsDiscovery()
        installSystemBarsOrientationTracking()
        scheduleSystemBars()
    }

    // 布局翻转后复核系统栏，避免尺寸滞后卡住显示态
    private fun installSystemBarsOrientationTracking() {
        window.decorView.addOnLayoutChangeListener(layoutOrientationListener)
    }

    // 仅宽高比翻转时复核系统栏
    private var layoutLandscape: Boolean? = null

    private val layoutOrientationListener = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
        val landscape = right - left > bottom - top
        if (landscape == layoutLandscape) return@OnLayoutChangeListener
        layoutLandscape = landscape
        scheduleSystemBars()
    }

    // 发现系统栏可见时重置计时；立即下发会与系统竞争成循环
    private fun installSystemBarsDiscovery() {
        window.decorView.setOnApplyWindowInsetsListener { view, insets ->
            val barsVisible = insets.isVisible(WindowInsets.Type.statusBars()) ||
                    insets.isVisible(WindowInsets.Type.navigationBars())
            if (barsVisible && systemBarTarget() != SystemBarTarget.VISIBLE) {
                startSystemBarsAutoHide()
            }
            view.onApplyWindowInsets(insets)
        }
    }

    // 重置守护计时，2 秒后压回隐藏
    private fun startSystemBarsAutoHide() {
        val decorView = window.decorView
        decorView.removeCallbacks(autoHideSystemBars)
        if (systemBarTarget() == SystemBarTarget.VISIBLE) return
        decorView.postDelayed(autoHideSystemBars, SYSTEM_BARS_AUTO_HIDE_DELAY_MS)
    }

    private fun stopSystemBarsAutoHide() {
        window.decorView.removeCallbacks(autoHideSystemBars)
    }

    // 合并同帧请求并延后执行，避免被窗口切换覆盖
    private fun scheduleSystemBars() {
        if (systemBarsScheduled) return
        systemBarsScheduled = true
        window.decorView.post {
            systemBarsScheduled = false
            applySystemBars()
            startSystemBarsAutoHide()
        }
    }

    // 按窗口实测宽高判朝向，配置读取可能滞后
    private fun isWindowLandscape(): Boolean {
        val decor = window.decorView
        if (decor.width > 0 && decor.height > 0) return decor.width > decor.height
        val bounds = windowManager.currentWindowMetrics.bounds
        return bounds.width() > bounds.height()
    }

    // 首页竖屏同横屏全沉浸，其余页面显示系统栏
    private fun systemBarTarget(): SystemBarTarget =
        if (isWindowLandscape() || SystemBarAppearance.isHomePortraitImmersive) {
            SystemBarTarget.HIDE_ALL
        } else {
            SystemBarTarget.VISIBLE
        }

    // 系统栏唯一下发点：图标外观与显隐一并处理
    private fun applySystemBars() {
        val controller = window.insetsController ?: return
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.setSystemBarsAppearance(
            if (SystemBarAppearance.isLightStatusBars) {
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            } else {
                0
            },
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
        controller.setSystemBarsAppearance(
            if (SystemBarAppearance.isLightNavigationBars) {
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            } else {
                0
            },
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        applySystemBarsVisibility(controller)
    }

    // 无条件下发显隐；insets 未必同步，短路会压不回
    private fun applySystemBarsVisibility(controller: WindowInsetsController) {
        when (systemBarTarget()) {
            SystemBarTarget.HIDE_ALL -> controller.hide(WindowInsets.Type.systemBars())
            SystemBarTarget.VISIBLE -> controller.show(WindowInsets.Type.systemBars())
        }
    }
}
