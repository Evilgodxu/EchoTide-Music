package com.yichao.evilgodxu.screens.settings.component.cache

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.component.section.GroupCard
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.utils.formatBytes

// 存储管理入口：副标题与缓存页合计同用一处文案，避免两处合计写法分叉
@Composable
fun Cache(
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
