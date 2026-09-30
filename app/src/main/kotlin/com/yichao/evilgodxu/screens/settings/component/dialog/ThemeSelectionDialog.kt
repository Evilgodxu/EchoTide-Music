package com.yichao.evilgodxu.screens.settings.component.dialog

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.ui.component.AppDialog
import com.yichao.evilgodxu.ui.component.DialogOption

@Composable
fun ThemeSelectionDialog(
    currentTheme: ThemeMode,
    onDismiss: () -> Unit,
    onThemeSelected: (ThemeMode) -> Unit,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_theme_dialog_title),
    ) {
        ThemeMode.entries.forEach { themeMode ->
            DialogOption(
                label = stringResource(themeLabelRes(themeMode)),
                selected = currentTheme == themeMode,
                onClick = { onThemeSelected(themeMode) },
            )
        }
    }
}

// 主题模式对应的展示文案
private fun themeLabelRes(themeMode: ThemeMode): Int = when (themeMode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.LIGHT -> R.string.theme_light
}
