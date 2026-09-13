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

// 清理入口：挂在合计占用下方，作用范围限于「可清理」卡片，不随某张归属卡片略去。
// 无可清理产出时一并置灰，避免给出点了也不会有任何结果的按钮
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
