package com.yichao.evilgodxu.screens.metadata.component

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.metadata.MetadataUiState
import com.yichao.evilgodxu.utils.formatTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 歌词输入框最少展示行数：给整篇 LRC 一个可读的初始高度
private const val LYRICS_MIN_LINES = 6

/**
 * 元数据编辑表单：内嵌封面 + 基本信息 + 歌词 + 保存栏。
 *
 * 保存入口置于表单末尾（而非标题栏）：改动可能只发生在歌词这类靠下的字段，
 * 把提交按钮固定在顶部会让用户改完仍需回滚到顶部才能保存。
 */
@Composable
internal fun MetadataForm(
    uiState: MetadataUiState,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onAlbumChange: (String) -> Unit,
    onLyricsChange: (String) -> Unit,
    onCoverSelected: (ByteArray) -> Unit,
    onCoverRemoved: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 图片选择统一按 image/* 过滤，选中后读回原始字节交给状态持有者
    val coverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes != null && bytes.isNotEmpty()) onCoverSelected(bytes)
        }
    }
    // 无可写目标的曲目（纯在线流）不展示表单：编辑一份不会落盘的字段只会误导用户
    if (!uiState.editable) {
        Column(
            modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.metadata_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // 标签读取期间不展示表单：空字段与「文件里就是空的」在界面上无法区分，易被误保存
        if (uiState.loading) {
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.CenterHorizontally).height(28.dp),
                strokeWidth = 2.dp,
            )
            return@Column
        }
        // 编辑对象摘要：表单字段取自文件标签，文件名是「正在改哪个文件」的锚点
        if (uiState.fileName.isNotBlank()) {
            MetadataTrackSummary(
                fileName = uiState.fileName,
                durationText = if (uiState.durationMs > 0) formatTime(uiState.durationMs) else "",
            )
        }
        MetadataSection(title = stringResource(R.string.metadata_section_cover)) {
            MetadataCoverEditor(
                coverBytes = uiState.coverBytes,
                coverPresent = uiState.coverPresent,
                enabled = !uiState.saving,
                onPickCover = { coverLauncher.launch("image/*") },
                onRemoveCover = onCoverRemoved,
            )
        }
        MetadataSection(title = stringResource(R.string.metadata_section_basic)) {
            MetadataTextField(
                label = stringResource(R.string.metadata_field_title),
                value = uiState.title,
                onValueChange = onTitleChange,
                enabled = !uiState.saving,
            )
            MetadataTextField(
                label = stringResource(R.string.metadata_field_artist),
                value = uiState.artist,
                onValueChange = onArtistChange,
                enabled = !uiState.saving,
            )
            MetadataTextField(
                label = stringResource(R.string.metadata_field_album),
                value = uiState.album,
                onValueChange = onAlbumChange,
                enabled = !uiState.saving,
            )
            MetadataFieldHint(stringResource(R.string.metadata_field_hint))
        }
        MetadataSection(title = stringResource(R.string.metadata_section_lyrics)) {
            MetadataTextField(
                label = stringResource(R.string.metadata_field_lyrics),
                value = uiState.lyrics,
                onValueChange = onLyricsChange,
                enabled = !uiState.saving,
                singleLine = false,
                minLines = LYRICS_MIN_LINES,
            )
            MetadataFieldHint(stringResource(R.string.metadata_field_lyrics_hint))
        }
        Spacer(Modifier.height(16.dp))
        MetadataSaveBar(
            saving = uiState.saving,
            message = uiState.message,
            messageIsError = uiState.messageIsError,
            onSave = onSave,
        )
        Spacer(Modifier.height(24.dp))
    }
}
