package com.yichao.evilgodxu.screens.cache.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.cache.CacheCategory
import com.yichao.evilgodxu.data.cache.CacheScope
import com.yichao.evilgodxu.data.cache.CacheUsage
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.section.GroupCard
import com.yichao.evilgodxu.utils.formatBytes

// 缓存明细：按归属范围分卡片展示，卡片划分与清理作用范围一一对应
@Composable
internal fun CacheUsageGroups(
    usages: List<CacheUsage>,
    innerPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        CacheUsageCard(R.string.cache_group_system, usages, CacheScope.SYSTEM_CACHE)
        CacheUsageCard(R.string.cache_group_private, usages, CacheScope.PRIVATE)
        CacheUsageCard(R.string.cache_group_user, usages, CacheScope.USER_VISIBLE)
        Text(
            text = stringResource(R.string.cache_clear_hint),
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

// 单张归属卡片：空组整卡略去，未采到数据时不留空标题
@Composable
private fun CacheUsageCard(
    @StringRes titleRes: Int,
    usages: List<CacheUsage>,
    scope: CacheScope,
) {
    val group = usages.filter { it.scope == scope }
    if (group.isEmpty()) return
    GroupCard(title = stringResource(titleRes)) {
        group.forEach { usage -> CacheUsageRow(usage) }
    }
}

// 单行缓存占用：分类名 + 文件数与占用大小
@Composable
private fun CacheUsageRow(usage: CacheUsage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(usage.category.labelRes),
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.cache_usage, usage.fileCount, formatBytes(usage.sizeBytes)),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

// 分类展示名一律取自资源，与日志台账中的内部标签互不牵连
@get:StringRes
private val CacheCategory.labelRes: Int
    get() = when (this) {
        CacheCategory.IMAGE -> R.string.cache_category_image
        CacheCategory.TEMP_FILE -> R.string.cache_category_temp
        CacheCategory.LOG -> R.string.cache_category_log
        CacheCategory.COVER -> R.string.cache_category_cover
        CacheCategory.LYRIC -> R.string.cache_category_lyric
        CacheCategory.AUDIO -> R.string.cache_category_audio
        CacheCategory.UPDATE_PACKAGE -> R.string.cache_category_update
        CacheCategory.ANALYSIS -> R.string.cache_category_analysis
    }
