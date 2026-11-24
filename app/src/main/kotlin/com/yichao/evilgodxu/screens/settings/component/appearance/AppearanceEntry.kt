package com.yichao.evilgodxu.screens.settings.component.appearance

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.component.section.GroupCard
import com.yichao.evilgodxu.ui.icons.AppIcons

// 语言外观入口：主题、语言与渲染三组都在页内层级展开，读写走设置页自己的状态
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
