package com.yichao.evilgodxu.screens.settings.component.proxy

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.component.section.GroupCard
import com.yichao.evilgodxu.ui.icons.AppIcons

// 代理音源入口，副标题显示数量
@Composable
fun ProxySourceEntry(sourceCount: Int, onClick: () -> Unit) {
    GroupCard(title = stringResource(R.string.settings_section_sources)) {
        SettingsEntry(
            icon = AppIcons.Folder,
            title = stringResource(R.string.settings_entry_proxy_source),
            subtitle = if (sourceCount == 0) {
                stringResource(R.string.settings_entry_proxy_source_empty)
            } else {
                pluralStringResource(
                    R.plurals.settings_proxy_source_count,
                    sourceCount,
                    sourceCount,
                )
            },
            onClick = onClick,
        )
    }
}
