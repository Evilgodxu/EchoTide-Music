package com.yichao.evilgodxu.screens.settings

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.cache.CacheInventory
import com.yichao.evilgodxu.data.music.api.MusicHttpClient
import com.yichao.evilgodxu.data.music.playback.usb.UsbAccessRequest
import com.yichao.evilgodxu.data.music.proxy.ProxyParseResult
import com.yichao.evilgodxu.data.music.proxy.ProxySourceSpec
import com.yichao.evilgodxu.data.music.proxy.ProxySourceStore
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.localization.LocalizationManager
import com.yichao.evilgodxu.log.CrashLogManager
import com.yichao.evilgodxu.permission.OverlayGrantMonitor
import com.yichao.evilgodxu.permission.PermissionReturnMonitor
import com.yichao.evilgodxu.permission.PermissionType
import com.yichao.evilgodxu.permission.bringAppToFront
import com.yichao.evilgodxu.permission.isNotificationPolicyGranted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** 代理音源导入的展示结果：文案由实现侧拼好，ViewModel 只负责放进状态 */
data class ProxyImportMessage(val text: String?, val failed: Boolean)

/**
 * 设置页需要的平台能力出口。
 *
 * 存在的唯一理由是让 [SettingsViewModel] 不持有 Context：代理音源、缓存采样与三条授权流程都是
 * 静态单例或系统服务，直接调用会把 ViewModel 钉死在 Android 运行时上，JVM 单测无从构造。
 * 这里只做委托，不含业务判定——判定留在 ViewModel，能力留在各自的实现。
 */
interface SettingsEnvironment {

    val appVersion: String

    fun proxySources(): List<ProxySourceSpec>

    // 外部写入（如系统分享导入）也要驱动列表刷新，故监听注册在本层
    fun registerProxySourceChanges(onChange: () -> Unit)

    fun unregisterProxySourceChanges()

    suspend fun importProxySource(content: String): ProxyImportMessage

    suspend fun setProxySourceEnabled(name: String, enabled: Boolean)

    suspend fun removeProxySource(name: String)

    suspend fun sampleCacheBytes(): Long

    // 语言热切换要换掉界面的本地化上下文，与授权流程同属界面侧平台动作
    fun applyLanguage(language: AppLanguage)

    fun canDrawOverlays(): Boolean

    fun startOverlayGrantMonitor()

    fun stopOverlayGrantMonitor()

    fun isNotificationPolicyGranted(): Boolean

    fun watchNotificationPolicyGrant()

    fun requestUsbAccess()
}

// Android 实现：逐项委托到既有的单例与系统服务，不新增状态
class AndroidSettingsEnvironment(
    private val context: Context,
    private val localizationManager: LocalizationManager,
) : SettingsEnvironment {

    private var proxyChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    override val appVersion: String
        get() = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName.orEmpty()

    override fun proxySources(): List<ProxySourceSpec> = ProxySourceStore.all(context)

    override fun registerProxySourceChanges(onChange: () -> Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange() }
        proxyChangeListener = listener
        ProxySourceStore.registerChangeListener(context, listener)
    }

    override fun unregisterProxySourceChanges() {
        val listener = proxyChangeListener ?: return
        ProxySourceStore.unregisterChangeListener(context, listener)
        proxyChangeListener = null
    }

    // 链接先拉取、文本直接解析；失败原因来自解析器，文案在此拼好后交给状态
    override suspend fun importProxySource(content: String): ProxyImportMessage =
        withContext(Dispatchers.IO) {
            val raw = if (content.startsWith("http://") || content.startsWith("https://")) {
                fetchText(content)
            } else {
                content
            }
            when {
                raw.isNullOrBlank() -> ProxyImportMessage(
                    text = context.getString(
                        R.string.settings_proxy_source_import_failed,
                        context.getString(R.string.settings_proxy_source_link_error),
                    ),
                    failed = true,
                )
                else -> when (val result = ProxySourceStore.import(context, raw)) {
                    is ProxyParseResult.Success -> ProxyImportMessage(text = null, failed = false)
                    is ProxyParseResult.Failure -> ProxyImportMessage(
                        text = context.getString(R.string.settings_proxy_source_import_failed, result.reason),
                        failed = true,
                    )
                }
            }
        }

    override suspend fun setProxySourceEnabled(name: String, enabled: Boolean) {
        withContext(Dispatchers.IO) { ProxySourceStore.setEnabled(context, name, enabled) }
    }

    override suspend fun removeProxySource(name: String) {
        withContext(Dispatchers.IO) { ProxySourceStore.remove(context, name) }
    }

    override suspend fun sampleCacheBytes(): Long = withContext(Dispatchers.IO) {
        CacheInventory.sample(context).sumOf { it.sizeBytes }
    }

    override fun applyLanguage(language: AppLanguage) {
        localizationManager.applyAppLocale(localizationManager.resolveLanguage(language))
    }

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    // 授权后自动把应用带回前台；部分 ROM 拦截后台拉起时，用户手动返回仍会触发对账
    override fun startOverlayGrantMonitor() {
        OverlayGrantMonitor.start(context) { appContext -> bringAppToFront(appContext) }
    }

    override fun stopOverlayGrantMonitor() = OverlayGrantMonitor.stop()

    override fun isNotificationPolicyGranted(): Boolean = isNotificationPolicyGranted(context)

    override fun watchNotificationPolicyGrant() {
        PermissionReturnMonitor.watch(context, PermissionType.NOTIFICATION_POLICY)
    }

    override fun requestUsbAccess() = UsbAccessRequest.requestIfNeeded(context)

    private fun fetchText(url: String): String? {
        return try {
            MusicHttpClient.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body.string().trim() else null
            }
        } catch (e: Exception) {
            CrashLogManager.logException("SettingsEnvironment", "拉取代理音源链接失败: $url", e)
            null
        }
    }
}
