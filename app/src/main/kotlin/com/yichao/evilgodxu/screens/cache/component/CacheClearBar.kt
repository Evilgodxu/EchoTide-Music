package com.yichao.evilgodxu.screens.cache.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.utils.formatBytes

// 底部清理栏：常驻可触达，展示合计占用并提供清理入口
@Composable
internal fun CacheClearBar(
    totalBytes: Long,
    clearing: Boolean,
    onClear: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        // 背景铺满至屏幕底部，内容自行避让系统导航栏（宿主为 edge-to-edge，Scaffold 未代管底部内边距）
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = stringResource(R.string.cache_total, formatBytes(totalBytes)),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
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
        }
    }
}
