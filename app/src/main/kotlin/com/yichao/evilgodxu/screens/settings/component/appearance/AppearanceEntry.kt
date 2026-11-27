package com.yichao.evilgodxu.screens.settings.component.appearance

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.component.section.GroupCard
import com.yichao.evilgodxu.ui.icons.AppIcons

// 语言外观入口，页内层级展开
@Composable
fun AppearanceEntry(onClick: () -> Unit) {
    GroupCard(title = stringResource(R.string.settings_section_appearance)) {
        SettingsEntry(
            icon = AppIcons.Palette,
            title = stringResource(R.string.settings_entry_appearance_language),
            subtitle = stringResource(R.string.settings_entry_appearance_language_desc),
            onClick = onClick,
        )
    }
}
