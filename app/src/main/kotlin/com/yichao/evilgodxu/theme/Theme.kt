package com.yichao.evilgodxu.theme

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.drawToBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yichao.evilgodxu.LocalMainViewModel
import com.yichao.evilgodxu.data.settings.ThemeMode

class ThemeTransitionController {
    var request: ((Offset) -> Unit)? = null

    fun revealAt(origin: Offset) {
        request?.invoke(origin)
    }
}

val LocalThemeTransitionController = androidx.compose.runtime.staticCompositionLocalOf<ThemeTransitionController> {
    error("ThemeTransitionController is not provided")
}

// 成功态配色（随主题切换）
val LocalSuccessColor = androidx.compose.runtime.staticCompositionLocalOf { md_theme_light_success }

// 当前是否为深色主题，供子页面按需覆盖状态栏样式
val LocalIsDarkTheme = androidx.compose.runtime.staticCompositionLocalOf { false }

// 状态栏是否采用浅色外观（深色图标）；默认跟随主题，深色背景页面（如首页）可覆盖为 false 固定白色图标
val LocalStatusBarLight = androidx.compose.runtime.staticCompositionLocalOf { false }

// 界面层声明的系统栏状态：图标外观与沉浸请求。窗口操作由 Activity 统一收敛下发，
// 界面侧不直接调用 insetsController，避免多处写入互相覆盖、或被窗口切换过程吞掉
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

    // 首页竖屏沉浸请求（仅隐藏状态栏），由首页页面写入、离开时撤销
    var isHomePortraitImmersive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            onChanged?.invoke()
        }

    // 声明变化时的即时下发回调，由 Activity 注册
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
    val appUiState by LocalMainViewModel.current.uiState.collectAsStateWithLifecycle()

    val isDarkTheme = when (appUiState.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> darkTheme
    }

    val transitionController = remember { ThemeTransitionController() }
    var previousBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var revealOrigin by remember { mutableStateOf(Offset.Zero) }
    val revealProgress = remember { Animatable(1f) }
    val view = LocalView.current

    transitionController.request = { origin ->
        if (view.width > 0 && view.height > 0) {
            previousBitmap = view.drawToBitmap()
            revealOrigin = origin
        }
    }

    LaunchedEffect(isDarkTheme, previousBitmap) {
        if (previousBitmap != null) {
            revealProgress.snapTo(0f)
            revealProgress.animateTo(1f, tween(800))
            previousBitmap = null
        }
    }

    CompositionLocalProvider(
        LocalThemeTransitionController provides transitionController,
        LocalSuccessColor provides if (isDarkTheme) md_theme_dark_success else md_theme_light_success,
        LocalIsDarkTheme provides isDarkTheme,
        // 状态栏默认跟随主题：浅色主题用深色图标，深色主题用白色图标
        LocalStatusBarLight provides !isDarkTheme,
    ) {
        MaterialTheme(
            colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        val bitmap = previousBitmap ?: return@drawWithContent
                        drawOldThemeOutsideReveal(bitmap, revealOrigin, revealProgress.value)
                    },
            ) {
                content()
            }
        }
    }
}

private fun DrawScope.drawOldThemeOutsideReveal(
    bitmap: Bitmap,
    origin: Offset,
    progress: Float,
) {
    val radius = maxRevealRadius(origin, size.width, size.height) * progress
    val path = Path().apply {
        addOval(
            androidx.compose.ui.geometry.Rect(
                left = origin.x - radius,
                top = origin.y - radius,
                right = origin.x + radius,
                bottom = origin.y + radius,
            ),
        )
    }
    clipPath(path, ClipOp.Difference) {
        drawImage(bitmap.asImageBitmap())
    }
}

private fun maxRevealRadius(origin: Offset, width: Float, height: Float): Float {
    return maxOf(
        origin.getDistance(),
        Offset(width, 0f).minus(origin).getDistance(),
        Offset(0f, height).minus(origin).getDistance(),
        Offset(width, height).minus(origin).getDistance(),
    )
}
