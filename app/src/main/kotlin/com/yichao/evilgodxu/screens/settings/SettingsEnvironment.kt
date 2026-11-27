package com.yichao.evilgodxu.screens.settings

import android.content.Context
import android.provider.Settings
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.cache.CacheInventory
import com.yichao.evilgodxu.data.music.api.MusicHttpClient
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request

/** 导入结果：文案已拼好，ViewModel 只放状态 */
data class ProxyImportMessage(val text: String?, val failed: Boolean)

/** 平台能力出口，ViewModel 不持 Context */
interface SettingsEnvironment {

    val appVersion: String

    // 音源状态流，外部与页内共用
    val proxySources: StateFlow<List<ProxySourceSpec>>

    suspend fun importProxySource(content: String): ProxyImportMessage

    suspend fun setProxySourceEnabled(name: String, enabled: Boolean)

    suspend fun removeProxySource(name: String)

    suspend fun sampleCacheBytes(): Long

    // 语言热切换属界面侧动作
    fun applyLanguage(language: AppLanguage)

    fun canDrawOverlays(): Boolean

    fun startOverlayGrantMonitor()

    fun stopOverlayGrantMonitor()

    fun isNotificationPolicyGranted(): Boolean

    fun watchNotificationPolicyGrant()
}

// Android 实现，逐项委托
class AndroidSettingsEnvironment(
    private val context: Context,
    private val localizationManager: LocalizationManager,
    private val proxySourceStore: ProxySourceStore,
) : SettingsEnvironment {

    override val appVersion: String
        get() = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName.orEmpty()

    override val proxySources: StateFlow<List<ProxySourceSpec>>
        get() = proxySourceStore.sources

    // 链接先拉取，文本直接解析
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
                else -> when (val result = proxySourceStore.import(raw)) {
                    is ProxyParseResult.Success -> ProxyImportMessage(text = null, failed = false)
                    is ProxyParseResult.Failure -> ProxyImportMessage(
                        text = context.getString(R.string.settings_proxy_source_import_failed, result.reason),
                        failed = true,
                    )
                }
            }
        }

    override suspend fun setProxySourceEnabled(name: String, enabled: Boolean) {
        withContext(Dispatchers.IO) { proxySourceStore.setEnabled(name, enabled) }
    }

    override suspend fun removeProxySource(name: String) {
        withContext(Dispatchers.IO) { proxySourceStore.remove(name) }
    }

    override suspend fun sampleCacheBytes(): Long = withContext(Dispatchers.IO) {
        CacheInventory.sample(context).sumOf { it.sizeBytes }
    }

    override fun applyLanguage(language: AppLanguage) {
        localizationManager.applyAppLocale(localizationManager.resolveLanguage(language))
        localizationManager.applySystemLocale(language)
    }

    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    // 授权后自动带回前台，手动返回也会对账
    override fun startOverlayGrantMonitor() {
        OverlayGrantMonitor.start(context) { appContext -> bringAppToFront(appContext) }
    }

    override fun stopOverlayGrantMonitor() = OverlayGrantMonitor.stop()

    override fun isNotificationPolicyGranted(): Boolean = isNotificationPolicyGranted(context)

    override fun watchNotificationPolicyGrant() {
        PermissionReturnMonitor.watch(context, PermissionType.NOTIFICATION_POLICY)
    }

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
