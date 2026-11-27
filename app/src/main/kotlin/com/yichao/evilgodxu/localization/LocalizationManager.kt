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

// 应用语言转 Locale；SYSTEM 用设备语言
fun AppLanguage.toLocale(systemLocale: Locale = Locale.getDefault()): Locale = when (this) {
    AppLanguage.SYSTEM -> systemLocale
    AppLanguage.CHINESE -> Locale.SIMPLIFIED_CHINESE
    AppLanguage.ENGLISH -> Locale.ENGLISH
}

// 语言管理：转 Locale、建本地化上下文
class LocalizationManager(
    private val context: Context,
) {
    // 取系统资源语言，getDefault 会被应用内改写
    private val systemLocale: Locale = Resources.getSystem().configuration.locales.let { locales ->
        if (locales.isEmpty()) Locale.getDefault() else locales[0]
    }

    fun resolveLanguage(language: AppLanguage): Locale = language.toLocale(systemLocale)

    // 供独立窗口同步语言的 Activity 引用
    private var activity: Activity? = null

    // 单 Activity 在 onCreate 绑定
    fun bindActivity(activity: Activity) {
        this.activity = activity
    }

    // 解除绑定，避免持有已销毁实例
    fun unbindActivity(activity: Activity) {
        if (this.activity === activity) this.activity = null
    }

    fun createLocalizedContext(locale: Locale): Context {
        val config = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
        return context.createConfigurationContext(config)
    }

    // 同步 app 与 activity 资源，使弹窗跟随
    fun applyAppLocale(locale: Locale) {
        applyToResources(context.applicationContext.resources, locale)
        activity?.let { applyToResources(it.resources, locale) }
        Locale.setDefault(locale)
    }

    /** 写入系统应用语言偏好；跟随系统时写空列表 */
    fun applySystemLocale(language: AppLanguage) {
        val localeManager = context.getSystemService(LocaleManager::class.java) ?: return
        val locales = language.languageTag
            ?.let(LocaleList::forLanguageTags)
            ?: LocaleList.getEmptyLocaleList()
        // 按语言标签比较，避免误判反复写偏好
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

// 提供本地化 Context，切语言无需重建 Activity
@Composable
fun ProvideLocalizedContext(
    localizationManager: LocalizationManager,
    content: @Composable () -> Unit,
) {
    val appUiState = LocalAppUiState.current
    val localizedContext = localizationManager.createLocalizedContext(
        localizationManager.resolveLanguage(appUiState.language),
    )
    // 替换后显式保留结果回调宿主
    val registryOwner = LocalActivityResultRegistryOwner.current
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalActivityResultRegistryOwner provides registryOwner!!,
    ) {
        content()
    }
}