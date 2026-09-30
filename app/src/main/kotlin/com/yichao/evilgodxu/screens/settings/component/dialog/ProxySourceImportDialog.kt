package com.yichao.evilgodxu.screens.settings.component.dialog

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.AppDialog
import com.yichao.evilgodxu.ui.component.DialogOption

// 代理音源导入方式选择对话框：本地/链接/文本
@Composable
fun ProxySourceImportDialog(
    onDismiss: () -> Unit,
    onLocalImport: () -> Unit,
    onLinkImport: () -> Unit,
    onTextImport: () -> Unit,
) {
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.settings_proxy_source_dialog_title),
    ) {
        DialogOption(
            label = stringResource(R.string.settings_proxy_source_import_local),
            onClick = onLocalImport,
        )
        DialogOption(
            label = stringResource(R.string.settings_proxy_source_import_link),
            onClick = onLinkImport,
        )
        DialogOption(
            label = stringResource(R.string.settings_proxy_source_import_text),
            onClick = onTextImport,
        )
    }
}
