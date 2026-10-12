package com.yichao.evilgodxu.screens.home.component.search

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 搜索面板内的区块标题：历史区块与结果区块共用同一款样式。
// 两处各写一遍字号/字重/颜色，随后必然只改一处而走形，故收敛成组件
@Composable
internal fun SearchSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}
