package com.yichao.evilgodxu.screens.settings.component.typography

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.component.section.GroupCard
import com.yichao.evilgodxu.ui.icons.AppIcons

// 排版入口，自带 ViewModel 与状态
@Composable
fun TypographyEntry(onClick: () -> Unit) {
    GroupCard(title = stringResource(R.string.settings_section_typography)) {
        SettingsEntry(
            icon = AppIcons.TextFields,
            title = stringResource(R.string.settings_typography_title),
            subtitle = stringResource(R.string.settings_typography_desc),
            onClick = onClick,
        )
    }
}
