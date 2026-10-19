package com.yichao.evilgodxu.screens.settings.component.language

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.section.GroupCard

@Composable
fun Language(language: AppLanguage, onLanguageSelected: (AppLanguage) -> Unit, onShowDialog: () -> Unit) {
    GroupCard(title = stringResource(R.string.settings_section_language)) {
        SettingsEntry(
            icon = AppIcons.Language,
            title = stringResource(R.string.settings_language_title),
            subtitle = when (language) {
                AppLanguage.SYSTEM -> stringResource(R.string.language_system)
                AppLanguage.CHINESE -> stringResource(R.string.language_chinese)
                AppLanguage.ENGLISH -> stringResource(R.string.language_english)
            },
            onClick = onShowDialog,
        )
    }
}
