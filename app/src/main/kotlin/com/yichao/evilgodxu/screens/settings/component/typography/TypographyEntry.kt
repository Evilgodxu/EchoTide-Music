package com.yichao.evilgodxu.screens.settings.component.typography

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.component.section.GroupCard
import com.yichao.evilgodxu.ui.icons.AppIcons

// 歌词排版入口：排版参数自持一份状态并经自己的 ViewModel 落盘，故走独立路由而非页内层级
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
