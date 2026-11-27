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
 * 单栏容器：窄窗满宽，宽窗限宽居中；不管内边距与滚动
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
