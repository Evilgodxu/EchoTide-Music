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

/**
 * 设置类页面的内容列：根页与页内层级共用同一套内边距、滚动与导航栏避让。
 *
 * @param innerPadding Scaffold 内边距：由组装器透传，内容避让标题栏
 */
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
            // 宿主为 edge-to-edge 且页面无底部栏代管：末项（关于分组）须自行避让系统导航栏，
            // 否则滚到底部时被三键导航的虚拟按键压住
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}
