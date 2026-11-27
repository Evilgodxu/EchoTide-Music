package com.yichao.evilgodxu.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import com.yichao.evilgodxu.LocalAppUiState
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.ui.component.ScreenRevealController
import com.yichao.evilgodxu.ui.component.ScreenRevealHost

// 主题切换整屏揭示入口；圆心为点击位置
val LocalThemeTransitionController = androidx.compose.runtime.staticCompositionLocalOf<ScreenRevealController> {
    error("ThemeTransitionController is not provided")
}

// 成功态配色（随主题切换）
val LocalSuccessColor = androidx.compose.runtime.staticCompositionLocalOf { md_theme_light_success }

// 当前是否深色，供子页面覆盖状态栏
val LocalIsDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }

// 状态栏是否浅色外观；首页可覆盖为 false
val LocalStatusBarLight = androidx.compose.runtime.staticCompositionLocalOf { false }

// 系统栏声明，窗口操作统一由 Activity 下发
object SystemBarAppearance {
    // 状态栏图标外观（浅色外观 = 深色图标）
    var isLightStatusBars: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            onChanged?.invoke()
        }

    var isLightNavigationBars: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            onChanged?.invoke()
        }

    // 首页竖屏沉浸请求，离开首页即撤销
    var isHomePortraitImmersive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            onChanged?.invoke()
        }

    // 声明变化回调，由 Activity 注册
    var onChanged: (() -> Unit)? = null
}

// 声明当前页面所需的系统栏图标外观
@Composable
fun StatusBarStyleEffect() {
    val statusBarLight = LocalStatusBarLight.current
    val navigationBarLight = !LocalIsDarkTheme.current
    SideEffect {
        SystemBarAppearance.isLightStatusBars = statusBarLight
        SystemBarAppearance.isLightNavigationBars = navigationBarLight
    }
}

val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    outline = md_theme_light_outline,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    surfaceContainerLowest = md_theme_light_surfaceContainerLowest,
    surfaceContainerLow = md_theme_light_surfaceContainerLow,
    surfaceContainer = md_theme_light_surfaceContainer,
    surfaceContainerHigh = md_theme_light_surfaceContainerHigh,
    surfaceContainerHighest = md_theme_light_surfaceContainerHighest,
    inverseSurface = md_theme_light_inverseSurface,
    inverseOnSurface = md_theme_light_inverseOnSurface,
    inversePrimary = md_theme_light_inversePrimary,
    surfaceTint = md_theme_light_surfaceTint,
    surfaceDim = md_theme_light_surfaceDim,
    surfaceBright = md_theme_light_surfaceBright,
    outlineVariant = md_theme_light_outlineVariant,
    scrim = md_theme_light_scrim,
)

val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    outline = md_theme_dark_outline,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    surfaceContainerLowest = md_theme_dark_surfaceContainerLowest,
    surfaceContainerLow = md_theme_dark_surfaceContainerLow,
    surfaceContainer = md_theme_dark_surfaceContainer,
    surfaceContainerHigh = md_theme_dark_surfaceContainerHigh,
    surfaceContainerHighest = md_theme_dark_surfaceContainerHighest,
    inverseSurface = md_theme_dark_inverseSurface,
    inverseOnSurface = md_theme_dark_inverseOnSurface,
    inversePrimary = md_theme_dark_inversePrimary,
    surfaceTint = md_theme_dark_surfaceTint,
    surfaceDim = md_theme_dark_surfaceDim,
    surfaceBright = md_theme_dark_surfaceBright,
    outlineVariant = md_theme_dark_outlineVariant,
    scrim = md_theme_dark_scrim,
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val appUiState = LocalAppUiState.current

    val isDarkTheme = when (appUiState.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> darkTheme
    }

    // 整屏揭示：点击瞬间交出新旧主题对照点
    val revealController = remember { ScreenRevealController() }

    CompositionLocalProvider(
        LocalThemeTransitionController provides revealController,
        LocalSuccessColor provides if (isDarkTheme) md_theme_dark_success else md_theme_light_success,
        LocalIsDarkTheme provides isDarkTheme,
        // 状态栏默认跟随主题
        LocalStatusBarLight provides !isDarkTheme,
    ) {
        MaterialTheme(
            colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
        ) {
            ScreenRevealHost(controller = revealController) {
                content()
            }
        }
    }
}
