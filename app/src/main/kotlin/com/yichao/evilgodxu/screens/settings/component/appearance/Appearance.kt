package com.yichao.evilgodxu.screens.settings.component.appearance

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.section.GroupCard

@Composable
fun Appearance(themeMode: ThemeMode, onThemeClick: (Offset) -> Unit) {
    GroupCard(title = stringResource(R.string.settings_section_appearance)) {
        SettingsEntry(
            icon = AppIcons.Palette,
            title = stringResource(R.string.settings_theme_title),
            subtitle = when (themeMode) {
                ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                ThemeMode.DARK -> stringResource(R.string.theme_dark)
                ThemeMode.LIGHT -> stringResource(R.string.theme_light)
            },
            onClick = {},
            onClickWithPosition = { position ->
                onThemeClick(position)
            },
        )
    }
}
