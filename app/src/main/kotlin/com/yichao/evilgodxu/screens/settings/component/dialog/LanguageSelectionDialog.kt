package com.yichao.evilgodxu.screens.settings.component.dialog

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.data.settings.AppLanguage
import com.yichao.evilgodxu.ui.component.AppDialog
import com.yichao.evilgodxu.ui.component.DialogOption

@Composable
fun LanguageSelectionDialog(
    currentLanguage: AppLanguage,
    onDismiss: () -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_language_dialog_title),
    ) {
        AppLanguage.entries.forEach { language ->
            DialogOption(
                label = stringResource(languageLabelRes(language)),
                selected = currentLanguage == language,
                onClick = { onLanguageSelected(language) },
            )
        }
    }
}

// 语言选项对应的展示文案
private fun languageLabelRes(language: AppLanguage): Int = when (language) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.CHINESE -> R.string.language_chinese
    AppLanguage.ENGLISH -> R.string.language_english
}
