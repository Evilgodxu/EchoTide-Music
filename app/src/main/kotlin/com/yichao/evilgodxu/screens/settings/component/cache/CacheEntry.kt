package com.yichao.evilgodxu.screens.settings.component.cache

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.component.section.GroupCard
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.utils.formatBytes

// 存储入口，副标题显示合计
@Composable
fun CacheEntry(
    totalBytes: Long,
    onClick: () -> Unit,
) {
    GroupCard(title = stringResource(R.string.settings_section_cache)) {
        SettingsEntry(
            icon = AppIcons.Storage,
            title = stringResource(R.string.settings_cache_entry_title),
            subtitle = stringResource(R.string.cache_total, formatBytes(totalBytes)),
            onClick = onClick,
        )
    }
}
