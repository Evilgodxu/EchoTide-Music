package com.yichao.evilgodxu.screens.cache.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yichao.evilgodxu.R

// 清理入口：与「可清理」分组绑定，挂在该卡片下方，不承载合计占用
@Composable
internal fun CacheClearAction(
    clearing: Boolean,
    onClear: () -> Unit,
) {
    Button(
        onClick = onClear,
        enabled = !clearing,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        Text(stringResource(if (clearing) R.string.cache_clearing else R.string.cache_clear))
    }
}
