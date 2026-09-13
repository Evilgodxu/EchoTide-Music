package com.yichao.evilgodxu.screens.settings.component.cache

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.component.section.GroupCard
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.utils.formatBytes

// 缓存管理入口：展示合计占用，明细与清理在缓存页内完成
@Composable
fun Cache(
    totalBytes: Long,
    onClick: () -> Unit,
) {
    GroupCard(title = stringResource(R.string.settings_section_cache)) {
        SettingsEntry(
            icon = AppIcons.Delete,
            title = stringResource(R.string.settings_cache_entry_title),
            subtitle = stringResource(R.string.settings_cache_entry_desc, formatBytes(totalBytes)),
            onClick = onClick,
        )
    }
}
