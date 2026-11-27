package com.yichao.evilgodxu.screens.settings.component.appearance

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.data.settings.ThemeMode
import com.yichao.evilgodxu.screens.settings.component.SettingsScrollColumn
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsSwitchRow
import com.yichao.evilgodxu.ui.component.section.GroupCard
import com.yichao.evilgodxu.ui.icons.AppIcons

/** 语言外观页：主题、语言、渲染三组 */
@Composable
internal fun AppearanceLanguagePane(
    themeMode: ThemeMode,
    language: AppLanguage,
    wordByWordRendering: Boolean,
    backgroundFlow: Boolean,
    innerPadding: PaddingValues,
    onThemeClick: (Offset) -> Unit,
    onShowLanguageDialog: () -> Unit,
    onWordByWordRenderingChange: (Boolean) -> Unit,
    onBackgroundFlowChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScrollColumn(innerPadding = innerPadding, modifier = modifier) {
        GroupCard(title = stringResource(R.string.settings_theme_title)) {
            SettingsEntry(
                icon = AppIcons.Palette,
                title = when (themeMode) {
                    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
                    ThemeMode.DARK -> stringResource(R.string.theme_dark)
                    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                },
                onClickWithPosition = onThemeClick,
            )
        }
        GroupCard(title = stringResource(R.string.settings_language_title)) {
            SettingsEntry(
                icon = AppIcons.Language,
                title = when (language) {
                    AppLanguage.SYSTEM -> stringResource(R.string.language_system)
                    AppLanguage.CHINESE -> stringResource(R.string.language_chinese)
                    AppLanguage.ENGLISH -> stringResource(R.string.language_english)
                },
                onClick = onShowLanguageDialog,
            )
        }
        GroupCard(title = stringResource(R.string.settings_section_rendering)) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_word_by_word_title),
                description = stringResource(R.string.settings_word_by_word_desc),
                checked = wordByWordRendering,
                onCheckedChange = onWordByWordRenderingChange,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings_background_flow_title),
                description = stringResource(R.string.settings_background_flow_desc),
                checked = backgroundFlow,
                onCheckedChange = onBackgroundFlowChange,
            )
        }
    }
}
