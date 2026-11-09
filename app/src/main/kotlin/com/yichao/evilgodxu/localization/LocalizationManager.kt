package com.yichao.evilgodxu.localization

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.yichao.evilgodxu.LocalAppUiState
import com.yichao.evilgodxu.data.settings.AppLanguage
import java.util.Locale

// 应用语言转 Locale；systemLocale 为跟随系统时的实际语言
fun AppLanguage.toLocale(systemLocale: Locale = Locale.getDefault()): Locale = when (this) {
    AppLanguage.SYSTEM -> systemLocale
    AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
    AppLanguage.ENGLISH -> Locale.ENGLISH
}

// 语言管理器：负责语言转 Locale、创建本地化上下文，并同步 app/activity 层 Resources
// 语言来源由界面层经 ActivityViewModel 的 CompositionLocal 提供，本类不直连数据源
class LocalizationManager(
    private val context: Context,
) {
    // 构造时捕获设备语言。取系统资源而非 Locale.getDefault()：后者会随应用内切换改写，
    // 也可能已是上个进程写入的系统「应用语言」，跟随系统时应解析为真实的设备语言
    private val systemLocale: Locale = Resources.getSystem().configuration.locales.let { locales ->
        if (locales.isEmpty()) Locale.getDefault() else locales[0]
    }

    fun resolveLanguage(language: AppLanguage): Locale = language.toLocale(systemLocale)

    // 当前 Activity 引用，用于对话框等独立窗口同步语言
    private var activity: Activity? = null

    // 绑定当前 Activity，单 Activity 应用在 onCreate 时注册
    fun bindActivity(activity: Activity) {
        this.activity = activity
    }

    // Activity 销毁时解除绑定，避免单例长期持有已销毁的 Activity
    fun unbindActivity(activity: Activity) {
        if (this.activity === activity) this.activity = null
    }

    fun createLocalizedContext(locale: Locale): Context {
        val config = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
        return context.createConfigurationContext(config)
    }

    // 同步更新 app 层与 activity 层 Resources 及默认 Locale，
    // 使 Dialog/Toast 等使用窗口 context 的资源跟随语言
    fun applyAppLocale(locale: Locale) {
        applyToResources(context.applicationContext.resources, locale)
        activity?.let { applyToResources(it.resources, locale) }
        Locale.setDefault(locale)
    }

    /**
     * 把应用语言写入系统级「应用语言」偏好。
     *
     * 系统在下次启动创建 Activity 之前即按它配置资源，因此冷启动首帧不再需要自建同步镜像。
     * 跟随系统时写入空列表，由系统按设备语言解析。
     */
    fun applySystemLocale(language: AppLanguage) {
        val localeManager = context.getSystemService(LocaleManager::class.java) ?: return
        val locales = language.languageTag
            ?.let(LocaleList::forLanguageTags)
            ?: LocaleList.getEmptyLocaleList()
        // 按语言标签比较：LocaleList 的相等判定可能不认逐项相同的两个实例，
        // 误判会每次启动都写一次系统偏好并触发配置变更
        if (localeManager.applicationLocales.toLanguageTags() != locales.toLanguageTags()) {
            localeManager.applicationLocales = locales
        }
    }

    private fun applyToResources(resources: Resources, locale: Locale) {
        val config = Configuration(resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}

// 提供本地化 Context 的 Composable，语言切换无需重建 Activity
@Composable
fun ProvideLocalizedContext(
    localizationManager: LocalizationManager,
    content: @Composable () -> Unit,
) {
    val appUiState = LocalAppUiState.current
    val localizedContext = localizationManager.createLocalizedContext(
        localizationManager.resolveLanguage(appUiState.language),
    )
    // 替换 LocalContext 后其不再能解析到宿主 Activity，需显式保留 ActivityResultRegistryOwner
    val registryOwner = LocalActivityResultRegistryOwner.current
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalActivityResultRegistryOwner provides registryOwner!!,
    ) {
        content()
    }
}