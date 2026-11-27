package com.yichao.evilgodxu.screens.settings.level.storage

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.R

// 清理仅作用于可清理卡片，无项则置灰
@Composable
internal fun CacheClearAction(
    clearing: Boolean,
    hasClearable: Boolean,
    onClear: () -> Unit,
) {
    Button(
        onClick = onClear,
        enabled = hasClearable && !clearing,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Text(stringResource(if (clearing) R.string.cache_clearing else R.string.cache_clear))
    }
}
