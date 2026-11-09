package com.yichao.evilgodxu.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified

/**
 * 单栏内容容器：窄窗占满可用宽度，宽窗限制行宽并水平居中。
 *
 * 只改变行宽、不改变功能区块的组成，因此形态差异用一个宽度上限表达；
 * 只有当两种形态的区块结构确实不同时，才值得拆成两套组装器。
 *
 * 只负责行宽与居中，不施加内边距与滚动：内容组件各自已按内边距与滚动容器组织，
 * 在这里再套一层会重复消费顶部内边距与滚动状态。
 *
 * @param maxContentWidth 行宽上限，[Dp.Unspecified] 表示不限制。
 */
@Composable
fun ContentPane(
    maxContentWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Box(
            modifier = if (maxContentWidth.isSpecified) {
                Modifier.widthIn(max = maxContentWidth).fillMaxSize()
            } else {
                Modifier.fillMaxSize()
            },
            content = content,
        )
    }
}
