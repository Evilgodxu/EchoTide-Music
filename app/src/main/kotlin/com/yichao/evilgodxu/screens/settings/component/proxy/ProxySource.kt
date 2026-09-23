package com.yichao.evilgodxu.screens.settings.component.proxy

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.proxy.ProxySourceSpec
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.settings.component.dialog.ProxySourceImportDialog
import com.yichao.evilgodxu.screens.settings.component.dialog.ProxySourceInputDialog
import com.yichao.evilgodxu.screens.settings.component.entry.SettingsEntry
import com.yichao.evilgodxu.ui.component.AppSwitch
import com.yichao.evilgodxu.ui.icons.AppIcons
import com.yichao.evilgodxu.ui.component.section.GroupCard
import kotlinx.coroutines.delay

// 代理音源设置：导入、管理第三方音源并展示导入结果
@Composable
fun ProxySource(
    sources: List<ProxySourceSpec>,
    importMessage: String?,
    importFailed: Boolean,
    onImport: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onMessageDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var inputMode by remember { mutableStateOf<ImportMode?>(null) }

    // 本地导入：读取所选文本文件的完整内容后交由导入流程解析
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (!text.isNullOrBlank()) onImport(text)
    }

    GroupCard(title = stringResource(R.string.settings_section_proxy_source)) {
        SettingsEntry(
            icon = AppIcons.Folder,
            title = stringResource(R.string.settings_proxy_source_import_title),
            subtitle = stringResource(R.string.settings_proxy_source_import_desc),
            onClick = { showImportDialog = true },
        )
        ImportedSourceList(sources, onToggle, onRemove)
        ImportMessage(
            message = importMessage,
            failed = importFailed,
            onClick = onMessageDismiss,
        )
    }

    if (showImportDialog) {
        ProxySourceImportDialog(
            onDismiss = { showImportDialog = false },
            onLocalImport = {
                showImportDialog = false
                filePickerLauncher.launch(arrayOf("text/*", "application/json", "application/javascript"))
            },
            onLinkImport = {
                showImportDialog = false
                inputMode = ImportMode.LINK
            },
            onTextImport = {
                showImportDialog = false
                inputMode = ImportMode.TEXT
            },
        )
    }

    inputMode?.let { mode ->
        ProxySourceInputDialog(
            title = stringResource(
                if (mode == ImportMode.LINK) R.string.settings_proxy_source_link_title
                else R.string.settings_proxy_source_text_title
            ),
            placeholder = stringResource(
                if (mode == ImportMode.LINK) R.string.settings_proxy_source_link_hint
                else R.string.settings_proxy_source_text_hint
            ),
            singleLine = mode == ImportMode.LINK,
            onDismiss = { inputMode = null },
            onConfirm = { content ->
                inputMode = null
                onImport(content)
            },
        )
    }
}

// 已导入音源列表：固定单行高度，默认展示前三个音源，超出后列表内滚动
@Composable
private fun ImportedSourceList(
    sources: List<ProxySourceSpec>,
    onToggle: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    if (sources.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_proxy_source_empty),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        return
    }
    // 三行以内按实际行数占位，超过三行固定三行高度并进入滚动
    val listHeight = PROXY_SOURCE_LIST_ROW_HEIGHT * minOf(sources.size, PROXY_SOURCE_LIST_VISIBLE_ROWS)
    LazyColumn(modifier = Modifier
        .fillMaxWidth()
        .height(listHeight)
    ) {
        items(sources.size, key = { index -> sources[index].name }) { index ->
            ProxySourceRow(
                source = sources[index],
                onToggle = onToggle,
                onRemove = onRemove,
            )
        }
    }
}

// 单个已导入音源行：信息列 + 启停开关 + 删除按钮
@Composable
private fun ProxySourceRow(
    source: ProxySourceSpec,
    onToggle: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    val textAlpha = if (source.enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PROXY_SOURCE_LIST_ROW_HEIGHT)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = listOf(source.name, source.version.takeIf { it.isNotBlank() }?.let { "v$it" })
                    .filterNotNull()
                    .joinToString(" · "),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
            )
            Text(
                text = stringResource(
                    R.string.settings_proxy_source_platforms,
                    // 自定义平台展示其声明的名称，内置平台无名称则直接展示平台键
                    source.platforms.entries.joinToString(" / ") { (key, platform) -> platform.name ?: key }
                ),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha * 0.6f),
            )
        }
        AppSwitch(
            checked = source.enabled,
            onCheckedChange = { checked -> onToggle(source.name, checked) },
        )
        IconButton(onClick = { onRemove(source.name) }) {
            Icon(
                imageVector = AppIcons.Delete,
                contentDescription = stringResource(R.string.settings_proxy_source_remove_desc),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

// 音源列表单行高度与列表可见行数
private val PROXY_SOURCE_LIST_ROW_HEIGHT = 56.dp
private const val PROXY_SOURCE_LIST_VISIBLE_ROWS = 3

// 最近一次导入结果提示：成功/失败着色不同；点击关闭或超时自动消失
@Composable
private fun ImportMessage(
    message: String?,
    failed: Boolean,
    onClick: () -> Unit,
) {
    if (message == null) return
    // 与成功提示一样不常驻，展示一段时间后自动关闭
    LaunchedEffect(message) {
        delay(PROXY_IMPORT_MESSAGE_DISMISS_MS)
        onClick()
    }
    Text(
        text = message,
        fontSize = 13.sp,
        color = if (failed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

// 导入结果提示自动消失时长
private const val PROXY_IMPORT_MESSAGE_DISMISS_MS = 3_000L

private enum class ImportMode { LINK, TEXT }
