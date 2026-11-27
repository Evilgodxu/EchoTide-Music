package com.yichao.evilgodxu.screens.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 设置内容列：统一内边距、滚动与导航栏避让 */
@Composable
internal fun SettingsScrollColumn(
    innerPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
            // 无底部栏代管，末项自行避让导航栏
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}
