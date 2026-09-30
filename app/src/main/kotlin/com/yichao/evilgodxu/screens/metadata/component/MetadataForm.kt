package com.yichao.evilgodxu.screens.metadata.component

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yichao.evilgodxu.R
import com.yichao.evilgodxu.screens.metadata.MetadataEditTarget
import com.yichao.evilgodxu.screens.metadata.MetadataField
import com.yichao.evilgodxu.screens.metadata.MetadataUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 元数据编辑表单：内嵌封面 + 基本信息 + 歌词。
 *
 * 页面没有保存入口 —— 条目改动在输入停顿后自动写回音频文件，
 * 用户无需记住「改完要点保存」，也不会因切走页面而丢掉改动。
 */
@Composable
internal fun MetadataForm(
    uiState: MetadataUiState,
    onEditStart: (MetadataEditTarget) -> Unit,
    onEditEnd: () -> Unit,
    onTitleChange: (String) -> Unit,
    onArtistChange: (String) -> Unit,
    onAlbumChange: (String) -> Unit,
    onLyricRawChange: (String) -> Unit,
    onLyricTranslationChange: (Int, String) -> Unit,
    onCoverSelected: (ByteArray) -> Unit,
    onCoverRemoved: () -> Unit,
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
    // 系统返回键收起键盘不经过点击路径：监听输入法可见性，一旦不可见即结束编辑态，
    // 使输入框回到展示态并释放焦点，而不是停在「无键盘的编辑态」
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(imeVisible) {
        if (!imeVisible) onEditEnd()
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
            // 点击输入框以外的空白区域即结束当前编辑并收起键盘：编辑被点击的可交互控件消费，
            // 只有空白区命中根节点。编辑态收起由用户的明确意图驱动，不依赖异步焦点事件
            .pointerInput(Unit) { detectTapGestures(onTap = { onEditEnd() }) }
            // 键盘避让：键盘弹出时底部收紧，配合文本框聚焦时的 bringIntoView 使输入框滚动到键盘上方
            .imePadding()
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
        // 封面不套分区卡片：直接展示，卡片背景只会压缩图片可用面积
        MetadataCoverEditor(
            coverBytes = uiState.coverBytes,
            coverPresent = uiState.coverPresent,
            enabled = !uiState.saving,
            onPickCover = { coverLauncher.launch("image/*") },
            onRemoveCover = onCoverRemoved,
        )
        MetadataFormHint(stringResource(R.string.metadata_edit_hint))
        MetadataStatusText(
            message = uiState.message,
            messageIsError = uiState.messageIsError,
            modifier = Modifier.fillMaxWidth(),
        )
        MetadataSection(title = stringResource(R.string.metadata_section_basic)) {
            BasicField(
                label = stringResource(R.string.metadata_field_title),
                value = uiState.title,
                field = MetadataField.TITLE,
                editing = uiState.editing,
                enabled = !uiState.saving,
                onValueChange = onTitleChange,
                onEditStart = onEditStart,
                onEditEnd = onEditEnd,
            )
            BasicField(
                label = stringResource(R.string.metadata_field_artist),
                value = uiState.artist,
                field = MetadataField.ARTIST,
                editing = uiState.editing,
                enabled = !uiState.saving,
                onValueChange = onArtistChange,
                onEditStart = onEditStart,
                onEditEnd = onEditEnd,
            )
            BasicField(
                label = stringResource(R.string.metadata_field_album),
                value = uiState.album,
                field = MetadataField.ALBUM,
                editing = uiState.editing,
                enabled = !uiState.saving,
                onValueChange = onAlbumChange,
                onEditStart = onEditStart,
                onEditEnd = onEditEnd,
            )
        }
        LyricsSection(
            lines = uiState.lyricLines,
            unparsable = uiState.lyricsUnparsable,
            editing = uiState.editing,
            lyricLineDraft = uiState.lyricLineDraft,
            enabled = !uiState.saving,
            onRawChange = onLyricRawChange,
            onTranslationChange = onLyricTranslationChange,
            onStartEdit = onEditStart,
            onEditDone = onEditEnd,
        )
        Spacer(Modifier.height(24.dp))
    }
}

// 基本信息条目：把字段枚举收在一处，避免三处重复判定当前编辑的是哪一行
@Composable
private fun BasicField(
    label: String,
    value: String,
    field: MetadataField,
    editing: MetadataEditTarget?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onEditStart: (MetadataEditTarget) -> Unit,
    onEditEnd: () -> Unit,
) {
    MetadataEntry(
        label = label,
        value = value,
        editing = editing == MetadataEditTarget.Field(field),
        enabled = enabled,
        onValueChange = onValueChange,
        onStartEdit = { onEditStart(MetadataEditTarget.Field(field)) },
        onEditDone = onEditEnd,
    )
}
