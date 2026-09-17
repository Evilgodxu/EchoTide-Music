package com.yichao.evilgodxu.screens.home.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.data.music.api.MusicQuality
import com.yichao.evilgodxu.data.music.proxy.PlaylistSyncer
import com.yichao.evilgodxu.data.music.proxy.RemotePlaylistLink
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.ui.component.DialogCard
import com.yichao.evilgodxu.ui.component.QualitySelectDialog
import kotlinx.coroutines.launch

// 从平台分享链接导入歌单：输入链接 → 解析预览歌单 → 选定音质 → 确认后回调启动后台同步
@Composable
internal fun PlaylistImportDialog(
    visible: Boolean,
    onSyncStart: (link: RemotePlaylistLink, playlistName: String, quality: MusicQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val invalidLinkMessage = stringResource(R.string.playlist_import_invalid_link)
    val fetchFailedMessage = stringResource(R.string.playlist_import_fetch_failed)
    val defaultName = stringResource(R.string.playlist_import_default_name)
    var link by remember { mutableStateOf("") }
    var parsing by remember { mutableStateOf(false) }
    var remoteLink by remember { mutableStateOf<RemotePlaylistLink?>(null) }
    var playlistName by remember { mutableStateOf("") }
    var totalSongs by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var showQuality by remember { mutableStateOf(false) }

    fun startParse() {
        val text = link.trim()
        if (text.isEmpty() || parsing) return
        parsing = true
        remoteLink = null
        error = null
        scope.launch {
            val parsed = PlaylistSyncer.parseLink(context, text)
            if (parsed == null) {
                error = invalidLinkMessage
                parsing = false
                return@launch
            }
            val fetched = PlaylistSyncer.fetchRemote(context, parsed)
            if (fetched == null) {
                error = fetchFailedMessage
                parsing = false
                return@launch
            }
            remoteLink = parsed
            totalSongs = fetched.songs.size
            playlistName = fetched.name.ifBlank { defaultName }
            parsing = false
        }
    }

    DialogCard(onDismiss = { if (!parsing) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.playlist_import_title),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (remoteLink == null) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.playlist_import_link_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.playlist_import_tip),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
                ErrorText(error)
                Spacer(modifier = Modifier.height(10.dp))
                DialogButtons(
                    confirmText = stringResource(
                        if (parsing) R.string.playlist_import_parsing else R.string.playlist_import_parse
                    ),
                    confirmEnabled = !parsing && link.isNotBlank(),
                    onConfirm = { startParse() },
                    onDismiss = onDismiss,
                )
            } else {
                OutlinedTextField(
                    value = playlistName,
                    onValueChange = { playlistName = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.playlist_import_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.playlist_import_preview_songs, totalSongs),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
                ErrorText(error)
                Spacer(modifier = Modifier.height(10.dp))
                DialogButtons(
                    confirmText = stringResource(R.string.playlist_import_start),
                    onConfirm = { showQuality = true },
                    onDismiss = onDismiss,
                )
            }
        }
    }
    // 选定音质后再启动同步；复用音质选择对话框，由用户自行决定下载音质
    if (showQuality) {
        val link = remoteLink
        if (link != null) {
            QualitySelectDialog(
                title = stringResource(R.string.playlist_import_quality_title),
                onSelect = { quality ->
                    showQuality = false
                    onSyncStart(
                        link,
                        playlistName.trim().ifBlank { defaultName },
                        quality,
                    )
                },
                onDismiss = { showQuality = false },
            )
        }
    }
}

// 弹窗内的错误提示
@Composable
private fun ErrorText(message: String?) {
    if (message == null) return
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
    )
}

// 取消/确认按钮行，样式与新建歌单弹窗一致
@Composable
private fun DialogButtons(
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.widthIn(max = 220.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            onClick = onDismiss,
        ) {
            Text(
                text = stringResource(R.string.music_panel_rename_cancel),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            color = if (confirmEnabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            enabled = confirmEnabled,
            onClick = onConfirm,
        ) {
            Text(
                text = confirmText,
                color = if (confirmEnabled) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
}
